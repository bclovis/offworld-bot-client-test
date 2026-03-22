package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketOrder(
        String id,
        String playerId,
        String goodName,
        String side,
        String orderType,
        Long price,
        long quantity,
        long filledQuantity,
        String status,
        String stationPlanetId,
        long createdAt
) {
    public boolean isActive() {
        return "open".equals(status) || "partially_filled".equals(status);
    }

    // DTO pour placer un ordre
    public record PlaceOrderRequest(
            String goodName,
            String side,
            String orderType,
            Long price,
            long quantity,
            String stationPlanetId
    ) {
        public static PlaceOrderRequest limitBuy(String good, long price, long qty, String planet) {
            return new PlaceOrderRequest(good, "buy", "limit", price, qty, planet);
        }

        public static PlaceOrderRequest limitSell(String good, long price, long qty, String planet) {
            return new PlaceOrderRequest(good, "sell", "limit", price, qty, planet);
        }

        public static PlaceOrderRequest marketBuy(String good, long qty, String planet) {
            return new PlaceOrderRequest(good, "buy", "market", null, qty, planet);
        }
    }
}
