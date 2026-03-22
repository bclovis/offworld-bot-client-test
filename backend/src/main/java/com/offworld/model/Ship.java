package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

// Un vaisseau de transport (trucking ou trade)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ship(
        String id,
        String ownerId,
        String originPlanetId,
        String destinationPlanetId,
        Map<String, Long> cargo,
        String status,
        String tradeId,
        String truckingId,
        Long fee,
        long createdAt,
        Long arrivalAt,
        Long operationCompleteAt
) {
    // Les statuts possibles côté serveur (snake_case)
    public static final String IN_TRANSIT_TO_ORIGIN        = "in_transit_to_origin";
    public static final String AWAITING_ORIGIN_DOCKING_AUTH = "awaiting_origin_docking_auth";
    public static final String LOADING                     = "loading";
    public static final String AWAITING_ORIGIN_UNDOCKING_AUTH = "awaiting_origin_undocking_auth";
    public static final String IN_TRANSIT                  = "in_transit";
    public static final String AWAITING_DOCKING_AUTH       = "awaiting_docking_auth";
    public static final String UNLOADING                   = "unloading";
    public static final String AWAITING_UNDOCKING_AUTH     = "awaiting_undocking_auth";
    public static final String COMPLETE                    = "complete";

    public boolean needsOriginDock() {
        return AWAITING_ORIGIN_DOCKING_AUTH.equals(status);
    }

    public boolean needsOriginUndock() {
        return AWAITING_ORIGIN_UNDOCKING_AUTH.equals(status);
    }

    public boolean needsDestDock() {
        return AWAITING_DOCKING_AUTH.equals(status);
    }

    public boolean needsDestUndock() {
        return AWAITING_UNDOCKING_AUTH.equals(status);
    }

    public boolean isDone() {
        return COMPLETE.equals(status);
    }
}
