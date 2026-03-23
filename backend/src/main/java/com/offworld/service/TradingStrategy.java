package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.MarketClient;
import com.offworld.client.ShipClient;
import com.offworld.client.StationClient;
import com.offworld.client.TradeClient;
import com.offworld.config.AppConfig;
import com.offworld.model.MarketOrder;
import com.offworld.model.OrderBook;
import com.offworld.model.StationInfo;
import com.offworld.model.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Automated trading strategy. Loop every N seconds to buy/sell. */
@Service
public class TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TradingStrategy.class);

    private static final double MIN_MARGIN = 0.05;
    private static final long MAX_SELL_QUANTITY = 100;
    private static final long MIN_STOCK_BUFFER = 50;
    private static final long ORDER_MAX_AGE_MS = 5 * 60 * 1000L;
    private static final long TRUCKING_MIN_QTY = 100L;
    // Starting price when market is empty (per good)
    private static final Map<String, Long> DEFAULT_PRICES = Map.of(
        "food", 5L, "water", 3L, "iron_ore", 8L,
        "copper_ore", 10L, "silicon", 15L
    );

    private final MarketClient marketClient;
    private final StationClient stationClient;
    private final TradeClient tradeClient;
    private final GalaxyService galaxyService;
    private final ShipClient shipClient;
    private final AppConfig config;
    private final AppState state;

    public TradingStrategy(MarketClient marketClient, StationClient stationClient,
                           TradeClient tradeClient, GalaxyService galaxyService,
                           ShipClient shipClient, AppConfig config, AppState state) {
        this.marketClient = marketClient;
        this.stationClient = stationClient;
        this.tradeClient = tradeClient;
        this.galaxyService = galaxyService;
        this.shipClient = shipClient;
        this.config = config;
        this.state = state;
    }

    public Flux<Void> startStrategyLoop(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .flatMap(tick -> runOneTick()
                        .onErrorResume(e -> {
                            log.error("Error in strategy (tick {}): {}", tick, e.getMessage());
                            return Mono.empty();
                        })
                )
                .doOnSubscribe(s -> log.info("Strategy loop started (interval={})", interval));
    }

    Mono<Void> runOneTick() {
        log.debug("Strategy tick...");
        if (state.getMyPlanetId() == null) {
            log.warn("Station not yet initialized, skip");
            return Mono.empty();
        }

        return Mono.zip(
                galaxyService.getMyStationInventory(),
                marketClient.getMyOrders("open").collectList()
        ).flatMap(tuple -> {
            var station = tuple.getT1();
            var openOrders = tuple.getT2();

            var goodsAlreadySelling = openOrders.stream()
                    .filter(o -> "sell".equals(o.side()))
                    .map(o -> o.goodName())
                    .collect(java.util.stream.Collectors.toSet());
            var goodsAlreadyBuying = openOrders.stream()
                    .filter(o -> "buy".equals(o.side()))
                    .map(o -> o.goodName())
                    .collect(java.util.stream.Collectors.toSet());

            log.info("[TICK] Station inventory: {}u stored | {} open orders ({}b/{}s)",
                    station.totalStored(), openOrders.size(),
                    goodsAlreadySelling.size(), goodsAlreadyBuying.size());

            return Mono.when(
                    sellGoodsWeHave(station, goodsAlreadySelling),
                    buyGoodsFromMarket(station, goodsAlreadyBuying),
                    cancelOldOrders(openOrders),
                    shipGoodsIfNeeded(station)
            );
        });
    }

    private Mono<Void> sellGoodsWeHave(StationInfo station, java.util.Set<String> goodsAlreadySelling) {
        if (station.inventory() == null || station.inventory().isEmpty()) {
            return Mono.empty();
        }

        List<Mono<Void>> sells = new ArrayList<>();

        for (Map.Entry<String, Long> entry : station.inventory().entrySet()) {
            String good = entry.getKey();
            long qty = entry.getValue();

            if ("construction".equals(good)) continue;
            if (qty <= MIN_STOCK_BUFFER) continue;
            if (goodsAlreadySelling.contains(good)) continue;

            long toSell = Math.min(qty - MIN_STOCK_BUFFER, MAX_SELL_QUANTITY);

            Mono<Void> sellOp = marketClient.getOrderBook(good)
                    .flatMap(book -> {
                        state.updateOrderBook(good, book);

                        Long bestBid = book.bestBid();
                        Long lastPrice = book.lastTradePrice();

                        // If market is empty, use default price to bootstrap
                        long refPrice;
                        if (bestBid != null) {
                            refPrice = bestBid;
                        } else if (lastPrice != null) {
                            refPrice = lastPrice;
                        } else {
                            refPrice = DEFAULT_PRICES.getOrDefault(good, 5L);
                            log.info("Empty market for {}, starting price: {}", good, refPrice);
                        }

                        long sellPrice = Math.max(refPrice, 1L);

                        log.info("Selling {} units of {} at {} credits/u", toSell, good, sellPrice);
                        return marketClient.placeOrder(
                                MarketOrder.PlaceOrderRequest.limitSell(good, sellPrice, toSell, state.getMyPlanetId())
                        ).then();
                    })
                    .onErrorResume(e -> {
                        log.warn("Erreur vente de {}: {}", good, e.getMessage());
                        return Mono.empty();
                    });

            sells.add(sellOp);
        }

        if (sells.isEmpty()) return Mono.empty();
        return Mono.when(sells);
    }

    // Niveau 1 : prix SSE dispo → limit buy si sous le seuil. Niveau 2 : order book → market buy pour amorcer le cache SSE.
    private Mono<Void> buyGoodsFromMarket(com.offworld.model.StationInfo station,
                                          java.util.Set<String> goodsAlreadyBuying) {
        // We only buy if we have room in orbit
        if (station.freeSpace() < MIN_STOCK_BUFFER * 2) {
            log.debug("[BUY] Station pleine ({}/{} u), skip rachats", station.totalStored(), station.maxStorage());
            return Mono.empty();
        }

        List<Mono<Void>> buys = new ArrayList<>();

        for (Map.Entry<String, Long> entry : DEFAULT_PRICES.entrySet()) {
            String good = entry.getKey();
            long defaultPrice = entry.getValue();

            if (goodsAlreadyBuying.contains(good)) continue;

            // ── Niveau 1 : prix SSE disponible ────────────────────────────
            Long ssePrice = state.getPrice(good);
            if (ssePrice != null && ssePrice > 0) {
                long buyThreshold = (long) (defaultPrice * 0.75);
                if (ssePrice <= buyThreshold) {
                    long buyQty = 50L;
                    log.info("[SSE→BUY] Signal achat via SSE : {} @ {}c ≤ seuil {}c → limit buy {}u",
                            good, ssePrice, buyThreshold, buyQty);
                    buys.add(placeLimitBuy(good, ssePrice, buyQty));
                    continue;
                }
                // Prix SSE connu mais pas sous le seuil → on tombe au niveau 2 pour amorcer
            }

            // Level 2: bootstrap via order book
            buys.add(
                    marketClient.getOrderBook(good)
                            .flatMap(book -> {
                                state.updateOrderBook(good, book);
                                Long bestAsk = book.bestAsk();
                                if (bestAsk == null) {
                                    log.debug("[BUY] Pas d'offre disponible pour {}", good);
                                    return Mono.empty();
                                }
                                // Only buy if ask price is reasonable
                                if (bestAsk > defaultPrice * 2) return Mono.empty();
                                long buyQty = 30L;
                                log.info("[ORDERBOOK→BUY] Bootstrap market {}: bestAsk={}c → market buy {}u (generates SSE)",
                                        good, bestAsk, buyQty);
                                return marketClient.placeOrder(
                                        MarketOrder.PlaceOrderRequest.limitBuy(good, bestAsk, buyQty, state.getMyPlanetId())
                                ).doOnNext(o -> log.info("[BUY] ✓ Order placed id={} → trade expected → SSE",
                                        o.id().substring(0, 8)))
                                .then();
                            })
                            .onErrorResume(e -> {
                                log.warn("[BUY] Erreur order book {} : {}", good, e.getMessage());
                                return Mono.empty();
                            })
            );
        }

        return buys.isEmpty() ? Mono.empty() : Mono.when(buys);
    }

    private Mono<Void> placeLimitBuy(String good, long price, long qty) {
        return marketClient.placeOrder(
                        MarketOrder.PlaceOrderRequest.limitBuy(good, price, qty, state.getMyPlanetId())
                )
                .doOnNext(o -> log.info("[BUY] Buy order placed: id={} {}× {} @ {}c",
                        o.id().substring(0, 8), o.quantity(), o.goodName(), o.price()))
                .then()
                .onErrorResume(e -> {
                    log.warn("[BUY] Erreur placement buy {} : {}", good, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Annule les ordres qui sont ouverts depuis trop longtemps.
     * An order that hangs too long without being filled is probably poorly priced.
     */
    private Mono<Void> cancelOldOrders(java.util.List<com.offworld.model.MarketOrder> openOrders) {
        long now = System.currentTimeMillis();
        return Flux.fromIterable(openOrders)
                .filter(order -> (now - order.createdAt()) > ORDER_MAX_AGE_MS)
                .flatMap(order -> {
                    log.info("Annulation ordre vieux de {}min: {} {}", 
                            (now - order.createdAt()) / 60000, order.side(), order.goodName());
                    return marketClient.cancelOrder(order.id());
                })
                .then();
    }

    /**
     * Creates an export request to generate supply for a good.
     * Useful to "bootstrap" our economy if our station lacks goods to sell.
     */
    private Mono<Void> shipGoodsIfNeeded(StationInfo station) {
        var planets = state.getConnectedPlanets();
        if (planets == null || planets.isEmpty()) return Mono.empty();
        if (!state.getActiveShips().isEmpty()) return Mono.empty();

        var dest = planets.values().stream()
                .filter(p -> !p.id().equals(state.getMyPlanetId()))
                .findFirst().orElse(null);
        if (dest == null) return Mono.empty();

        if (station.inventory() == null) return Mono.empty();
        var best = station.inventory().entrySet().stream()
                .filter(e -> !"construction".equals(e.getKey()))
                .filter(e -> e.getValue() > MIN_STOCK_BUFFER + TRUCKING_MIN_QTY)
                .max(Map.Entry.comparingByValue()).orElse(null);
        if (best == null) return Mono.empty();

        long qty = Math.min(best.getValue() - MIN_STOCK_BUFFER, TRUCKING_MIN_QTY);
        var cargo = Map.of(best.getKey(), qty);
        log.info("[SHIP] Trucking {}× {} → {}", qty, best.getKey(), dest.name());
        return shipClient.hireTrucking(state.getMyPlanetId(), dest.id(), cargo)
                .doOnNext(ship -> {
                    log.info("[SHIP] ✓ Ship {} en route vers {}", ship.id().substring(0, 8), dest.name());
                    state.trackShip(ship);
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("[SHIP] Erreur trucking: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> createExportDemand(String goodName, long ratePerTick, long totalQty) {
        if (state.getMyPlanetId() == null) return Mono.empty();

        var req = new TradeRequest.CreateTradeRequest(
                state.getMyPlanetId(),
                goodName,
                "export",
                "fixed_rate",
                ratePerTick,
                totalQty,
                null
        );
        return tradeClient.createTradeRequest(req)
                .doOnNext(r -> log.info("Export request created: id={} good={}", r.id(), r.goodName()))
                .then();
    }
}
