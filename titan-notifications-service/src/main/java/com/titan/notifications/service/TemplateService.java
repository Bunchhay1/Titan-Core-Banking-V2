package com.titan.notifications.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TemplateService {
    
    private final Configuration freemarkerConfig;
    
    public String renderSms(String templateName, Map<String, Object> data, String locale) {
        return render("sms/" + templateName + ".ftl", data, locale);
    }
    
    public String renderEmail(String templateName, Map<String, Object> data, String locale) {
        return render("email/" + templateName + ".ftl", data, locale);
    }
    
    private String render(String templatePath, Map<String, Object> data, String localeStr) {
        try {
            Template template = freemarkerConfig.getTemplate(templatePath, new Locale(localeStr));
            StringWriter writer = new StringWriter();
            template.process(data, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("❌ Template rendering failed: {}", e.getMessage());
            throw new RuntimeException("Template error", e);
        }
    }
}
