package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.ShipClient;
import com.offworld.model.Ship;
import com.offworld.model.webhook.ShipWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SHIP SERVICE TEST
 * * Purpose: Tests the bot's ability to manage its fleet of spaceships.
 * * What it checks:
 * 1. Webhook Handling: Does the bot react correctly when the server pings it (e.g., automatically authorizing a ship to dock when it arrives)?
 * 2. Active Polling: Does the bot successfully monitor its active ships on a regular interval to see if their status has changed?
 * 3. Auto-Undock: Does the bot automatically command a ship to undock and depart once it finishes loading?
 * 4. Synchronization: Can the bot download the list of all its ships at startup and correctly filter out the ones that are already finished?
 */

@ExtendWith(MockitoExtension.class)
class ShipServiceTest {

    @Mock private ShipClient shipClient;
    @Mock private AppState state;

    private ShipService shipService;

    @BeforeEach
    void setUp() {
        shipService = new ShipService(shipClient, state);
    }

   

    @Test
    @DisplayName("webhook OriginDockingRequest — authorizes docking and updates state")
    void handleWebhook_originDockingRequest_docksAndUpdates() {
        var event = new ShipWebhookEvent.OriginDockingRequest(
                "ship-001", "p1", "p2", Map.of("food", 100L));
        var dockedShip = buildShip("ship-001", Ship.LOADING);
        when(shipClient.dock("ship-001")).thenReturn(Mono.just(dockedShip));

        StepVerifier.create(shipService.handleWebhookEvent(event))
                .verifyComplete();

        verify(shipClient).dock("ship-001");
        verify(state).updateShip(dockedShip);
    }

    @Test
    @DisplayName("webhook DockingRequest — authorizes docking at destination")
    void handleWebhook_dockingRequest_docksAtDestination() {
        var event = new ShipWebhookEvent.DockingRequest(
                "ship-002", "p1", Map.of("water", 50L));
        var dockedShip = buildShip("ship-002", Ship.UNLOADING);
        when(shipClient.dock("ship-002")).thenReturn(Mono.just(dockedShip));

        StepVerifier.create(shipService.handleWebhookEvent(event))
                .verifyComplete();

        verify(shipClient).dock("ship-002");
        verify(state).updateShip(dockedShip);
    }

    @Test
    @DisplayName("webhook ShipDocked — no network call (polling takes over)")
    void handleWebhook_shipDocked_noExtraNetworkCall() {
        var event = new ShipWebhookEvent.ShipDocked("ship-003", Ship.LOADING);

        StepVerifier.create(shipService.handleWebhookEvent(event))
                .verifyComplete();

        verifyNoInteractions(shipClient);
        // state.then() is called by doOnSuccess even with empty flux — normal behavior
    }

    @Test
    @DisplayName("webhook ShipComplete — removes ship from active state")
    void handleWebhook_shipComplete_removesFromState() {
        var event = new ShipWebhookEvent.ShipComplete("ship-004");

        StepVerifier.create(shipService.handleWebhookEvent(event))
                .verifyComplete();

        verify(state).removeShip("ship-004");
        verifyNoInteractions(shipClient);
    }

    @Test
    @DisplayName("webhook — dock returns empty (internal onErrorResume), completes without crash")
    void handleWebhook_dockFails_completesGracefully() {
        var event = new ShipWebhookEvent.OriginDockingRequest(
                "ship-005", "p1", "p2", Map.of());
        when(shipClient.dock("ship-005")).thenReturn(Mono.empty());

        StepVerifier.create(shipService.handleWebhookEvent(event))
                .verifyComplete();
    }

 

    @Test
    @DisplayName("startPolling — polls active ships on each tick")
    void startPolling_pollsActiveShips() {
        var ships = new ConcurrentHashMap<String, Ship>();
        ships.put("s1", buildShip("s1", Ship.IN_TRANSIT));

        when(state.getActiveShips()).thenReturn(ships);
        when(state.getShip("s1")).thenReturn(null);
        when(shipClient.getShip("s1"))
                .thenReturn(Mono.just(buildShip("s1", Ship.IN_TRANSIT)));

        StepVerifier.create(
                shipService.startPolling(Duration.ofMillis(50)).take(1)
        )
        .expectNextCount(1)
        .verifyComplete();

        verify(shipClient, atLeastOnce()).getShip("s1");
    }

