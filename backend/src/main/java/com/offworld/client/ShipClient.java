package com.offworld.client;

import com.offworld.model.Ship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class ShipClient {

    private static final Logger log = LoggerFactory.getLogger(ShipClient.class);
    private final WebClient webClient;

    public ShipClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Flux<Ship> getMyShips() {
        return webClient.get()
                .uri("/ships")
                .retrieve()
                .bodyToFlux(Ship.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Flux<Ship> getMyShipsByStatus(String status) {
        return webClient.get()
                .uri(u -> u.path("/ships").queryParam("status", status).build())
                .retrieve()
                .bodyToFlux(Ship.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * PATTERN POLLING : get d'un ship qui met aussi à jour le statut côté serveur.
     * Si le timer de loading/unloading est expiré, le serveur passe automatiquement
     * le ship à l'état awaiting_*_auth dans la réponse.
     */
    public Mono<Ship> getShip(String shipId) {
        return webClient.get()
                .uri("/ships/{id}", shipId)
                .retrieve()
                .bodyToMono(Ship.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.warn("Erreur getShip {}: {}", shipId, e.getMessage()));
    }

    public Mono<Ship> dock(String shipId) {
        log.info("Autorisation docking ship {}", shipId);
        return webClient.put()
                .uri("/ships/{id}/dock", shipId)
                .bodyValue(Map.of("authorized", true))
                .retrieve()
                .bodyToMono(Ship.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Erreur dock ship {}: {}", shipId, e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Ship> undock(String shipId) {
        log.info("Autorisation undocking ship {}", shipId);
        return webClient.put()
                .uri("/ships/{id}/undock", shipId)
                .bodyValue(Map.of("authorized", true))
                .retrieve()
                .bodyToMono(Ship.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Erreur undock ship {}: {}", shipId, e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Ship> hireTrucking(String originPlanetId, String destPlanetId, Map<String, Long> cargo) {
        log.info("Trucking {} -> {} avec {}", originPlanetId, destPlanetId, cargo);
        var body = Map.of(
                "origin_planet_id", originPlanetId,
                "destination_planet_id", destPlanetId,
                "cargo", cargo
        );
        return webClient.post()
                .uri("/trucking")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Ship.class)
                .timeout(Duration.ofSeconds(10));
    }
}
