package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.GalaxyClient;
import com.offworld.client.PlayerClient;
import com.offworld.client.StationClient;
import com.offworld.config.AppConfig;
import com.offworld.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GALAXY SERVICE TEST
 * * Purpose: Tests the bot's ability to discover and interact with the game's universe.
 * * What it checks:
 * 1. Can the bot scan multiple star systems and find connected planets?
 * 2. Can the bot correctly identify which planet contains our own space station?
 * 3. Does the bot keep working (using reactive error handling) even if one planet's API crashes?
 * 4. Does the initialization sequence work perfectly (fetch profile -> register webhook -> scan galaxy)?
 */

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GalaxyServiceTest {

    @Mock private GalaxyClient galaxyClient;
    @Mock private PlayerClient playerClient;
    @Mock private StationClient stationClient;
    @Mock private AppConfig config;
    @Mock private AppState state;

    private GalaxyService galaxyService;

    @BeforeEach
    void setUp() {
        lenient().when(config.playerId()).thenReturn("alpha-team");
        lenient().when(config.webhookUrl()).thenReturn("http://localhost:8081/webhooks");
        galaxyService = new GalaxyService(galaxyClient, playerClient, stationClient, config, state);
    }

    @Test
    @DisplayName("scanGalaxy — découvre et stocke les planètes connectées")
    void scanGalaxy_storeConnectedPlanets() {
        var system = new StarSystem("Sol", new StarSystem.Coordinates(0, 0, 0), "G", List.of());
        var connectedStatus   = new Planet.PlanetStatus("connected", null, null, null);
        var uninhabitedStatus = new Planet.PlanetStatus("uninhabited", null, null, null);
        var planetConnected   = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, null, null);
        var planetUninhabited = new Planet("p2", "Mars",  4, 1.5, null, uninhabitedStatus, null, null, null);
        var stationOther = new StationInfo("Other", "other-player", Map.of(), null, 2, BigInteger.valueOf(10000));
        var planetDetail = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, stationOther, null);

        when(galaxyClient.getAllSystems()).thenReturn(Flux.just(system));
        when(galaxyClient.getPlanets("Sol")).thenReturn(Flux.just(planetConnected, planetUninhabited));
        when(galaxyClient.getPlanet("Sol", "p1")).thenReturn(Mono.just(planetDetail));

        StepVerifier.create(galaxyService.scanGalaxy()).verifyComplete();

        verify(state).addConnectedPlanet(planetDetail);
        verify(state, never()).addConnectedPlanet(planetUninhabited);
        verify(state, never()).setMyPlanetId(any());
    }

    @Test
    @DisplayName("scanGalaxy — détecte notre station quand ownerId correspond")
    void scanGalaxy_detectsOurStation() {
        var system = new StarSystem("Sol", new StarSystem.Coordinates(0, 0, 0), "G", List.of());
        var connectedStatus = new Planet.PlanetStatus("connected", null, null, null);
        var planet = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, null, null);
        var ourStation = new StationInfo("Alpha Base", "alpha-team", Map.of(), null, 4, BigInteger.valueOf(50000));
        var planetDetail = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, ourStation, null);

        when(galaxyClient.getAllSystems()).thenReturn(Flux.just(system));
        when(galaxyClient.getPlanets("Sol")).thenReturn(Flux.just(planet));
        when(galaxyClient.getPlanet("Sol", "p1")).thenReturn(Mono.just(planetDetail));

        StepVerifier.create(galaxyService.scanGalaxy()).verifyComplete();

        verify(state).setMyPlanetId("p1");
        verify(state).setMySystemName("Sol");
        verify(state).addConnectedPlanet(planetDetail);
    }

    @Test
    @DisplayName("scanGalaxy — continue si une planète est inaccessible (onErrorResume)")
    void scanGalaxy_continuesOnPlanetError() {
        var system = new StarSystem("Sol", new StarSystem.Coordinates(0, 0, 0), "G", List.of());
        var connectedStatus = new Planet.PlanetStatus("connected", null, null, null);
        var planet1 = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, null, null);
        var planet2 = new Planet("p2", "Luna",  4, 1.2, null, connectedStatus, null, null, null);
        var ourStation = new StationInfo("Alpha Base", "alpha-team", Map.of(), null, 4, BigInteger.valueOf(50000));
        var p1Detail = new Planet("p1", "Terra", 3, 1.0, null, connectedStatus, null, ourStation, null);

        when(galaxyClient.getAllSystems()).thenReturn(Flux.just(system));
        when(galaxyClient.getPlanets("Sol")).thenReturn(Flux.just(planet1, planet2));
        when(galaxyClient.getPlanet("Sol", "p1")).thenReturn(Mono.just(p1Detail));
        when(galaxyClient.getPlanet("Sol", "p2"))
                .thenReturn(Mono.error(new RuntimeException("503 Service Unavailable")));

        StepVerifier.create(galaxyService.scanGalaxy()).verifyComplete();

        verify(state).addConnectedPlanet(p1Detail);
        verify(state).setMyPlanetId("p1");
    }

    @Test
    @DisplayName("scanGalaxy — scanne plusieurs systèmes (flatMap parallèle)")
    void scanGalaxy_parallelSystemScan() {
        var sys1 = new StarSystem("Sol", new StarSystem.Coordinates(0, 0, 0), "G", List.of());
        var sys2 = new StarSystem("Alpha Centauri", new StarSystem.Coordinates(4, 2, 1), "K", List.of());
        var settledStatus = new Planet.PlanetStatus("settled", null, null, null);
        var p1 = new Planet("p1", "Terra",    3, 1.0, null, settledStatus, null, null, null);
        var p2 = new Planet("p2", "Proxima-b",2, 0.8, null, settledStatus, null, null, null);
        var stationOther = new StationInfo("X", "other", Map.of(), null, 1, BigInteger.valueOf(1000));
        var p1Detail = new Planet("p1", "Terra",    3, 1.0, null, settledStatus, null, stationOther, null);
        var p2Detail = new Planet("p2", "Proxima-b",2, 0.8, null, settledStatus, null, stationOther, null);

        when(galaxyClient.getAllSystems()).thenReturn(Flux.just(sys1, sys2));
        when(galaxyClient.getPlanets("Sol")).thenReturn(Flux.just(p1));
        when(galaxyClient.getPlanets("Alpha Centauri")).thenReturn(Flux.just(p2));
        when(galaxyClient.getPlanet("Sol", "p1")).thenReturn(Mono.just(p1Detail));
        when(galaxyClient.getPlanet("Alpha Centauri", "p2")).thenReturn(Mono.just(p2Detail));

        StepVerifier.create(galaxyService.scanGalaxy()).verifyComplete();

        verify(state).addConnectedPlanet(p1Detail);
        verify(state).addConnectedPlanet(p2Detail);
    }

    @Test
    @DisplayName("initialize — chaîne player, webhook, scan dans l'ordre")
    void initialize_chainsSequentially() {
        var player = new Player("alpha-team", "Alpha", 10000L, "key", null, null);
        var playerWithWebhook = new Player("alpha-team", "Alpha", 10000L, "key",
                "http://localhost:8081/webhooks", null);

        when(playerClient.getMyProfile("alpha-team")).thenReturn(Mono.just(player));
        when(playerClient.registerWebhookUrl("alpha-team", "http://localhost:8081/webhooks"))
                .thenReturn(Mono.just(playerWithWebhook));
        when(galaxyClient.getAllSystems()).thenReturn(Flux.empty());

        StepVerifier.create(galaxyService.initialize()).verifyComplete();

        verify(state).setCredits(10000L);
        var inOrder = inOrder(playerClient, galaxyClient);
        inOrder.verify(playerClient).getMyProfile("alpha-team");
        inOrder.verify(playerClient).registerWebhookUrl(anyString(), anyString());
        inOrder.verify(galaxyClient).getAllSystems();
    }

    @Test
    @DisplayName("getMyStationInventory — erreur si station non initialisée")
    void getMyStationInventory_errorWhenNotInitialized() {
        when(state.getMyPlanetId()).thenReturn(null);
        when(state.getMySystemName()).thenReturn(null);

        StepVerifier.create(galaxyService.getMyStationInventory())
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("getMyStationInventory — retourne l'inventaire si initialisé")
    void getMyStationInventory_returnsInventory() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(state.getMySystemName()).thenReturn("Sol");
        var station = new StationInfo("Alpha Base", "alpha-team",
                Map.of("food", 200L, "water", 50L), null, 4, BigInteger.valueOf(50000));
        when(stationClient.getMyStation("Sol", "p1")).thenReturn(Mono.just(station));

        StepVerifier.create(galaxyService.getMyStationInventory())
                .expectNext(station)
                .verifyComplete();
    }
}