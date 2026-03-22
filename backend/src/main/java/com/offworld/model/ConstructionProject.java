package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConstructionProject(
        String id,
        String ownerId,
        String projectType,
        String sourcePlanetId,
        String targetPlanetId,
        long fee,
        Map<String, Long> goodsConsumed,
        String status,
        long createdAt,
        Long completionAt
) {
    public boolean isDone() { return "complete".equals(status); }
}
