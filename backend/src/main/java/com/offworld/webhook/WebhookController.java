package com.offworld.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offworld.model.webhook.ConstructionWebhookEvent;
import com.offworld.model.webhook.ShipWebhookEvent;
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
 * PATTERN WEBHOOK : Le serveur nous envoie des POST quand des événements se produisent.
 * On doit exposer ce endpoint et y répondre rapidement (le serveur a un timeout court).
 *
 * Le serveur envoie sur la même URL les events de ships ET de construction,
 * donc on dispatch manuellement selon le champ "type".
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // Types d'events qui concernent les ships
    private static final Set<String> SHIP_EVENTS = Set.of(
            "origin_docking_request", "docking_request", "ship_docked", "ship_complete"
    );

    private final ShipService shipService;
    private final ObjectMapper objectMapper;

    public WebhookController(ShipService shipService, ObjectMapper objectMapper) {
        this.shipService = shipService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Mono<ResponseEntity<String>> handleWebhook(@RequestBody Map<String, Object> rawPayload) {
        String type = (String) rawPayload.get("type");
        log.info("Webhook reçu, type={}", type);

        if (type == null) {
            log.warn("Webhook sans champ 'type', payload={}", rawPayload);
            return Mono.just(ResponseEntity.badRequest().body("missing type"));
        }

        if (SHIP_EVENTS.contains(type)) {
            try {
                ShipWebhookEvent event = objectMapper.convertValue(rawPayload, ShipWebhookEvent.class);
                // Traitement asynchrone - le serveur a un timeout court donc on répond vite
                shipService.handleWebhookEvent(event)
                        .subscribe(
                                v -> {},
                                e -> log.error("Erreur traitement ship webhook: {}", e.getMessage())
                        );
            } catch (Exception e) {
                log.error("Impossible de parser ship webhook: {}", e.getMessage());
            }
        } else if ("construction_complete".equals(type)) {
            try {
                ConstructionWebhookEvent event = objectMapper.convertValue(rawPayload, ConstructionWebhookEvent.class);
                log.info("Construction terminée: {}", event);
                // TODO: déclencher un re-scan de la galaxie ou une action de suivi
            } catch (Exception e) {
                log.error("Impossible de parser construction webhook: {}", e.getMessage());
            }
        } else {
            log.warn("Type de webhook inconnu: {}", type);
        }

        // On répond 200 immédiatement dans tous les cas
        return Mono.just(ResponseEntity.ok("ok"));
    }
}
