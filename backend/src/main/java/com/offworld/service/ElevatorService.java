package com.offworld.service;

import com.offworld.AppState;
import com.offworld.client.StationClient;
import com.offworld.client.TradeClient;
import com.offworld.model.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Manages space elevator and export requests. */
@Service
public class ElevatorService {

    private static final Logger log = LoggerFactory.getLogger(ElevatorService.class);

    // Goods for which we create export requests at startup
    private static final List<String> EXPORT_GOODS = List.of("food", "water", "iron_ore", "copper_ore");
    private static final long EXPORT_RATE_PER_TICK = 10L;   // units/tick
    private static final long EXPORT_TOTAL_QTY    = 500L;   // total to generate

    private final StationClient stationClient;
    private final TradeClient   tradeClient;
    private final AppState      state;

    public ElevatorService(StationClient stationClient, TradeClient tradeClient, AppState state) {
        this.stationClient = stationClient;
        this.tradeClient   = tradeClient;
        this.state         = state;
    }

    public Mono<Void> initExportDemands() {
        if (state.getMyPlanetId() == null) {
            log.warn("[TRADE] Station not initialized, skip export demands");
            return Mono.empty();
        }

        // Fetches active trade requests to avoid duplicates
        return tradeClient.getMyTradeRequests()
                .filter(r -> "active".equals(r.status()))
                .map(TradeRequest::goodName)
                .collect(Collectors.toSet())
                .flatMap(alreadyActive -> {
                    var creates = new ArrayList<Mono<Void>>();

                    for (String good : EXPORT_GOODS) {
                        if (alreadyActive.contains(good)) continue;
                        var req = new TradeRequest.CreateTradeRequest(
                                state.getMyPlanetId(),
                                good,
                                "export",
                                "fixed_rate",
                                EXPORT_RATE_PER_TICK,
                                EXPORT_TOTAL_QTY,
                                null
                        );
                        creates.add(
                                tradeClient.createTradeRequest(req)
                                        .doOnNext(r -> log.info(
                                                "[TRADE] Export demand created: {} → {}u/tick × {} ticks = {}u total",
                                                r.goodName(), r.ratePerTick(),
                                                r.totalQuantity() / r.ratePerTick(), r.totalQuantity()))
                                        .onErrorResume(e -> {
                                            log.debug("[TRADE] Export demand already active for {} (skip)", good);
                                            return Mono.empty();
                                        })
                                        .then()
                        );
                    }

                    if (creates.isEmpty()) return Mono.empty();
                    return Mono.when(creates);
                });
    }

    public Mono<Void> checkAndTransferToOrbit() {
        if (state.getMyPlanetId() == null || state.getMySystemName() == null) {
            return Mono.empty();
        }

        return stationClient.getElevatorStatus(state.getMySystemName(), state.getMyPlanetId())
                .flatMap(elevator -> {
                    var warehouse = elevator.warehouse();

                    if (warehouse == null
                            || warehouse.inventory() == null
                            || warehouse.inventory().isEmpty()) {
                        log.debug("[ELEVATOR] Surface warehouse empty — nothing to transfer");
                        return Mono.empty();
                    }

                    if (!elevator.hasAvailableCabin()) {
                        log.info("[ELEVATOR] No free cabins ({} cabins) — transfer deferred",
                                elevator.config().cabinCount());
                        return Mono.empty();
                    }

                    long cabinCapacity = elevator.config().cabinCapacity();
                    long remaining = cabinCapacity;
                    var items = new java.util.ArrayList<StationClient.TransferItem>();
                    for (var e : warehouse.inventory().entrySet()) {
                        if (e.getValue() <= 0 || remaining <= 0) continue;
                        long qty = Math.min(e.getValue(), remaining);
                        items.add(new StationClient.TransferItem(e.getKey(), qty));
                        remaining -= qty;
                    }

                    if (items.isEmpty()) return Mono.empty();

                    var summary = items.stream()
                            .map(i -> i.quantity() + "× " + i.goodName())
                            .collect(Collectors.joining(", "));

                    log.info("[ELEVATOR] ↑ Transfert surface→orbite — appel BLOQUANT sur boundedElastic : {}",
                            summary);

                    return stationClient.transferGoods(
                                    state.getMySystemName(),
                                    state.getMyPlanetId(),
                                    "to_orbit",
                                    items)
                            .doOnSuccess(result -> {
                                if (result.success()) {
                                    log.info("[ELEVATOR] ✓ Transfer OK — cabin={} duration={}s total={}u in orbit",
                                            result.cabinId(), result.durationSecs(), result.totalQuantity());
                                } else {
                                    log.warn("[ELEVATOR] ✗ Transfer failed: {}", result.failureReason());
                                }
                            })
                            .then();
                })
                .onErrorResume(e -> {
                    log.warn("[ELEVATOR] Erreur : {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
