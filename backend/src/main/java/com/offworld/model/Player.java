package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Player(
        String id,
        String name,
        long credits,
        String apiKey,
        String callbackUrl,
        String pulsarBiscuit
) {}
