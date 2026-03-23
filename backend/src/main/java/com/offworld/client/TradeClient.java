package com.offworld.client;

import com.offworld.model.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class TradeClient {

    private static final Logger log = LoggerFactory.getLogger(TradeClient.class);
    private final WebClient webClient;

    public TradeClient(WebClient webClient) {
        this.webClient = webClient;
    }

    // Creates an import or export request to generate supply/demand
    public Mono<TradeRequest> createTradeRequest(TradeRequest.CreateTradeRequest req) {
        log.info("Creating trade request: {} {} on {}", req.direction(), req.goodName(), req.planetId());
        return webClient.post()
                .uri("/trade")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(TradeRequest.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Flux<TradeRequest> getMyTradeRequests() {
        return webClient.get()
                .uri("/trade")
                .retrieve()
                .bodyToFlux(TradeRequest.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<TradeRequest> cancelTradeRequest(String requestId) {
        log.info("Annulation trade request {}", requestId);
        return webClient.delete()
                .uri("/trade/{id}", requestId)
                .retrieve()
                .bodyToMono(TradeRequest.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Impossible d'annuler trade request {}: {}", requestId, e.getMessage());
                    return Mono.empty();
                });
    }
}
