package com.offworld.client;

import com.offworld.model.Planet;
import com.offworld.model.StarSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Client pour les endpoints galaxy : systèmes, planètes, settlements.
 * Toutes les requêtes sont non-bloquantes grâce au WebClient.
 */
@Component
public class GalaxyClient {

    private static final Logger log = LoggerFactory.getLogger(GalaxyClient.class);
    private final WebClient webClient;

    public GalaxyClient(WebClient webClient) {
        this.webClient = webClient;
    }

    // Récupère tous les systèmes stellaires de la galaxie
    public Flux<StarSystem> getAllSystems() {
        return webClient.get()
                .uri("/systems")
                .retrieve()
                .bodyToFlux(StarSystem.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.error("Erreur getAllSystems: {}", e.getMessage(), e));
    }

    // On peut filtrer par type d'étoile si besoin
    public Flux<StarSystem> getSystemsByType(String starType) {
        return webClient.get()
                .uri(u -> u.path("/systems").queryParam("star_type", starType).build())
                .retrieve()
                .bodyToFlux(StarSystem.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<StarSystem> getSystem(String name) {
        return webClient.get()
                .uri("/systems/{name}", name)
                .retrieve()
                .bodyToMono(StarSystem.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Flux<Planet> getPlanets(String systemName) {
        return webClient.get()
                .uri("/systems/{system}/planets", systemName)
                .retrieve()
                .bodyToFlux(Planet.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<Planet> getPlanet(String systemName, String planetId) {
        return webClient.get()
                .uri("/systems/{system}/planets/{planet}", systemName, planetId)
                .retrieve()
                .bodyToMono(Planet.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Liste les planètes avec settlement dans un système (exclut les non-habitées)
    public Flux<Planet> getSettledPlanets(String systemName) {
        return webClient.get()
                .uri("/settlements/{system}", systemName)
                .retrieve()
                .bodyToFlux(Planet.class)
                .timeout(Duration.ofSeconds(10));
    }
}
