package com.seatbooking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Settings that keep the service answering correctly when traffic exceeds what one instance can serve. */
@Configuration
@EnableConfigurationProperties({LoadSheddingProperties.class, CacheProperties.class})
public class ResilienceConfig {
}
