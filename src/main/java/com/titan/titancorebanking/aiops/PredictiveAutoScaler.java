package com.titan.titancorebanking.aiops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Modernized AIOps Auto-Scaler.
 * Migrated from WebClient.block() to RestClient. When running on Java 21,
 * this allows the @Scheduled task to yield smoothly if virtual threads are enabled
 * for scheduling, preventing thread pinning issues associated with reactive wrappers.
 */
@Service
@Slf4j
public class PredictiveAutoScaler {

    private final RestClient restClient;

    @Value("${aiops.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    @Value("${aiops.k8s.api.url:https://kubernetes.default.svc}")
    private String k8sApiUrl;

    @Value("${aiops.scale.namespace:titan-banking}")
    private String namespace;

    @Value("${aiops.scale.deployment:titan-promotions-service}")
    private String deploymentName;

    @Value("${aiops.tps.threshold:500}")
    private int tpsThreshold;

    @Value("${aiops.scale.max-replicas:20}")
    private int maxReplicas;

    private final Deque<Double> tpsHistory = new ArrayDeque<>(30);

    public PredictiveAutoScaler(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Scheduled(fixedDelay = 60_000)
    public void evaluateAndScale() {
        double currentTps = fetchCurrentTps();
        tpsHistory.addLast(currentTps);

        if (tpsHistory.size() > 30) tpsHistory.pollFirst();
        if (tpsHistory.size() < 5) return;

        double predictedTps = predict();
        int requiredReplicas = calculateReplicas(predictedTps);

        log.info("AIOps: currentTPS={:.1f}, predictedTPS={:.1f}, requiredReplicas={}",
                currentTps, predictedTps, requiredReplicas);

        if (predictedTps > tpsThreshold) {
            log.warn("  AIOps: Spike predicted! Pre-scaling {} to {} replicas", deploymentName, requiredReplicas);
            patchHpa(requiredReplicas);
        }
    }

    double predict() {
        List<Double> samples = new ArrayList<>(tpsHistory);
        int n = samples.size();
        double weightedSum = 0;
        double totalWeight = 0;

        for (int i = 0; i < n; i++) {
            double weight = (i + 1);
            weightedSum += samples.get(i) * weight;
            totalWeight += weight;
        }

        double wma = weightedSum / totalWeight;
        int hour = LocalDateTime.now().getHour();
        double seasonality = (hour >= 11 && hour <= 13) || (hour >= 17 && hour <= 19) ? 1.4 : 1.0;

        double trend = 0;
        if (n >= 5) {
            double recent = samples.subList(n - 5, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double older = samples.subList(Math.max(0, n - 10), n - 5).stream().mapToDouble(Double::doubleValue).average().orElse(recent);
            trend = (recent - older) * 2;
        }
        return (wma + trend) * seasonality;
    }

    int calculateReplicas(double predictedTps) {
        int needed = (int) Math.ceil(predictedTps / 50.0);
        return Math.min(Math.max(needed, 2), maxReplicas);
    }

    private double fetchCurrentTps() {
        try {
            String query = "sum(rate(kafka_consumer_fetch_manager_records_consumed_total{topic=\"banking.transactions.completed\"}[1m]))";
            Map response = restClient.get()
                    .uri(prometheusUrl + "/api/v1/query?query={query}", query)
                    .retrieve()
                    .body(Map.class);

            if (response != null) {
                var data = (Map<?, ?>) response.get("data");
                var result = (List<?>) data.get("result");
                if (!result.isEmpty()) {
                    var value = (List<?>) ((Map<?, ?>) result.get(0)).get("value");
                    return Double.parseDouble((String) value.get(1));
                }
            }
        } catch (Exception e) {
            log.debug("Prometheus fetch failed, using simulated TPS", e);
        }

        int hour = LocalDateTime.now().getHour();
        return (hour >= 11 && hour <= 13) ? 400 + Math.random() * 200 : 50 + Math.random() * 100;
    }

    private void patchHpa(int replicas) {
        String patchBody = String.format("{\"spec\":{\"minReplicas\":%d,\"maxReplicas\":%d}}", replicas, maxReplicas);
        try {
            restClient.patch()
                    .uri(k8sApiUrl + "/apis/autoscaling/v1/namespaces/{ns}/horizontalpodautoscalers/{name}", namespace, deploymentName)
                    .header("Content-Type", "application/merge-patch+json")
                    .body(patchBody)
                    .retrieve()
                    .toBodilessEntity();
            log.info("  HPA patched: {} replicas for {}", replicas, deploymentName);
        } catch (Exception e) {
            log.error("HPA patch failed", e);
        }
    }
}