package com.titan.titancorebanking.fx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@RequiredArgsConstructor
@Slf4j
public class FxHedgingService {

    private final WebClient.Builder webClientBuilder;
    private final FxPositionRepository fxPositionRepository;

    @Value("${fx.data.url:https://api.frankfurter.app}")
    private String fxDataUrl;

    // STAFF ENGINEER FIX: Fully concurrent 2D map to prevent state corruption across threads
    private final Map<String, Map<String, Double>> qTable = new ConcurrentHashMap<>();

    // STAFF ENGINEER FIX: Thread-safe deque for sliding window
    private final Deque<BigDecimal> rateHistory = new ConcurrentLinkedDeque<>();

    private static final double LEARNING_RATE = 0.1;
    private static final double DISCOUNT_FACTOR = 0.95;
    private static final double EPSILON = 0.1;

    private BigDecimal lastRate = null;
    private String lastAction = null;
    private String lastStateKey = null;

    @Scheduled(fixedDelay = 30_000)
    public void evaluateAndHedge() {
        BigDecimal currentRate = fetchRate("USD", "KHR");
        if (currentRate == null) return;

        rateHistory.addLast(currentRate);
        while (rateHistory.size() > 10) {
            rateHistory.pollFirst();
        }

        String stateKey = computeStateKey(currentRate);
        String action = selectAction(stateKey);

        if (lastRate != null && lastAction != null) {
            double reward = computeReward(lastAction, lastRate, currentRate);
            updateQTable(lastStateKey, lastAction, stateKey, reward);
        }

        executeHedge(action, currentRate);

        lastRate = currentRate;
        lastAction = action;
        lastStateKey = stateKey;

        log.debug("FX Hedge Cycle: rate={} USD/KHR, state={}, action={}", currentRate, stateKey, action);
    }

    private String computeStateKey(BigDecimal currentRate) {
        if (rateHistory.size() < 2) return "STABLE_LOW";

        List<BigDecimal> rates = new ArrayList<>(rateHistory);
        BigDecimal prev = rates.get(rates.size() - 2);

        double changePct = currentRate.subtract(prev)
                .divide(prev, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        String trend = changePct > 0.5 ? "UP" : changePct < -0.5 ? "DOWN" : "STABLE";
        BigDecimal exposure = getTotalPendingExposure();

        String exposureLevel = exposure.compareTo(BigDecimal.valueOf(100_000)) > 0 ? "HIGH"
                : exposure.compareTo(BigDecimal.valueOf(10_000)) > 0 ? "MED" : "LOW";

        return trend + "_" + exposureLevel;
    }

    private String selectAction(String stateKey) {
        List<String> actions = List.of("HEDGE_FULL", "HEDGE_HALF", "HOLD", "UNHEDGE");
        if (Math.random() < EPSILON) {
            return actions.get((int) (Math.random() * actions.size()));
        }

        Map<String, Double> qValues = qTable.getOrDefault(stateKey, new ConcurrentHashMap<>());
        return actions.stream()
                .max(Comparator.comparingDouble(a -> qValues.getOrDefault(a, 0.0)))
                .orElse("HOLD");
    }

    private void updateQTable(String state, String action, String nextState, double reward) {
        // STAFF ENGINEER FIX: Ensure inner maps are also ConcurrentHashMap
        qTable.computeIfAbsent(state, k -> new ConcurrentHashMap<>());
        qTable.computeIfAbsent(nextState, k -> new ConcurrentHashMap<>());

        double currentQ = qTable.get(state).getOrDefault(action, 0.0);
        double maxNextQ = qTable.get(nextState).values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        double newQ = currentQ + LEARNING_RATE * (reward + DISCOUNT_FACTOR * maxNextQ - currentQ);
        qTable.get(state).put(action, newQ);
    }

    private double computeReward(String action, BigDecimal prevRate, BigDecimal currentRate) {
        double rateChange = currentRate.subtract(prevRate).doubleValue();
        return switch (action) {
            case "HEDGE_FULL"  -> rateChange * 2.0;
            case "HEDGE_HALF"  -> rateChange * 1.0;
            case "HOLD"        -> 0.0;
            case "UNHEDGE"     -> -rateChange * 2.0;
            default -> 0.0;
        };
    }

    private void executeHedge(String action, BigDecimal rate) {
        BigDecimal exposure = getTotalPendingExposure();
        if (exposure.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal hedgeAmount = switch (action) {
            case "HEDGE_FULL"  -> exposure;
            case "HEDGE_HALF"  -> exposure.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
            case "UNHEDGE"     -> BigDecimal.ZERO;
            default            -> null;
        };

        if (hedgeAmount != null) {
            fxPositionRepository.upsertHedgePosition("USD", "KHR", hedgeAmount, rate, Instant.now());
            log.info("Executed {} hedge: {} USD at rate {}", action, hedgeAmount, rate);
        }
    }

    private BigDecimal fetchRate(String from, String to) {
        try {
            // STAFF ENGINEER FIX: Added 3-second hard timeout for external API calls
            Map<?, ?> response = webClientBuilder.baseUrl(fxDataUrl).build()
                    .get()
                    .uri("/latest?from={from}&to={to}", from, to)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            if (response != null) {
                var rates = (Map<?, ?>) response.get("rates");
                return new BigDecimal(rates.get(to).toString());
            }
        } catch (Exception e) {
            log.warn("FX rate fetch failed due to timeout or network error: {}", e.getMessage());
        }
        return null;
    }

    private BigDecimal getTotalPendingExposure() {
        return fxPositionRepository.sumPendingExposure().orElse(BigDecimal.ZERO);
    }
}