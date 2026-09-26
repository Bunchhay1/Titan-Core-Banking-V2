package com.titan.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;

@Configuration
public class FreeMarkerConfig {
    
    @Bean
    public FreeMarkerConfigurationFactoryBean freemarkerConfiguration() {
        FreeMarkerConfigurationFactoryBean bean = new FreeMarkerConfigurationFactoryBean();
        bean.setTemplateLoaderPath("classpath:/templates/");
        bean.setDefaultEncoding("UTF-8");
        return bean;
    }
    
    @Bean
    public freemarker.template.Configuration freemarkerConfig(FreeMarkerConfigurationFactoryBean factory) throws Exception {
        return factory.createConfiguration();
    }
}
