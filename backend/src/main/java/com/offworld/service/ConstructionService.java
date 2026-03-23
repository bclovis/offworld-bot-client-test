package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.ConstructionClient;
import com.offworld.model.ConstructionProject;
import com.offworld.model.webhook.ConstructionWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Manages construction project lifecycle:
 * - Sync at startup
 * - Periodic polling to detect completions
 * - Webhook reactions on construction_complete
 *
 * REACTIVE PATTERN: Flux.interval for polling + non-blocking flatMap.
 * When a project is complete → re-scan galaxy to integrate new infrastructure.
 */
@Service
public class ConstructionService {

    private static final Logger log = LoggerFactory.getLogger(ConstructionService.class);

    /**
     * We only launch an upgrade if we have at least this amount of credits in reserve.
     * Avoids spending all money before trading.
     */
    private static final long MIN_CREDITS_TO_BUILD = 5_000L;

    private final ConstructionClient constructionClient;
    private final AppState state;
    private final GalaxyService galaxyService;

    public ConstructionService(ConstructionClient constructionClient,
                               AppState state,
                               GalaxyService galaxyService) {
        this.constructionClient = constructionClient;
        this.state = state;
        this.galaxyService = galaxyService;
    }

    /**
     * Sync active projects into AppState at startup.
     * If no active project, immediately attempts first upgrade.
     */
    public Mono<Void> syncProjects() {
        return constructionClient.getMyProjects()
                .filter(p -> !p.isDone())
                .collectList()
                .flatMap(projects -> {
                    state.putConstructionProjects(projects);
                    log.info("[CONSTRUCTION] {} project(s) active at startup", projects.size());
                    if (projects.isEmpty()) {
                        return tryTriggerUpgrade();
                    }
                    return Mono.<Void>empty();
                });
    }

    /**
     * Periodic polling loop.
     * Compare previous state: if a project moves to 'complete', triggers galaxy re-scan.
     * If no more active projects and enough credits, attempts automatic upgrade.
     *
     * PATTERN : Flux.interval + flatMap non-bloquant + onErrorResume par tick.
     */
    public Flux<Void> startPolling(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .flatMap(tick ->
                        constructionClient.getMyProjects()
                                .collectList()
                                .flatMap(latestProjects -> {
                                    // Known IDs before update
                                    List<String> knownIds = state.getConstructionProjectsList()
                                            .stream()
                                            .map(ConstructionProject::id)
                                            .toList();

                                    List<ConstructionProject> active = latestProjects.stream()
                                            .filter(p -> !p.isDone())
                                            .toList();

                                    // Update cache with still-active projects
                                    state.putConstructionProjects(active);

                                    boolean newlyCompleted = latestProjects.stream()
                                            .anyMatch(p -> p.isDone() && knownIds.contains(p.id()));

                                    if (newlyCompleted) {
                                        log.info("[CONSTRUCTION] Project completed detected (polling) — galaxy re-scan");
                                        return galaxyService.scanGalaxy();
                                    }

                                    // Aucun projet en cours → tente un upgrade
                                    if (active.isEmpty()) {
                                        return tryTriggerUpgrade();
                                    }

                                    return Mono.<Void>empty();
                                })
                                .onErrorResume(e -> {
                                    log.warn("[CONSTRUCTION] Erreur tick {}: {}", tick, e.getMessage());
                                    return Mono.empty();
                                })
                );
    }

    /**
     * Tente de lancer un upgrade sur notre station.
     * Priority order: docking_bays → storage → elevator.
     * Each call is fire-and-forget: if server responds 4xx (not enough
     * goods/credits, max upgrade already...), silently move to next.
     */
    public Mono<Void> tryTriggerUpgrade() {
        String planetId = state.getMyPlanetId();
        if (planetId == null) {
            return Mono.empty();
        }
        if (state.getCredits() < MIN_CREDITS_TO_BUILD) {
            log.debug("[CONSTRUCTION] Not enough credits to build ({} < {})",
                    state.getCredits(), MIN_CREDITS_TO_BUILD);
            return Mono.empty();
        }

        return constructionClient.upgradeDockingBays(planetId)
                .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade docking_bays started → project {}", p.id()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.info("[CONSTRUCTION] docking_bays rejected ({}), trying storage...", e.getStatusCode());
                    return constructionClient.upgradeStorage(planetId)
                            .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade storage started → project {}", p.id()))
                            .onErrorResume(WebClientResponseException.class, e2 -> {
                                log.info("[CONSTRUCTION] storage rejected ({}), trying elevator...", e2.getStatusCode());
                                return constructionClient.upgradeElevator(planetId)
                                        .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade elevator started → project {}", p.id()))
                                        .onErrorResume(WebClientResponseException.class, e3 -> {
                                            log.info("[CONSTRUCTION] Aucun upgrade disponible pour l'instant ({})", e3.getStatusCode());
                                            return Mono.empty();
                                        });
                            });
                })
                .flatMap(project -> {
                    state.putConstructionProjects(List.of(project));
                    return Mono.<Void>empty();
                })
                .onErrorResume(e -> {
                    log.warn("[CONSTRUCTION] Erreur inattendue lors de tryTriggerUpgrade: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Called by WebhookController when construction_complete event arrives.
     * Removes project from cache and immediately triggers galaxy re-scan.
     *
     * PATTERN: Webhook-driven reaction (more reactive than polling alone).
     */
    public Mono<Void> onConstructionComplete(ConstructionWebhookEvent event) {
        if (event instanceof ConstructionWebhookEvent.ConstructionComplete e) {
            log.info("[CONSTRUCTION] Webhook: project {} ({}) completed on planet {}",
                    e.projectId(), e.projectType(), e.targetPlanetId());
            state.removeConstructionProject(e.projectId());
        }
        return galaxyService.scanGalaxy()
                .then(syncProjects());
    }
}
