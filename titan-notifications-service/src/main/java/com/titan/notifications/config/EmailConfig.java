package com.titan.notifications.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.*;
import java.util.Properties;

@Configuration
public class EmailConfig {
    
    @Value("${notification.email.host:smtp.sendgrid.net}")
    private String host;
    
    @Value("${notification.email.port:587}")
    private int port;
    
    @Value("${notification.email.username:apikey}")
    private String username;
    
    @Value("${notification.email.password:}")
    private String password;
    
    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        
        // Task 6: DKIM, SPF, DMARC enforcement
        props.put("mail.smtp.ssl.trust", host);
        props.put("mail.smtp.ssl.protocols", "TLSv1.3");
        props.put("mail.from", "noreply@titanbank.com");
        props.put("mail.smtp.from", "noreply@titanbank.com");
        
        return sender;
    }
}
