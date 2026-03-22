package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StarSystem(
        String name,
        Coordinates coordinates,
        String starType,
        List<Planet> planets
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coordinates(double x, double y, double z) {}
}
