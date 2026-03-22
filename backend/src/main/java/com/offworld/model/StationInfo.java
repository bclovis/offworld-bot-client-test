package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigInteger;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StationInfo(
        String name,
        @JsonProperty("owner_id") String ownerId,
        Map<String, Long> inventory,
        @JsonProperty("mass_driver") MassDriver massDriver,
        @JsonProperty("docking_bays") int dockingBays,
        @JsonProperty("max_storage") BigInteger maxStorage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MassDriver(int maxChannels) {}

    public long totalStored() {
        if (inventory == null) return 0;
        return inventory.values().stream().mapToLong(Long::longValue).sum();
    }

    public long freeSpace() {
        if (maxStorage == null) return Long.MAX_VALUE;
        long max = maxStorage.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        return max - totalStored();
    }
}