    @Test
    @DisplayName("startPolling — flux vide si aucun ship actif")
    void startPolling_emptyWhenNoActiveShips() {
        when(state.getActiveShips()).thenReturn(new ConcurrentHashMap<>());

        StepVerifier.create(
                shipService.startPolling(Duration.ofMillis(50))
                        .take(Duration.ofMillis(120))
        )
        .verifyComplete();

        verifyNoInteractions(shipClient);
    }

    @Test
    @DisplayName("startPolling — continues after network error (retry)")
    void startPolling_retriesAfterNetworkError() {
        var ships = new ConcurrentHashMap<String, Ship>();
        ships.put("s1", buildShip("s1", Ship.IN_TRANSIT));

        when(state.getActiveShips()).thenReturn(ships);
        when(state.getShip("s1")).thenReturn(null);
        // 1st call fails, 2nd succeeds
        when(shipClient.getShip("s1"))
                .thenReturn(Mono.error(new RuntimeException("timeout")))
                .thenReturn(Mono.just(buildShip("s1", Ship.IN_TRANSIT)));

        StepVerifier.create(
                shipService.startPolling(Duration.ofMillis(50)).take(1)
        )
        .expectNextCount(1)
        .verifyComplete();
    }

    @Test
    @DisplayName("startPolling — triggers undock if ship waiting at origin")
    void startPolling_triggersOriginUndock() throws InterruptedException {
        var ships = new ConcurrentHashMap<String, Ship>();
        var readyShip = buildShip("s1", Ship.AWAITING_ORIGIN_UNDOCKING_AUTH);
        ships.put("s1", readyShip);

        when(state.getActiveShips()).thenReturn(ships);
        when(state.getShip("s1")).thenReturn(readyShip);
        when(shipClient.getShip("s1")).thenReturn(Mono.just(readyShip));
        when(shipClient.undock("s1"))
                .thenReturn(Mono.just(buildShip("s1", Ship.IN_TRANSIT)));

        StepVerifier.create(
                shipService.startPolling(Duration.ofMillis(50)).take(1)
        )
        .expectNextCount(1)
        .verifyComplete();

        Thread.sleep(100); // let internal .subscribe() execute
        verify(shipClient).undock("s1");
    }

    @Test
    @DisplayName("startPolling — retire le ship si status=complete")
    void startPolling_removesCompletedShip() throws InterruptedException {
        var ships = new ConcurrentHashMap<String, Ship>();
        ships.put("s1", buildShip("s1", Ship.IN_TRANSIT));

        when(state.getActiveShips()).thenReturn(ships);
        when(state.getShip("s1")).thenReturn(buildShip("s1", Ship.IN_TRANSIT));
        when(shipClient.getShip("s1"))
                .thenReturn(Mono.just(buildShip("s1", Ship.COMPLETE)));

        StepVerifier.create(
                shipService.startPolling(Duration.ofMillis(50)).take(1)
        )
        .expectNextCount(1)
        .verifyComplete();

        Thread.sleep(100);
        verify(state).removeShip("s1");
    }

   

    @Test
    @DisplayName("syncActiveShips — enregistre seulement les ships non-complete")
    void syncActiveShips_tracksActiveShips() {
        var s1 = buildShip("s1", Ship.IN_TRANSIT);
        var s2 = buildShip("s2", Ship.LOADING);
        var s3 = buildShip("s3", Ship.COMPLETE);

        when(shipClient.getMyShips()).thenReturn(Flux.just(s1, s2, s3));

        StepVerifier.create(shipService.syncActiveShips())
                .verifyComplete();

        verify(state).trackShip(s1);
        verify(state).trackShip(s2);
        verify(state, never()).trackShip(s3);
    }

    @Test
    @DisplayName("syncActiveShips — no-op si aucun ship")
    void syncActiveShips_noopWhenNoShips() {
        when(shipClient.getMyShips()).thenReturn(Flux.empty());

        StepVerifier.create(shipService.syncActiveShips())
                .verifyComplete();

    }

  

    private Ship buildShip(String id, String status) {
        return new Ship(id, "alpha-team", "p1", "p2",
                Map.of("food", 100L), status, null, "truck-1", 50L,
                System.currentTimeMillis(), null, null);
    }
}