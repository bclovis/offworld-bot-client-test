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
                .uri("/projects")
                .retrieve()
                .bodyToFlux(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<ConstructionProject> getProject(String projectId) {
        return webClient.get()
                .uri("/projects/{id}", projectId)
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Upgrade docking bays (to handle more ships simultaneously)
    public Mono<ConstructionProject> upgradeDockingBays(String planetId) {
        log.info("Upgrading docking bays on {}", planetId);
        return webClient.post()
                .uri("/projects")
                .bodyValue(Map.of("project_type", "upgrade_docking_bays", "planet_id", planetId))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Increase station storage capacity
    public Mono<ConstructionProject> upgradeStorage(String planetId) {
        log.info("Upgrading storage on {}", planetId);
        return webClient.post()
                .uri("/projects")
                .bodyValue(Map.of("project_type", "upgrade_storage", "planet_id", planetId))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Add a cabin to the space elevator
    public Mono<ConstructionProject> upgradeElevator(String planetId) {
        log.info("Upgrading elevator on {}", planetId);
        return webClient.post()
                .uri("/projects")
                .bodyValue(Map.of("project_type", "upgrade_elevator_cabins", "planet_id", planetId))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }

    // Build a new station on a settled planet
    public Mono<ConstructionProject> installStation(String sourcePlanetId, String targetPlanetId, String stationName) {
        log.info("Installing station on {} from {}", targetPlanetId, sourcePlanetId);
        return webClient.post()
            .uri("/projects")
                .bodyValue(Map.of(
                "project_type", "install_station",
                        "source_planet_id", sourcePlanetId,
                        "target_planet_id", targetPlanetId,
                        "station_name", stationName
                ))
                .retrieve()
                .bodyToMono(ConstructionProject.class)
                .timeout(Duration.ofSeconds(10));
    }
}
