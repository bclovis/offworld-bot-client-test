package com.offworld.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offworld.model.webhook.ConstructionWebhookEvent;
import com.offworld.model.webhook.ShipWebhookEvent;
import com.offworld.service.ConstructionService;
import com.offworld.service.ShipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * WEBHOOK PATTERN: The server sends us POST requests when events occur.
 * We must expose this endpoint and respond quickly (server has a short timeout).
 *
 * The server sends ship AND construction events on the same URL,
 * so we manually dispatch based on the "type" field.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // Types of events that concern ships
    private static final Set<String> SHIP_EVENTS = Set.of(
            "origin_docking_request", "docking_request", "ship_docked", "ship_complete"
    );

    private final ShipService shipService;
    private final ConstructionService constructionService;
    private final ObjectMapper objectMapper;

    public WebhookController(ShipService shipService, ConstructionService constructionService, ObjectMapper objectMapper) {
        this.shipService = shipService;
        this.constructionService = constructionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Mono<ResponseEntity<String>> handleWebhook(@RequestBody Map<String, Object> rawPayload) {
        String type = (String) rawPayload.get("type");
        log.info("Webhook received, type={}", type);

        if (type == null) {
            log.warn("Webhook without 'type' field, payload={}", rawPayload);
            return Mono.just(ResponseEntity.badRequest().body("missing type"));
        }

        if (SHIP_EVENTS.contains(type)) {
            try {
                ShipWebhookEvent event = objectMapper.convertValue(rawPayload, ShipWebhookEvent.class);
                // Async processing - server has short timeout so we respond quickly
                shipService.handleWebhookEvent(event)
                        .subscribe(
                                v -> {},
                                e -> log.error("Error processing ship webhook: {}", e.getMessage())
                        );
            } catch (Exception e) {
                log.error("Cannot parse ship webhook: {}", e.getMessage());
            }
        } else if ("construction_complete".equals(type)) {
            try {
                ConstructionWebhookEvent event = objectMapper.convertValue(rawPayload, ConstructionWebhookEvent.class);
                constructionService.onConstructionComplete(event)
                        .subscribe(
                                v -> {},
                                e -> log.error("Error processing construction webhook: {}", e.getMessage())
                        );
            } catch (Exception e) {
                log.error("Cannot parse construction webhook: {}", e.getMessage());
            }
        } else {
            log.warn("Unknown webhook type: {}", type);
        }

        // We respond 200 immediately in all cases
        return Mono.just(ResponseEntity.ok("ok"));
    }
}
