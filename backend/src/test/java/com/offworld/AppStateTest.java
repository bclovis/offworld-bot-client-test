package com.offworld;

import com.offworld.model.OrderBook;
import com.offworld.model.Planet;
import com.offworld.model.Ship;
import com.offworld.model.StationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * APP STATE TEST
 * * Purpose: Tests the central "memory" of the bot where all current game information is temporarily stored.
 * * What it checks:
 * 1. Data Storage: Does the bot correctly save and retrieve information about market prices, active ships, order books, known planets, and its current money (credits)?
 * 2. Data Updates: When a price changes or a ship changes status, does the bot correctly overwrite the old information with the new data?
 * 3. Thread Safety: Can multiple parts of the bot (e.g., the market scanner and the webhook receiver) read and write to this memory at the exact same time without crashing the program (ConcurrentModificationException)?
 */

class AppStateTest {

    private AppState appState;

    @BeforeEach
    void setUp() {
        appState = new AppState();
    }

    // -------------------------------------------------------------------------
    // Prix
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updatePrice/getPrice — stocke et retourne le dernier prix")
    void priceCache_storesAndReturns() {
        appState.updatePrice("food", 5L);
        appState.updatePrice("water", 3L);

        assertThat(appState.getPrice("food")).isEqualTo(5L);
        assertThat(appState.getPrice("water")).isEqualTo(3L);
    }

    @Test
    @DisplayName("updatePrice — écrase l'ancienne valeur (dernier SSE gagne)")
    void priceCache_overwritesOldValue() {
        appState.updatePrice("food", 5L);
        appState.updatePrice("food", 7L); // nouveau prix SSE

        assertThat(appState.getPrice("food")).isEqualTo(7L);
    }

    @Test
    @DisplayName("getPrice — retourne null pour un good inconnu")
    void priceCache_returnsNullForUnknown() {
        assertThat(appState.getPrice("unknown_good")).isNull();
    }

    @Test
    @DisplayName("getAllPrices — retourne tous les prix")
    void getAllPrices_returnsAll() {
        appState.updatePrice("food", 5L);
        appState.updatePrice("water", 3L);
        appState.updatePrice("silicon", 15L);

        var prices = appState.getAllPrices();
        assertThat(prices).hasSize(3)
                .containsEntry("food", 5L)
                .containsEntry("water", 3L)
                .containsEntry("silicon", 15L);
    }

    // -------------------------------------------------------------------------
    // Ships
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("trackShip/getShip — enregistre et retrouve un ship")
    void shipTracking_storesAndReturns() {
        var ship = buildShip("s1", Ship.IN_TRANSIT);
        appState.trackShip(ship);

        assertThat(appState.getShip("s1")).isEqualTo(ship);
        assertThat(appState.getActiveShips()).containsKey("s1");
    }

    @Test
    @DisplayName("updateShip — remplace le ship existant")
    void shipTracking_updatesExisting() {
        var ship1 = buildShip("s1", Ship.IN_TRANSIT);
        var ship2 = buildShip("s1", Ship.LOADING); // même id, nouveau statut

        appState.trackShip(ship1);
        appState.updateShip(ship2);

        assertThat(appState.getShip("s1").status()).isEqualTo(Ship.LOADING);
    }

    @Test
    @DisplayName("removeShip — retire le ship de l'état actif")
    void shipTracking_removesShip() {
        appState.trackShip(buildShip("s1", Ship.IN_TRANSIT));
        appState.trackShip(buildShip("s2", Ship.LOADING));

        appState.removeShip("s1");

        assertThat(appState.getActiveShips()).doesNotContainKey("s1");
        assertThat(appState.getActiveShips()).containsKey("s2");
    }

    @Test
    @DisplayName("getShip — retourne null si le ship n'existe pas")
    void getShip_returnsNullIfNotFound() {
        assertThat(appState.getShip("unknown")).isNull();
    }

    // -------------------------------------------------------------------------
    // OrderBooks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateOrderBook/getOrderBook — stocke et retourne l'order book")
    void orderBook_storesAndReturns() {
        var book = new OrderBook("food",
                List.of(new OrderBook.PriceLevel(5L, 1000L, 3)),
                List.of(new OrderBook.PriceLevel(6L, 500L, 1)),
                5L);

        appState.updateOrderBook("food", book);

        var retrieved = appState.getOrderBook("food");
        assertThat(retrieved).isEqualTo(book);
        assertThat(retrieved.bestBid()).isEqualTo(5L);
        assertThat(retrieved.bestAsk()).isEqualTo(6L);
    }

    // -------------------------------------------------------------------------
    // Planètes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addConnectedPlanet — stocke et retrouve par ID")
    void connectedPlanets_storesById() {
        var status = new Planet.PlanetStatus("connected", null, null, null);
        var planet = new Planet("p1", "Terra", 3, 1.0, null, status, null, null, null);

        appState.addConnectedPlanet(planet);

        assertThat(appState.getConnectedPlanets()).containsKey("p1");
        assertThat(appState.getConnectedPlanets().get("p1").name()).isEqualTo("Terra");
    }

    // -------------------------------------------------------------------------
    // Credits
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setCredits/getCredits — met à jour les crédits")
    void credits_storesAndReturns() {
        assertThat(appState.getCredits()).isEqualTo(0L);

        appState.setCredits(15000L);
        assertThat(appState.getCredits()).isEqualTo(15000L);
    }

    // -------------------------------------------------------------------------
    // Thread-safety
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updatePrice — thread-safe sous accès concurrent")
    void priceCache_isConcurrentlySafe() throws InterruptedException {
        int threadCount = 50;
        var latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < threadCount; i++) {
            final long price = i;
            pool.submit(() -> {
                try {
                    appState.updatePrice("food", price);
                    appState.getPrice("food"); // lecture concurrente
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Pas de ConcurrentModificationException = thread-safe
        assertThat(appState.getPrice("food")).isNotNull();
    }

    @Test
    @DisplayName("trackShip/removeShip — thread-safe sous accès concurrent")
    void shipTracking_isConcurrentlySafe() throws InterruptedException {
        int threadCount = 50;
        var latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < threadCount; i++) {
            final String shipId = "ship-" + i;
            pool.submit(() -> {
                try {
                    appState.trackShip(buildShip(shipId, Ship.IN_TRANSIT));
                    appState.getActiveShips().size(); // lecture concurrente
                    if (Math.random() > 0.5) appState.removeShip(shipId);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        // Aucune exception = thread-safe
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Ship buildShip(String id, String status) {
        return new Ship(id, "alpha-team", "p1", "p2",
                Map.of("food", 100L), status, null, "truck-1", 50L,
                System.currentTimeMillis(), null, null);
    }
}
