package com.offworld.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    // We inject the auto-configured WebClient.Builder from Spring Boot to benefit from
    // the SNAKE_CASE naming strategy defined in application.yml
    @Bean
    public WebClient webClient(WebClient.Builder builder, AppConfig config) {
        return builder
                .baseUrl(config.serverUrl())
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
