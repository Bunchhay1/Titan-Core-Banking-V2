package com.titan.promotions.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReferralGraphService {
    private final UserGraphRepository userGraphRepository;
    private static final BigDecimal[] TIER_PERCENTAGES = {
        new BigDecimal("0.05"), // Level 1: 5%
        new BigDecimal("0.03"), // Level 2: 3%
        new BigDecimal("0.02"), // Level 3: 2%
        new BigDecimal("0.01")  // Level 4+: 1%
    };

    /**
     * Calculate referral rewards using Neo4j.
     *
     * REQUIRES_NEW ensures this method runs in its own transaction, completely
     * isolated from the caller's Postgres transaction. If Neo4j is unavailable
     * (as in local/dev stacks), the exception is caught here and an empty map
     * is returned — the caller's transaction is never marked rollback-only.
     */
    @Transactional(value = "transactionManager", propagation = Propagation.REQUIRES_NEW)
    public Map<Long, BigDecimal> calculateReferralRewards(Long accountId, BigDecimal transactionAmount) {
        Map<Long, BigDecimal> rewards = new HashMap<>();
        List<UserNode> ancestors;
        try {
            ancestors = userGraphRepository.findAncestorChain(accountId);
        } catch (Exception e) {
            // Neo4j is optional in local/dev stacks — log and skip gracefully so the
            // main Postgres transaction (deposit bonus, cashback, etc.) is NOT rolled back.
            log.warn("[REFERRAL] Neo4j unavailable — skipping referral reward calculation for account {}: {}",
                    accountId, e.getMessage());
            return rewards; // empty map, no referral rewards
        }

        for (int i = 0; i < ancestors.size() && i < 10; i++) {
            BigDecimal percentage = i < TIER_PERCENTAGES.length ? 
                TIER_PERCENTAGES[i] : TIER_PERCENTAGES[TIER_PERCENTAGES.length - 1];
            BigDecimal reward = transactionAmount.multiply(percentage);
            rewards.put(ancestors.get(i).getAccountId(), reward);
        }
        
        log.info("Calculated {} referral rewards for account {}", rewards.size(), accountId);
        return rewards;
    }
    
    public void addReferral(Long referrerId, Long referredAccountId) {
        UserNode referrer = userGraphRepository.findByAccountId(referrerId)
            .orElseGet(() -> {
                UserNode node = new UserNode();
                node.setAccountId(referrerId);
                return userGraphRepository.save(node);
            });
        
        UserNode referred = new UserNode();
        referred.setAccountId(referredAccountId);
        referred = userGraphRepository.save(referred);
        
        referrer.getReferrals().add(referred);
        userGraphRepository.save(referrer);
        log.info("Added referral: {} -> {}", referrerId, referredAccountId);
    }
}
