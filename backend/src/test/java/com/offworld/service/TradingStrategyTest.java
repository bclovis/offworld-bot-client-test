package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.MarketClient;
import com.offworld.client.ShipClient;
import com.offworld.client.StationClient;
import com.offworld.client.TradeClient;
import com.offworld.config.AppConfig;
import com.offworld.model.MarketOrder;
import com.offworld.model.OrderBook;
import com.offworld.model.Planet;
import com.offworld.model.Ship;
import com.offworld.model.StationInfo;
import com.offworld.model.TradeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * TRADING STRATEGY TEST
 * * Purpose: Tests the automated "brain" of the bot that makes commercial decisions.
 * * What it checks:
 * 1. Selling Logic: Does the bot automatically place sell orders when it has surplus inventory?
 * 2. Order Management: Does it prevent spamming duplicate orders and automatically cancel old, stuck orders (older than 30 minutes)?
 * 3. Market Awareness: Does it check current market prices before selling, and use a fallback price if the market is completely empty?
 * 4. Safety Limits: Does it refuse to sell below a minimum emergency stock limit (buffer), and does it ignore non-tradeable items like "construction"?
 * 5. Exports: Can it successfully create trade requests to export goods to other planets?
 */


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingStrategyTest {

    @Mock private MarketClient marketClient;
    @Mock private StationClient stationClient;
    @Mock private TradeClient tradeClient;
    @Mock private GalaxyService galaxyService;
    @Mock private ShipClient shipClient;
    @Mock private AppConfig config;
    @Mock private AppState state;

    private TradingStrategy tradingStrategy;

    @BeforeEach
    void setUp() {
        lenient().when(config.playerId()).thenReturn("alpha-team");
        // Par défaut, getOrderBook retourne un book vide (pas de bestAsk → pas de buy)
        // Les tests qui veulent un comportement spécifique surchargent ce stub.
        lenient().when(marketClient.getOrderBook(anyString()))
                .thenReturn(Mono.just(new OrderBook("?", List.of(), List.of(), null)));
        tradingStrategy = new TradingStrategy(
                marketClient, stationClient, tradeClient, galaxyService, shipClient, config, state);
    }

    

    @Test
    @DisplayName("place un ordre sell si stock > buffer")
    void strategyLoop_placesSellOrder() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 200L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());

        var book = new OrderBook("food",
                List.of(new OrderBook.PriceLevel(5L, 100L, 1)), List.of(), 5L);
        when(marketClient.getOrderBook("food")).thenReturn(Mono.just(book));
        when(marketClient.placeOrder(any())).thenReturn(Mono.just(
                new MarketOrder("o1", "alpha-team", "food", "sell", "limit",
                        5L, 100L, 0L, "open", "p1", System.currentTimeMillis())));

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient).placeOrder(argThat(req ->
                "sell".equals(req.side()) && "food".equals(req.goodName())));
    }

    @Test
    @DisplayName("prix par défaut si marché vide")
    void strategyLoop_usesDefaultPrice() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 200L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());
        when(marketClient.getOrderBook("food"))
                .thenReturn(Mono.just(new OrderBook("food", List.of(), List.of(), null)));
        when(marketClient.placeOrder(any())).thenReturn(Mono.just(
                new MarketOrder("o1", "alpha-team", "food", "sell", "limit",
                        5L, 100L, 0L, "open", "p1", System.currentTimeMillis())));

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient).placeOrder(argThat(req -> req.price() == 5L));
    }

    @Test
    @DisplayName("annule les ordres vieux de plus de 5 minutes")
    void strategyLoop_cancelsOldOrders() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of())));

        long oldTs    = System.currentTimeMillis() - (6  * 60 * 1000L);
        long recentTs = System.currentTimeMillis() - (2  * 60 * 1000L);
        var oldOrder    = new MarketOrder("old", "alpha-team", "food", "sell",
                "limit", 5L, 50L, 0L, "open", "p1", oldTs);
        var recentOrder = new MarketOrder("rec", "alpha-team", "water", "sell",
                "limit", 3L, 20L, 0L, "open", "p1", recentTs);

        when(marketClient.getMyOrders("open")).thenReturn(Flux.just(oldOrder, recentOrder));
        when(marketClient.cancelOrder("old")).thenReturn(Mono.just(oldOrder));

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient).cancelOrder("old");
        verify(marketClient, never()).cancelOrder("rec");
    }

    @Test
    @DisplayName("ignore le good 'construction'")
    void strategyLoop_ignoresConstruction() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("construction", 999L, "food", 200L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());

        var book = new OrderBook("food",
                List.of(new OrderBook.PriceLevel(5L, 100L, 1)), List.of(), 5L);
        when(marketClient.getOrderBook("food")).thenReturn(Mono.just(book));
        when(marketClient.placeOrder(any())).thenReturn(Mono.just(
                new MarketOrder("o1", "alpha-team", "food", "sell", "limit",
                        5L, 100L, 0L, "open", "p1", System.currentTimeMillis())));

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient, never()).getOrderBook("construction");
        verify(marketClient, atLeast(1)).getOrderBook("food");
    }

    @Test
    @DisplayName("skip si station pas initialisée")
    void strategyLoop_skipsIfStationNotInitialized() {
        when(state.getMyPlanetId()).thenReturn(null);

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verifyNoInteractions(galaxyService);
        verifyNoInteractions(marketClient);
    }

    @Test
    @DisplayName("ne place pas d'ordre si sell déjà ouvert")
    void strategyLoop_skipsIfOrderExists() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 200L))));
        var existing = new MarketOrder("o1", "alpha-team", "food", "sell", "limit",
                5L, 50L, 0L, "open", "p1", System.currentTimeMillis());
        when(marketClient.getMyOrders("open")).thenReturn(Flux.just(existing));

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient, never()).placeOrder(any());
        // Le chemin d'achat (niveau 2) peut appeler getOrderBook pour amorcer le cache SSE,
        // mais aucun ordre ne doit être placé car les books sont vides (pas de bestAsk).
    }

    @Test
    @DisplayName("ne vend pas si stock <= MIN_STOCK_BUFFER (50)")
    void strategyLoop_doesNotSellBelowBuffer() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 50L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());

        StepVerifier.create(tradingStrategy.runOneTick())
                .verifyComplete();

        verify(marketClient, never()).placeOrder(any());
    }

   

    @Test
    @DisplayName("écrit un trucking si stock élevé et aucun ship actif")
    void shipGoods_hiresWhenStockHigh() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 500L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());

        var otherPlanet = new Planet("p2", "Mars", 3, 1.5, null,
                new Planet.PlanetStatus("connected", null, null, null), null, null, null);
        when(state.getConnectedPlanets()).thenReturn(Map.of("p2", otherPlanet));
        when(state.getActiveShips()).thenReturn(Map.of());

        var ship = new Ship("ship-uuid-1", null, "p1", "p2",
                Map.of("food", 100L), Ship.IN_TRANSIT_TO_ORIGIN, null, "t1", null, 0L, null, null);
        when(shipClient.hireTrucking(eq("p1"), eq("p2"), any())).thenReturn(Mono.just(ship));

        StepVerifier.create(tradingStrategy.runOneTick()).verifyComplete();

        verify(shipClient).hireTrucking(eq("p1"), eq("p2"), any());
    }

    @Test
    @DisplayName("skip le trucking si ships déjà actifs")
    void shipGoods_skipsWhenShipsActive() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(galaxyService.getMyStationInventory())
                .thenReturn(Mono.just(buildStation(Map.of("food", 500L))));
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());

        var ship = new Ship("ship-1", null, "p1", "p2",
                Map.of("food", 100L), Ship.IN_TRANSIT, null, null, null, 0L, null, null);
        when(state.getActiveShips()).thenReturn(Map.of("ship-1", ship));

        StepVerifier.create(tradingStrategy.runOneTick()).verifyComplete();

        verify(shipClient, never()).hireTrucking(any(), any(), any());
    }

    @Test
    @DisplayName("createExportDemand — crée la trade request")
    void createExportDemand_createsRequest() {
        when(state.getMyPlanetId()).thenReturn("p1");
        when(tradeClient.createTradeRequest(any())).thenReturn(Mono.just(
                new TradeRequest("tr1", "alpha-team", "p1", "food",
                        "export", "fixed_rate", 10L, 1000L, 0L, "active")));

        StepVerifier.create(tradingStrategy.createExportDemand("food", 10L, 1000L))
                .verifyComplete();

        verify(tradeClient).createTradeRequest(argThat(req ->
                "food".equals(req.goodName()) && "export".equals(req.direction())
                && "p1".equals(req.planetId()) && req.ratePerTick() == 10L));
    }

    @Test
    @DisplayName("createExportDemand — no-op si station pas initialisée")
    void createExportDemand_noopIfNotInitialized() {
        when(state.getMyPlanetId()).thenReturn(null);

        StepVerifier.create(tradingStrategy.createExportDemand("food", 10L, 1000L))
                .verifyComplete();

        verifyNoInteractions(tradeClient);
    }

    private StationInfo buildStation(Map<String, Long> inventory) {
        return new StationInfo("Alpha Base", "alpha-team",
                inventory, null, 4, BigInteger.valueOf(50000));
    }
}