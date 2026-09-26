package com.titan.notifications.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class SecureLogAppender extends AppenderBase<ILoggingEvent> {
    
    @Override
    protected void append(ILoggingEvent event) {
        String original = event.getFormattedMessage();
        String redacted = PiiRedactionFilter.redact(original);
        
        System.out.println(String.format("%s [%s] %s - %s",
            event.getTimeStamp(),
            event.getLevel(),
            event.getLoggerName(),
            redacted
        ));
    }
}
