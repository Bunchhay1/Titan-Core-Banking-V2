package com.titan.notifications.chaos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class ChaosSimulator {
    
    private final AtomicBoolean blackoutActive = new AtomicBoolean(false);
    private volatile long blackoutStartTime = 0;
    private static final long BLACKOUT_DURATION_MS = 30 * 60 * 1000;
    
    public void startBlackout() {
        blackoutActive.set(true);
        blackoutStartTime = System.currentTimeMillis();
        log.warn("🔥 CHAOS: Provider blackout activated for 30 minutes");
    }
    
    public void stopBlackout() {
        blackoutActive.set(false);
        log.info("✅ CHAOS: Provider blackout deactivated");
    }
    
    public boolean isBlackoutActive() {
        if (blackoutActive.get()) {
            long elapsed = System.currentTimeMillis() - blackoutStartTime;
            if (elapsed > BLACKOUT_DURATION_MS) {
                stopBlackout();
                return false;
            }
            return true;
        }
        return false;
    }
    
    public void simulateProviderFailure() {
        if (isBlackoutActive()) {
            throw new RuntimeException("CHAOS: Provider unreachable during blackout drill");
        }
    }
}
