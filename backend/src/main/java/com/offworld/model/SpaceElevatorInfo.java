package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpaceElevatorInfo(
        Warehouse warehouse,
        Config config,
        List<Cabin> cabins
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warehouse(String ownerId, Map<String, Long> inventory) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(
            int cabinCount,
            long cabinCapacity,
            int transferDurationSecs,
            double failureRate,
            int repairDurationSecs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cabin(int id, String state, Integer availableInSecs) {
        public boolean isAvailable() { return "available".equals(state); }
    }

    public boolean hasAvailableCabin() {
        return cabins != null && cabins.stream().anyMatch(Cabin::isAvailable);
    }
}
