package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.MarketClient;
import com.offworld.model.MarketOrder;
import com.offworld.model.TradeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * MARKET SERVICE TEST
 * * Purpose: Tests the bot's ability to monitor the game's stock market and manage its trading orders.
 * * What it checks:
 * 1. Live Monitoring: Does the bot correctly listen to the real-time market stream (SSE) and update its internal memory with the latest prices?
 * 2. Initial Setup: Can it successfully download the starting market prices when the bot boots up?
 * 3. Order Cleanup: Can it successfully find and cancel all its currently open or partially filled orders to reset its market presence?
 */

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock private MarketClient marketClient;
    @Mock private AppState state;

    private MarketService marketService;

    @BeforeEach
    void setUp() {
        marketService = new MarketService(marketClient, state);
    }



    @Test
    @DisplayName("startMarketStream — updates price cache on each trade")
    void startMarketStream_updatesPriceCache() {
        // GIVEN : 3 trade events SSE
        var trade1 = new TradeEvent("t1", "food",   5L, 100L, "buyer1", "alpha-team", "p2", "p1", 1000L);
        var trade2 = new TradeEvent("t2", "water",  3L,  50L, "buyer2", "alpha-team", "p2", "p1", 2000L);
        var trade3 = new TradeEvent("t3", "food",   6L,  75L, "buyer1", "alpha-team", "p2", "p1", 3000L);

        when(marketClient.streamTrades()).thenReturn(Flux.just(trade1, trade2, trade3));

        // WHEN
        StepVerifier.create(marketService.startMarketStream())
                .expectNext(trade1, trade2, trade3)
                .verifyComplete();

        // THEN: each price is updated in state
        verify(state).updatePrice("food",  5L);
        verify(state).updatePrice("water", 3L);
        verify(state).updatePrice("food",  6L);  
    }

    @Test
    @DisplayName("startMarketStream — propage les trades au subscriber")
    void startMarketStream_propagatesAllEvents() {
        // GIVEN : flux de 5 trades
        var trades = Flux.range(1, 5)
                .map(i -> new TradeEvent("t" + i, "iron_ore", (long) (i * 10), 100L,
                        "b", "s", "p1", "p2", (long) i));

        when(marketClient.streamTrades()).thenReturn(trades);

        // WHEN / THEN : tous les events passent
        StepVerifier.create(marketService.startMarketStream())
                .expectNextCount(5)
                .verifyComplete();

        // 5 price updates
        verify(state, times(5)).updatePrice(eq("iron_ore"), anyLong());
    }

    @Test
    @DisplayName("startMarketStream — se termine proprement si le flux SSE se ferme")
    void startMarketStream_completesWhenStreamEnds() {
        // GIVEN: empty stream (server disconnect)
        when(marketClient.streamTrades()).thenReturn(Flux.empty());

        StepVerifier.create(marketService.startMarketStream())
                .verifyComplete();

        verifyNoInteractions(state);
    }



    @Test
    @DisplayName("initPrices — loads all prices and stores in state")
    void initPrices_loadsAndStoresPrices() {
        // GIVEN
        var prices = Map.of("food", 5L, "water", 3L, "iron_ore", 8L, "silicon", 15L);
        when(marketClient.getAllPrices()).thenReturn(Mono.just(prices));

        // WHEN
        StepVerifier.create(marketService.initPrices())
                .verifyComplete();

        // THEN: each price is recorded
        verify(state).updatePrice("food",     5L);
        verify(state).updatePrice("water",    3L);
        verify(state).updatePrice("iron_ore", 8L);
        verify(state).updatePrice("silicon",  15L);
    }

    @Test
    @DisplayName("initPrices — handles empty map without error")
    void initPrices_emptyMapDoesNothing() {
        when(marketClient.getAllPrices()).thenReturn(Mono.just(Map.of()));

        StepVerifier.create(marketService.initPrices())
                .verifyComplete();

        verifyNoInteractions(state);
    }

    @Test
    @DisplayName("initPrices — propage l'erreur si le serveur est injoignable")
    void initPrices_propagatesNetworkError() {
        when(marketClient.getAllPrices())
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        StepVerifier.create(marketService.initPrices())
                .expectError(RuntimeException.class)
                .verify();
    }

    

    @Test
    @DisplayName("cancelAllOpenOrders — annule tous les ordres open + partially_filled")
    void cancelAllOpenOrders_cancelsAllActiveOrders() {
        // GIVEN : 2 ordres ouverts, 1 partiellement rempli
        var order1 = new MarketOrder("o1", "alpha-team", "food",  "sell", "limit",
                5L, 100L, 0L,   "open",             "p1", 1000L);
        var order2 = new MarketOrder("o2", "alpha-team", "water", "sell", "limit",
                3L,  50L, 0L,   "open",             "p1", 2000L);
        var order3 = new MarketOrder("o3", "alpha-team", "food",  "buy",  "limit",
                4L,  30L, 15L, "partially_filled", "p1", 3000L);

        when(marketClient.getMyOrders("open")).thenReturn(Flux.just(order1, order2));
        when(marketClient.getMyOrders("partially_filled")).thenReturn(Flux.just(order3));
        when(marketClient.cancelOrder("o1")).thenReturn(Mono.just(order1));
        when(marketClient.cancelOrder("o2")).thenReturn(Mono.just(order2));
        when(marketClient.cancelOrder("o3")).thenReturn(Mono.just(order3));

        // WHEN
        StepVerifier.create(marketService.cancelAllOpenOrders())
                .verifyComplete();

        // THEN: all 3 orders canceled
        verify(marketClient).cancelOrder("o1");
        verify(marketClient).cancelOrder("o2");
        verify(marketClient).cancelOrder("o3");
    }

    @Test
    @DisplayName("cancelAllOpenOrders — ne fait rien si aucun ordre ouvert")
    void cancelAllOpenOrders_noopWhenEmpty() {
        when(marketClient.getMyOrders("open")).thenReturn(Flux.empty());
        when(marketClient.getMyOrders("partially_filled")).thenReturn(Flux.empty());

        StepVerifier.create(marketService.cancelAllOpenOrders())
                .verifyComplete();

        verify(marketClient, never()).cancelOrder(any());
    }
}
