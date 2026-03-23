package com.offworld.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Planet(
        String id,
        String name,
        int position,
        double distanceUa,
        PlanetType planetType,
        // The server returns either a string ("uninhabited") or a JSON object
        @JsonDeserialize(using = PlanetStatusDeserializer.class)
        PlanetStatus status,
        // Direct fields on the planet (detail endpoint only)
        Settlement settlement,
        StationInfo station,
        SpaceElevatorInfo spaceElevator
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanetType(String category, String climate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanetStatus(
            String status,
            Settlement settlement,
            StationInfo station,
            SpaceElevatorInfo spaceElevator
    ) {
        public boolean isConnected() {
            return "connected".equals(status);
        }
    }

    // Custom deserializer: handles the case where status is a String or an object
    public static class PlanetStatusDeserializer extends StdDeserializer<PlanetStatus> {
        public PlanetStatusDeserializer() { super(PlanetStatus.class); }

        @Override
        public PlanetStatus deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                // Ex: "uninhabited" → we create a PlanetStatus with just the status name
                return new PlanetStatus(p.getText(), null, null, null);
            }
            // It's a JSON object, we parse it manually to avoid infinite recursion
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            ObjectNode node = mapper.readTree(p);
            String status = node.has("status") ? node.get("status").asText() : null;
            Settlement settlement = node.has("settlement") ? mapper.treeToValue(node.get("settlement"), Settlement.class) : null;
            StationInfo station = node.has("station") ? mapper.treeToValue(node.get("station"), StationInfo.class) : null;
            SpaceElevatorInfo spaceElevator = node.has("space_elevator") ? mapper.treeToValue(node.get("space_elevator"), SpaceElevatorInfo.class) : null;
            return new PlanetStatus(status, settlement, station, spaceElevator);
        }
    }
}
