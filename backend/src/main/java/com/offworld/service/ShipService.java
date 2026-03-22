package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.ShipClient;
import com.offworld.model.Ship;
import com.offworld.model.webhook.ShipWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Gère le lifecycle des ships via webhooks (push) et polling (fallback). */
@Service
public class ShipService {

    private static final Logger log = LoggerFactory.getLogger(ShipService.class);
    private final ShipClient shipClient;
    private final AppState state;

    public ShipService(ShipClient shipClient, AppState state) {
        this.shipClient = shipClient;
        this.state = state;
    }

    public Mono<Void> handleWebhookEvent(ShipWebhookEvent event) {
        return switch (event) {
            case ShipWebhookEvent.OriginDockingRequest e -> {
                log.info("Ship {} arrive à l'origine, autorisation docking", e.shipId());
                yield shipClient.dock(e.shipId())
                        .doOnNext(ship -> state.updateShip(ship))
                        .then();
            }
            case ShipWebhookEvent.DockingRequest e -> {
                log.info("Ship {} arrive à destination, autorisation docking", e.shipId());
                yield shipClient.dock(e.shipId())
                        .doOnNext(ship -> state.updateShip(ship))
                        .then();
            }
            case ShipWebhookEvent.ShipDocked e -> {
                log.info("Ship {} docké, statut={}", e.shipId(), e.status());
                // Le ship est en train de charger/décharger, on le met à jour via polling
                yield Mono.empty();
            }
            case ShipWebhookEvent.ShipComplete e -> {
                log.info("Ship {} terminé avec succès!", e.shipId());
                state.removeShip(e.shipId());
                yield Mono.empty();
            }
        };
    }

    public Flux<Ship> startPolling(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .flatMap(tick -> pollAllActiveShips())
                .doOnError(e -> log.error("Erreur polling ships: {}", e.getMessage()))
                .retry();
    }

    private Flux<Ship> pollAllActiveShips() {
        var ships = state.getActiveShips();
        if (ships.isEmpty()) return Flux.empty();

        return Flux.fromIterable(ships.keySet())
                .flatMap(shipId ->
                        shipClient.getShip(shipId)
                                .doOnNext(ship -> handleShipStateChange(ship))
                                .onErrorResume(e -> {
                                    log.warn("Impossible de poll le ship {}: {}", shipId, e.getMessage());
                                    return Mono.empty();
                                })
                );
    }

    private void handleShipStateChange(Ship ship) {
        Ship previous = state.getShip(ship.id());
        state.updateShip(ship);

        // Si le statut a changé, on log et on réagit
        if (previous != null && !previous.status().equals(ship.status())) {
            log.info("Ship {} changement: {} -> {}", ship.id(), previous.status(), ship.status());
        }

        // Transitions qui nécessitent notre action (détectées par polling)
        if (ship.needsOriginUndock()) {
            log.info("Ship {} prêt à fuir l'origine, undocking", ship.id());
            shipClient.undock(ship.id())
                    .doOnNext(state::updateShip)
                    .subscribe();
        } else if (ship.needsDestUndock()) {
            log.info("Ship {} a fini de décharger, undocking", ship.id());
            shipClient.undock(ship.id())
                    .doOnNext(updated -> {
                        state.updateShip(updated);
                        if (updated.isDone()) {
                            state.removeShip(updated.id());
                            log.info("Ship {} livraison complète!", updated.id());
                        }
                    })
                    .subscribe();
        } else if (ship.isDone()) {
            state.removeShip(ship.id());
        }
    }

    // Récupère tous nos ships actifs depuis le serveur et les met dans l'état
    public Mono<Void> syncActiveShips() {
        return shipClient.getMyShips()
                .filter(s -> !Ship.COMPLETE.equals(s.status()))
                .doOnNext(state::trackShip)
                .then()
                .doOnSuccess(v -> log.info("Ships actifs synchronisés: {}", state.getActiveShips().size()));
    }
}
