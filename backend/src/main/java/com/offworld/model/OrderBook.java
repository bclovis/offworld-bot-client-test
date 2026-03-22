package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderBook(
        String goodName,
        List<PriceLevel> bids,
        List<PriceLevel> asks,
        Long lastTradePrice
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceLevel(long price, long totalQuantity, int orderCount) {}

    public Long bestBid() {
        return bids != null && !bids.isEmpty() ? bids.get(0).price() : null;
    }

    public Long bestAsk() {
        return asks != null && !asks.isEmpty() ? asks.get(0).price() : null;
    }
}
