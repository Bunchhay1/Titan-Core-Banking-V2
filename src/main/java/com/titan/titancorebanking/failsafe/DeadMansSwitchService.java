package com.titan.titancorebanking.failsafe;

import org.springframework.stereotype.Service;

@Service
public class DeadMansSwitchService {
    public boolean isSystemLocked() { return false; }
    public boolean isLockdownActive() { return false; }
    public void recordHeartbeat() {}
}
