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
        // Le serveur renvoie soit une string ("uninhabited") soit un objet JSON
        @JsonDeserialize(using = PlanetStatusDeserializer.class)
        PlanetStatus status,
        // Champs directs sur la planète (endpoint détail uniquement)
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

    // Désérialiseur custom : gère le cas où status est une String ou un objet
    public static class PlanetStatusDeserializer extends StdDeserializer<PlanetStatus> {
        public PlanetStatusDeserializer() { super(PlanetStatus.class); }

        @Override
        public PlanetStatus deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                // Ex: "uninhabited" → on crée un PlanetStatus avec juste le nom du statut
                return new PlanetStatus(p.getText(), null, null, null);
            }
            // C'est un objet JSON, on le parse manuellement pour éviter la récursion infinie
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
