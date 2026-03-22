package com.offworld.model.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

// Le serveur Rust utilise #[serde(tag = "type", rename_all = "snake_case")]
// donc on reçoit: {"type": "origin_docking_request", ...}
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ShipWebhookEvent.OriginDockingRequest.class, name = "origin_docking_request"),
    @JsonSubTypes.Type(value = ShipWebhookEvent.DockingRequest.class,       name = "docking_request"),
    @JsonSubTypes.Type(value = ShipWebhookEvent.ShipDocked.class,           name = "ship_docked"),
    @JsonSubTypes.Type(value = ShipWebhookEvent.ShipComplete.class,         name = "ship_complete"),
})
public sealed interface ShipWebhookEvent
        permits ShipWebhookEvent.OriginDockingRequest,
                ShipWebhookEvent.DockingRequest,
                ShipWebhookEvent.ShipDocked,
                ShipWebhookEvent.ShipComplete {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OriginDockingRequest(
            String shipId,
            String originPlanetId,
            String destinationPlanetId,
            Map<String, Long> cargo
    ) implements ShipWebhookEvent {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DockingRequest(
            String shipId,
            String originPlanetId,
            Map<String, Long> cargo
    ) implements ShipWebhookEvent {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipDocked(
            String shipId,
            String status
    ) implements ShipWebhookEvent {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipComplete(String shipId) implements ShipWebhookEvent {}
}
