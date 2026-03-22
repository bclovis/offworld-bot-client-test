package com.offworld.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offworld.model.Ship;
import com.offworld.model.webhook.ShipWebhookEvent;
import com.offworld.service.ShipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * WEBHOOK CONTROLLER TEST
 * * Purpose: Tests the bot's "receptionist" that receives incoming alerts (webhooks) from the game server.
 * * What it checks:
 * 1. Message Routing: Does it correctly read the "type" of incoming messages and forward ship-related events to the ShipService?
 * 2. Resilience: Does it safely ignore unknown messages or unrelated events (like construction updates) without crashing?
 * 3. Error Handling: Does it reject malformed messages that are missing a "type" (returns a 400 Bad Request)?
 * 4. Speed (Fire-and-Forget): Does it reply immediately to the game server to prevent timeouts, even if the bot takes a while to process the actual event in the background?
 */

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    private ShipService shipService;

    private WebhookController webhookController;

    @BeforeEach
    void setUp() {
        webhookController = new WebhookController(shipService, new ObjectMapper());
    }

    // Helper pour créer un payload Map<String, Object> facilement
    private Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────
    // Routing : dispatch selon "type"
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("origin_docking_request → dispatché vers ShipService.handleWebhookEvent")
    void webhook_originDockingRequest_dispatched() {
        when(shipService.handleWebhookEvent(any())).thenReturn(Mono.<Void>empty());

        var p = payload(
                "type", "origin_docking_request",
                "ship_id", "ship-001",
                "origin_planet_id", "p1",
                "destination_planet_id", "p2",
                "cargo", Map.of("food", 100)
        );

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("ok");
        verify(shipService).handleWebhookEvent(
                argThat(e -> e instanceof ShipWebhookEvent.OriginDockingRequest));
    }

    @Test
    @DisplayName("docking_request → dispatché vers ShipService")
    void webhook_dockingRequest_dispatched() {
        when(shipService.handleWebhookEvent(any())).thenReturn(Mono.<Void>empty());

        var p = payload(
                "type", "docking_request",
                "ship_id", "ship-002",
                "origin_planet_id", "p1",
                "cargo", Map.of("water", 50)
        );

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(shipService).handleWebhookEvent(
                argThat(e -> e instanceof ShipWebhookEvent.DockingRequest));
    }

    @Test
    @DisplayName("ship_docked → dispatché vers ShipService")
    void webhook_shipDocked_dispatched() {
        when(shipService.handleWebhookEvent(any())).thenReturn(Mono.<Void>empty());

        var p = payload("type", "ship_docked", "ship_id", "ship-003", "status", Ship.LOADING);

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(shipService).handleWebhookEvent(
                argThat(e -> e instanceof ShipWebhookEvent.ShipDocked));
    }

    @Test
    @DisplayName("ship_complete → dispatché vers ShipService")
    void webhook_shipComplete_dispatched() {
        when(shipService.handleWebhookEvent(any())).thenReturn(Mono.<Void>empty());

        var p = payload("type", "ship_complete", "ship_id", "ship-004");

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(shipService).handleWebhookEvent(
                argThat(e -> e instanceof ShipWebhookEvent.ShipComplete));
    }

    // ─────────────────────────────────────────────────────────────────
    // Robustesse
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("construction_complete → 200, ShipService NON appelé")
    void webhook_constructionComplete_returns200() {
        var p = payload("type", "construction_complete", "project_id", "proj-001", "planet_id", "p5");

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(shipService);
    }

    @Test
    @DisplayName("type inconnu → 200 (résilience : on ne crashe jamais)")
    void webhook_unknownType_returns200() {
        var p = payload("type", "some_future_event", "data", "foo");

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(shipService);
    }

    @Test
    @DisplayName("payload sans champ 'type' → 400 Bad Request")
    void webhook_missingType_returns400() {
        var p = payload("ship_id", "s1");

        var response = webhookController.handleWebhook(p).block();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(shipService);
    }

    @Test
    @DisplayName("fire-and-forget : répond < 500ms même si ShipService prend 1 seconde")
    void webhook_fireAndForget_respondsImmediately() {
        // ShipService lent (1s), mais controller répond via .subscribe() sans attendre
        Mono<Void> slowMono = Mono.<Void>empty().delaySubscription(java.time.Duration.ofSeconds(1));
        when(shipService.handleWebhookEvent(any())).thenReturn(slowMono);

        var p = payload("type", "ship_complete", "ship_id", "ship-005");

        long start = System.currentTimeMillis();
        var response = webhookController.handleWebhook(p).block();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("ok");
        assertThat(elapsed).isLessThan(500); // fire-and-forget = pas d'attente
    }
}