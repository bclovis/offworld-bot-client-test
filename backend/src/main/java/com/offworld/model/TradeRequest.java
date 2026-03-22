package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeRequest(
        String id,
        String ownerId,
        String planetId,
        String goodName,
        String direction,
        String mode,
        long ratePerTick,
        Long totalQuantity,
        long cumulativeGenerated,
        String status
) {
    public boolean isActive() { return "active".equals(status); }

    public record CreateTradeRequest(
            String planetId,
            String goodName,
            String direction,
            String mode,
            long ratePerTick,
            Long totalQuantity,
            Long targetLevel
    ) {}
}
