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

/** Manages ship lifecycle via webhooks (push) and polling (fallback). */
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
                log.info("Ship {} arriving at origin, authorizing docking", e.shipId());
                yield shipClient.dock(e.shipId())
                        .doOnNext(ship -> state.updateShip(ship))
                        .then();
            }
            case ShipWebhookEvent.DockingRequest e -> {
                log.info("Ship {} arriving at destination, authorizing docking", e.shipId());
                yield shipClient.dock(e.shipId())
                        .doOnNext(ship -> state.updateShip(ship))
                        .then();
            }
            case ShipWebhookEvent.ShipDocked e -> {
                log.info("Ship {} docked, status={}", e.shipId(), e.status());
                // The ship is loading/unloading, we update it via polling
                yield Mono.empty();
            }
            case ShipWebhookEvent.ShipComplete e -> {
                log.info("Ship {} completed successfully!", e.shipId());
                state.removeShip(e.shipId());
                yield Mono.empty();
            }
        };
    }

    public Flux<Ship> startPolling(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .flatMap(tick -> pollAllActiveShips())
                .doOnError(e -> log.error("Error polling ships: {}", e.getMessage()))
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
                                    log.warn("Cannot poll ship {}: {}", shipId, e.getMessage());
                                    return Mono.empty();
                                })
                );
    }

    private void handleShipStateChange(Ship ship) {
        Ship previous = state.getShip(ship.id());
        state.updateShip(ship);

        // If the status has changed, we log and react
        if (previous != null && !previous.status().equals(ship.status())) {
            log.info("Ship {} change: {} -> {}", ship.id(), previous.status(), ship.status());
        }

        // Transitions that need our action (detected by polling)
        if (ship.needsOriginUndock()) {
            log.info("Ship {} ready to leave origin, undocking", ship.id());
            shipClient.undock(ship.id())
                    .doOnNext(state::updateShip)
                    .subscribe();
        } else if (ship.needsDestUndock()) {
            log.info("Ship {} finished unloading, undocking", ship.id());
            shipClient.undock(ship.id())
                    .doOnNext(updated -> {
                        state.updateShip(updated);
                        if (updated.isDone()) {
                            state.removeShip(updated.id());
                            log.info("Ship {} delivery complete!", updated.id());
                        }
                    })
                    .subscribe();
        } else if (ship.isDone()) {
            state.removeShip(ship.id());
        }
    }

    // Fetches all our active ships from the server and adds them to the state
    public Mono<Void> syncActiveShips() {
        return shipClient.getMyShips()
                .filter(s -> !Ship.COMPLETE.equals(s.status()))
                .doOnNext(state::trackShip)
                .then()
                .doOnSuccess(v -> log.info("Active ships synchronized: {}", state.getActiveShips().size()));
    }
}
