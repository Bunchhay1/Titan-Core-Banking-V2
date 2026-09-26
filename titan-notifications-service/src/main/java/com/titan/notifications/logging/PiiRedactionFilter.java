package com.titan.notifications.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.regex.Pattern;

public class PiiRedactionFilter extends Filter<ILoggingEvent> {
    
    private static final Pattern PHONE = Pattern.compile("\\+?\\d{10,15}");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern ACCOUNT = Pattern.compile("\\b\\d{10,16}\\b");
    private static final Pattern AMOUNT = Pattern.compile("\\$\\d+\\.\\d{2}");
    
    @Override
    public FilterReply decide(ILoggingEvent event) {
        return FilterReply.NEUTRAL;
    }
    
    public static String redact(String message) {
        if (message == null) return null;
        
        String redacted = message;
        redacted = PHONE.matcher(redacted).replaceAll(m -> 
            m.group().substring(0, Math.min(4, m.group().length())) + "****" + 
            m.group().substring(Math.max(m.group().length() - 2, 4)));
        redacted = EMAIL.matcher(redacted).replaceAll(m -> {
            String[] parts = m.group().split("@");
            return parts[0].charAt(0) + "***@" + parts[1];
        });
        redacted = ACCOUNT.matcher(redacted).replaceAll(m -> 
            "****" + m.group().substring(m.group().length() - 4));
        redacted = AMOUNT.matcher(redacted).replaceAll("$***.**");
        
        return redacted;
    }
}
