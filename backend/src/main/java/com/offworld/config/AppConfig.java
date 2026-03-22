package com.offworld.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "offworld")
public record AppConfig(
        String serverUrl,
        String playerId,
        String apiKey,
        String webhookUrl,
        long shipPollingIntervalMs,
        long strategyIntervalMs
) {}
