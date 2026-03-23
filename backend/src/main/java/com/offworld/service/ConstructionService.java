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
 * Gère le cycle de vie des projets de construction :
 * - Sync au démarrage
 * - Polling périodique pour détecter les completions
 * - Réaction aux webhooks construction_complete
 *
 * PATTERN RÉACTIF : Flux.interval pour le polling + flatMap non-bloquant.
 * Quand un projet est terminé → re-scan galaxie pour intégrer la nouvelle infra.
 */
@Service
public class ConstructionService {

    private static final Logger log = LoggerFactory.getLogger(ConstructionService.class);

    /**
     * On ne lance un upgrade que si on a au moins ce montant de crédits en réserve.
     * Évite de dépenser tout l'argent avant le trading.
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
     * Sync des projets actifs dans AppState au démarrage.
     * Si aucun projet actif, tente immédiatement un premier upgrade.
     */
    public Mono<Void> syncProjects() {
        return constructionClient.getMyProjects()
                .filter(p -> !p.isDone())
                .collectList()
                .flatMap(projects -> {
                    state.putConstructionProjects(projects);
                    log.info("[CONSTRUCTION] {} projet(s) actif(s) au démarrage", projects.size());
                    if (projects.isEmpty()) {
                        return tryTriggerUpgrade();
                    }
                    return Mono.<Void>empty();
                });
    }

    /**
     * Boucle de polling périodique.
     * Compare l'état précédent : si un projet passe à 'complete', déclenche un re-scan galaxie.
     * Si plus aucun projet actif et assez de crédits, tente un upgrade automatique.
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
                                    // IDs connus avant la mise à jour
                                    List<String> knownIds = state.getConstructionProjectsList()
                                            .stream()
                                            .map(ConstructionProject::id)
                                            .toList();

                                    List<ConstructionProject> active = latestProjects.stream()
                                            .filter(p -> !p.isDone())
                                            .toList();

                                    // Met à jour le cache avec les projets encore actifs
                                    state.putConstructionProjects(active);

                                    boolean newlyCompleted = latestProjects.stream()
                                            .anyMatch(p -> p.isDone() && knownIds.contains(p.id()));

                                    if (newlyCompleted) {
                                        log.info("[CONSTRUCTION] Projet complété détecté (polling) — re-scan galaxie");
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
     * Ordre de priorité : docking_bays → storage → elevator.
     * Chaque appel est fire-and-forget : si le serveur répond 4xx (pas assez de
     * goods/crédits, upgrade déjà max…), on passe silencieusement au suivant.
     */
    public Mono<Void> tryTriggerUpgrade() {
        String planetId = state.getMyPlanetId();
        if (planetId == null) {
            return Mono.empty();
        }
        if (state.getCredits() < MIN_CREDITS_TO_BUILD) {
            log.debug("[CONSTRUCTION] Pas assez de crédits pour builder ({} < {})",
                    state.getCredits(), MIN_CREDITS_TO_BUILD);
            return Mono.empty();
        }

        return constructionClient.upgradeDockingBays(planetId)
                .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade docking_bays lancé → projet {}", p.id()))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.info("[CONSTRUCTION] docking_bays refusé ({}), essai storage…", e.getStatusCode());
                    return constructionClient.upgradeStorage(planetId)
                            .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade storage lancé → projet {}", p.id()))
                            .onErrorResume(WebClientResponseException.class, e2 -> {
                                log.info("[CONSTRUCTION] storage refusé ({}), essai elevator…", e2.getStatusCode());
                                return constructionClient.upgradeElevator(planetId)
                                        .doOnNext(p -> log.info("[CONSTRUCTION] Upgrade elevator lancé → projet {}", p.id()))
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
     * Appelé par le WebhookController quand l'événement construction_complete arrive.
     * Retire le projet du cache et déclenche immédiatement un re-scan galaxie.
     *
     * PATTERN : Webhook-driven reaction (plus réactif que le polling seul).
     */
    public Mono<Void> onConstructionComplete(ConstructionWebhookEvent event) {
        if (event instanceof ConstructionWebhookEvent.ConstructionComplete e) {
            log.info("[CONSTRUCTION] Webhook: projet {} ({}) terminé sur planète {}",
                    e.projectId(), e.projectType(), e.targetPlanetId());
            state.removeConstructionProject(e.projectId());
        }
        return galaxyService.scanGalaxy()
                .then(syncProjects());
    }
}
