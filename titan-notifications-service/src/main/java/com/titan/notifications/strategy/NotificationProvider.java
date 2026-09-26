package com.titan.notifications.strategy;

public interface NotificationProvider {
    void send(String recipient, String message);
    String getProviderName();
}
