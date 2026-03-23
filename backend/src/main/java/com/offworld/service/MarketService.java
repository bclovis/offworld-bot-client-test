package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.MarketClient;
import com.offworld.model.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Manages subscription to market SSE stream. */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    // Trade counter received via SSE — to make the stream visible in logs
    private final AtomicLong sseTradeCount = new AtomicLong(0);

    private final MarketClient marketClient;
    private final AppState state;

    public MarketService(MarketClient marketClient, AppState state) {
        this.marketClient = marketClient;
        this.state = state;
    }

    public Flux<TradeEvent> startMarketStream() {
        return marketClient.streamTrades()
                .doOnNext(event -> {
                    state.updatePrice(event.goodName(), event.price());

                    long n = sseTradeCount.incrementAndGet();
                    log.info("[SSE #{}/{}] Market trade: {}× {} @ {} credits | buyer={} seller={}",
                            n, formatCount(n),
                            event.quantity(), event.goodName(), event.price(),
                            abbrev(event.buyerId()), abbrev(event.sellerId()));
                });
    }

    /**
     * Initialise les prix en faisant un appel REST initial avant de brancher le SSE.
     * This way we have data from startup.
     */
    public Mono<Void> initPrices() {
        return marketClient.getAllPrices()
                .doOnNext(prices -> {
                    prices.forEach(state::updatePrice);
                    log.info("Initial prices loaded: {} goods", prices.size());
                })
                .then();
    }

    // Cancelle tous les ordres ouverts (utile en cas de restart)
    public Mono<Void> cancelAllOpenOrders() {
        return marketClient.getMyOrders("open")
                .concatWith(marketClient.getMyOrders("partially_filled"))
                .flatMap(order -> marketClient.cancelOrder(order.id()))
                .then()
                .doOnSuccess(v -> log.info("Open orders canceled"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Abbreviate player id for logs: "alpha-team" → "alpha" */
    private static String abbrev(String id) {
        if (id == null) return "?";
        int idx = id.indexOf('-');
        return idx > 0 ? id.substring(0, idx) : id;
    }

    /** Display trade rank in readable format ("1st", "10th", etc.) */
    private static String formatCount(long n) {
        return n == 1 ? "1er" : n + "e";
    }
}
