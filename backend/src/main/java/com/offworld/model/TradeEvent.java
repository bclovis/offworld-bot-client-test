package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Event received via SSE stream GET /market/trades
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeEvent(
        String id,
        String goodName,
        long price,
        long quantity,
        String buyerId,
        String sellerId,
        String buyerStation,
        String sellerStation,
        long timestamp
) {}
