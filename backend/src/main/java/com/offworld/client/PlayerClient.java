package com.offworld.client;

import com.offworld.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class PlayerClient {

    private static final Logger log = LoggerFactory.getLogger(PlayerClient.class);
    private final WebClient webClient;

    public PlayerClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Player> getMyProfile(String playerId) {
        return webClient.get()
                .uri("/players/{id}", playerId)
                .retrieve()
                .bodyToMono(Player.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Enregistre notre URL de webhook sur le serveur
    // C'est indispensable pour recevoir les events ship/construction
    public Mono<Player> registerWebhookUrl(String playerId, String webhookUrl) {
        log.info("Enregistrement du webhook URL: {}", webhookUrl);
        return webClient.put()
                .uri("/players/{id}", playerId)
                .bodyValue(Map.of("callback_url", webhookUrl))
                .retrieve()
                .bodyToMono(Player.class)
                .timeout(Duration.ofSeconds(10));
    }
}
