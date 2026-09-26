package com.titan.notifications.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@EnableCaching
@EnableRetry
@EnableScheduling
@Slf4j
public class NotificationConfig {

    /**
     * RedisTemplate — only created when Redis connection factory is available.
     * On Render free tier without Redis, this bean is simply absent.
     */
    @Bean
    @Autowired(required = false)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        if (factory == null) return null;
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * Redis-backed cache manager — used when Redis IS available.
     * Conditional on spring.cache.type != none
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    public CacheManager redisCacheManager(RedisConnectionFactory factory) {
        log.info("✅ CacheManager: Redis");
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10));
        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * In-memory fallback cache — used when Redis is NOT available (Render free tier).
     * spring.cache.type=none disables auto-config, so we provide ConcurrentMapCacheManager.
     * This keeps @Cacheable annotations working without Redis.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "none", matchIfMissing = true)
    public CacheManager simpleCacheManager() {
        log.info("⚠️  Redis not available — using in-memory ConcurrentMapCacheManager (single-instance only)");
        return new ConcurrentMapCacheManager("userPreferences", "notificationAudits");
    }
}

