package com.titan.titancorebanking.dto.request;

// យើងប្រើ Record ដើម្បីវេចខ្ចប់ទិន្នន័យផ្ញើទៅ Golang (JSON: { "userId": "...", "message": "..." })
public record NotificationRequest(String userId, String message) {}