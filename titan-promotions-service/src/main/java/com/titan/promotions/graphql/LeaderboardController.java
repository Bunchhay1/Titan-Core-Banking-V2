package com.titan.promotions.graphql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
@Slf4j
@RequiredArgsConstructor
public class LeaderboardController {
    private final Map<Long, Sinks.Many<LeaderboardEntry>> campaignSinks = new ConcurrentHashMap<>();
    
    @QueryMapping
    public List<LeaderboardEntry> leaderboard(@Argument Long campaignId, @Argument Integer limit) {
        // Stub: Query from Redis sorted set
        log.info("Fetching leaderboard for campaign {} limit {}", campaignId, limit);
        return List.of();
    }
    
    @SubscriptionMapping
    public Flux<LeaderboardEntry> leaderboardUpdates(@Argument Long campaignId) {
        Sinks.Many<LeaderboardEntry> sink = campaignSinks.computeIfAbsent(
            campaignId, 
            k -> Sinks.many().multicast().onBackpressureBuffer()
        );
        log.info("Client subscribed to leaderboard updates for campaign {}", campaignId);
        return sink.asFlux();
    }
    
    public void emitLeaderboardUpdate(LeaderboardEntry entry) {
        Sinks.Many<LeaderboardEntry> sink = campaignSinks.get(entry.getCampaignId());
        if (sink != null) {
            sink.tryEmitNext(entry);
            log.debug("Emitted leaderboard update: rank {} for account {}", entry.getRank(), entry.getAccountId());
        }
    }
}
