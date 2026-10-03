package com.seatbooking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected wherever "now" matters so tests can pin time (token expiry, hold expiry). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
