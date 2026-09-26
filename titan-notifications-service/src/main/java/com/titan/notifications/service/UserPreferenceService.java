package com.titan.notifications.service;

import com.titan.notifications.model.UserPreference;
import com.titan.notifications.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserPreferenceService {
    
    private final UserPreferenceRepository repository;
    
    @Cacheable(value = "userPreferences", key = "#userId")
    public UserPreference getPreferences(String userId) {
        return repository.findById(userId)
                .orElseGet(() -> createDefaultPreference(userId));
    }

    /** Persist a preference update and evict the cache so next read is fresh. */
    @CacheEvict(value = "userPreferences", key = "#pref.userId")
    public UserPreference save(UserPreference pref) {
        return repository.save(pref);
    }
    
    public boolean canSendMarketing(String userId) {
        UserPreference pref = getPreferences(userId);
        return pref.isMarketingOptIn();
    }
    
    public boolean canSendTransactionAlert(String userId) {
        UserPreference pref = getPreferences(userId);
        return pref.isTransactionAlertsEnabled();
    }
    
    public String getPreferredLocale(String userId) {
        return getPreferences(userId).getPreferredLocale();
    }
    
    private UserPreference createDefaultPreference(String userId) {
        UserPreference pref = new UserPreference();
        pref.setUserId(userId);
        pref.setMarketingOptIn(true);
        pref.setTransactionAlertsEnabled(true);
        pref.setPreferredLocale("en");
        // Leave email/smsNumber blank — don't default to placeholder values
        pref.setSmsNumber("");
        pref.setEmail("");
        return pref;
    }
}
