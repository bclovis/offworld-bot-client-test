package com.offworld.client;

import com.offworld.model.ConstructionProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class ConstructionClient {

    private static final Logger log = LoggerFactory.getLogger(ConstructionClient.class);
    private final WebClient webClient;

    public ConstructionClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Flux<ConstructionProject> getMyProjects() {
        return webClient.get()
                .uri("/construction")
                .retrieve()
                .bodyToFlux(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<ConstructionProject> getProject(String projectId) {
        return webClient.get()
                .uri("/construction/{id}", projectId)
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Améliorer les baies de docking (pour gérer plus de ships simultanément)
    public Mono<ConstructionProject> upgradeDockingBays(String planetId) {
        log.info("Upgrade docking bays sur {}", planetId);
        return webClient.post()
                .uri("/construction/upgrade-station")
                .bodyValue(Map.of("planet_id", planetId, "upgrade_type", "docking_bays"))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Construire une nouvelle station sur une planète settlée
    public Mono<ConstructionProject> installStation(String sourcePlanetId, String targetPlanetId, String stationName) {
        log.info("Installation station sur {} depuis {}", targetPlanetId, sourcePlanetId);
        return webClient.post()
                .uri("/construction/install-station")
                .bodyValue(Map.of(
                        "source_planet_id", sourcePlanetId,
                        "target_planet_id", targetPlanetId,
                        "station_name", stationName
                ))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }
}
