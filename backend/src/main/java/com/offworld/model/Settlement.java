package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Settlement(
        String name,
        Long population,
        Economy economy,
        @JsonProperty("founding_goods") Map<String, Long> foundingGoods
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Economy(
            Map<String, Long> supply,
            Map<String, Long> demand
    ) {}
}
