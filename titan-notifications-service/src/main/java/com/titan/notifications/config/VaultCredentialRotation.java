package com.titan.notifications.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

@Configuration
@ConditionalOnProperty(name = "spring.cloud.vault.enabled", havingValue = "true")
@Slf4j
public class VaultCredentialRotation {
    
    private final VaultTemplate vaultTemplate;
    
    @Value("${notification.vault.apns-path:secret/apns}")
    private String apnsPath;
    
    @Value("${notification.vault.fcm-path:secret/fcm}")
    private String fcmPath;
    
    private volatile String apnsKey;
    private volatile String fcmKey;
    
    public VaultCredentialRotation(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
        refreshCredentials();
    }
    
    @Scheduled(fixedRate = 3600000)
    public void refreshCredentials() {
        try {
            VaultResponse apnsResponse = vaultTemplate.read(apnsPath);
            if (apnsResponse != null && apnsResponse.getData() != null) {
                apnsKey = (String) apnsResponse.getData().get("key");
                log.info("🔄 Rotated APNs credentials from Vault");
            }
            
            VaultResponse fcmResponse = vaultTemplate.read(fcmPath);
            if (fcmResponse != null && fcmResponse.getData() != null) {
                fcmKey = (String) fcmResponse.getData().get("key");
                log.info("🔄 Rotated FCM credentials from Vault");
            }
        } catch (Exception e) {
            log.error("❌ Failed to rotate credentials from Vault: {}", e.getMessage());
        }
    }
    
    public String getApnsKey() {
        return apnsKey;
    }
    
    public String getFcmKey() {
        return fcmKey;
    }
}
