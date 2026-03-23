package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.GalaxyClient;
import com.offworld.client.PlayerClient;
import com.offworld.client.StationClient;
import com.offworld.config.AppConfig;
import com.offworld.model.Planet;
import com.offworld.model.StationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Galaxy exploration service.
 * Discovers systems, planets and identifies our station at startup.
 */
@Service
public class GalaxyService {

    private static final Logger log = LoggerFactory.getLogger(GalaxyService.class);

    private final GalaxyClient galaxyClient;
    private final PlayerClient playerClient;
    private final StationClient stationClient;
    private final AppConfig config;
    private final AppState state;

    public GalaxyService(GalaxyClient galaxyClient, PlayerClient playerClient,
                         StationClient stationClient, AppConfig config, AppState state) {
        this.galaxyClient = galaxyClient;
        this.playerClient = playerClient;
        this.stationClient = stationClient;
        this.config = config;
        this.state = state;
    }

    public Mono<Void> initialize() {
        return playerClient.getMyProfile(config.playerId())
                .doOnNext(p -> {
                    state.setCredits(p.credits());
                    log.info("Player: {} | Credits: {}", p.name(), p.credits());
                })
                .then(playerClient.registerWebhookUrl(config.playerId(), config.webhookUrl()))
                .doOnNext(p -> log.info("Webhook URL registered: {}", p.callbackUrl()))
                .then(scanGalaxy());
    }

    public Mono<Void> scanGalaxy() {
        log.info("Scanning galaxy...");
        return galaxyClient.getAllSystems()
                .flatMap(system ->
                        galaxyClient.getPlanets(system.name())
                                .filter(p -> {
                                    String st = p.status() != null ? p.status().status() : null;
                                    return "connected".equals(st) || "settled".equals(st);
                                })
                                .flatMap(p -> galaxyClient.getPlanet(system.name(), p.id())
                                        .doOnNext(planet -> {
                                            state.addConnectedPlanet(planet);
                                            if (planet.station() != null
                                                    && config.playerId().equals(planet.station().ownerId())) {
                                                state.setMyPlanetId(planet.id());
                                                state.setMySystemName(system.name());
                                                log.info("Our station found on: {} ({})", planet.name(), planet.id());
                                            }
                                        })
                                        .onErrorResume(e -> {
                                            log.warn("Cannot detail planet {}: {}", p.id(), e.getMessage());
                                            return Mono.empty();
                                        })
                                )
                )
                .then()
                .doOnSuccess(v -> log.info("Galaxy scanned: {} planets found, station: {}",
                        state.getConnectedPlanets().size(), state.getMyPlanetId()));
    }

    // Retourne l'inventaire actuel de notre station
    public Mono<StationInfo> getMyStationInventory() {
        if (state.getMyPlanetId() == null || state.getMySystemName() == null) {
            return Mono.error(new IllegalStateException("Station not initialized"));
        }
        return stationClient.getMyStation(state.getMySystemName(), state.getMyPlanetId());
    }
}
