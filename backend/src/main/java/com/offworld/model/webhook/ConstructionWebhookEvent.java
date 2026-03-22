package com.offworld.model.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConstructionWebhookEvent.ConstructionComplete.class, name = "construction_complete"),
})
public sealed interface ConstructionWebhookEvent
        permits ConstructionWebhookEvent.ConstructionComplete {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConstructionComplete(
            String projectId,
            String projectType,
            String targetPlanetId
    ) implements ConstructionWebhookEvent {}
}
