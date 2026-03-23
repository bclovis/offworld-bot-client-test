package com.offworld;

import com.offworld.config.AppConfig;
import com.offworld.service.ConstructionService;
import com.offworld.service.ElevatorService;
import com.offworld.service.GalaxyService;
import com.offworld.service.MarketService;
import com.offworld.service.ShipService;
import com.offworld.service.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class OffworldApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OffworldApplication.class);

    private final GalaxyService       galaxyService;
    private final MarketService       marketService;
    private final ShipService         shipService;
    private final TradingStrategy     tradingStrategy;
    private final ElevatorService     elevatorService;
    private final ConstructionService constructionService;
    private final AppConfig           config;


    private final List<Disposable> subscriptions = new ArrayList<>();

    public OffworldApplication(GalaxyService galaxyService, MarketService marketService,
                               ShipService shipService, TradingStrategy tradingStrategy,
                               ElevatorService elevatorService, ConstructionService constructionService,
                               AppConfig config) {
        this.galaxyService       = galaxyService;
        this.marketService       = marketService;
        this.shipService         = shipService;
        this.tradingStrategy     = tradingStrategy;
        this.elevatorService     = elevatorService;
        this.constructionService = constructionService;
        this.config              = config;
    }

    public static void main(String[] args) {
        SpringApplication.run(OffworldApplication.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("=== Offworld bot starting | server={} player={} ===",
                config.serverUrl(), config.playerId());


        galaxyService.initialize()
                .then(marketService.initPrices())
                .then(shipService.syncActiveShips())
                .then(Mono.defer(() -> elevatorService.initExportDemands()))
                .then(Mono.defer(() -> elevatorService.checkAndTransferToOrbit()))
                .then(Mono.defer(() -> constructionService.syncProjects()))
                .onErrorResume(e -> {
                    log.warn("Incomplete initialization (server unreachable?): {}", e.getMessage());
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(60));

        log.info("Initialization complete — starting reactive loops");

        // [3] Market SSE stream
        subscriptions.add(
                marketService.startMarketStream()
                        .subscribe(
                                e -> { /* processing in service doOnNext */ },
                                err -> log.error("[SSE] Stream stopped: {}", err.getMessage())
                        )
        );

        // [4] Ships polling
        subscriptions.add(
                shipService.startPolling(Duration.ofMillis(config.shipPollingIntervalMs()))
                        .subscribe(
                                ship -> { /* state transitions managed in ShipService */ },
                                err -> log.error("[POLL] Ships polling stopped: {}", err.getMessage())
                        )
        );

        // [1+2] Strategy loop
        subscriptions.add(
                tradingStrategy.startStrategyLoop(Duration.ofMillis(config.strategyIntervalMs()))
                        .subscribe(
                                v -> {},
                                err -> log.error("[STRATEGY] Loop stopped: {}", err.getMessage())
                        )
        );

        // [5] Construction polling every 30s
        subscriptions.add(
                constructionService.startPolling(Duration.ofSeconds(30))
                        .subscribe(
                                v -> {},
                                err -> log.error("[CONSTRUCTION] Loop stopped: {}", err.getMessage())
                        )
        );

        // [2] Check elevator every 60s
        subscriptions.add(
                Flux.interval(Duration.ofSeconds(60))
                        .onBackpressureDrop()
                        .flatMap(tick -> elevatorService.checkAndTransferToOrbit()
                                .onErrorResume(e -> {
                                    log.warn("[ELEVATOR] Error tick {}: {}", tick, e.getMessage());
                                    return Mono.empty();
                                }))
                        .subscribe(
                                v -> {},
                                err -> log.error("[ELEVATOR] Loop stopped: {}", err.getMessage())
                        )
        );

        log.info("[OK] Bot operational — 5 active loops + webhook server on port {}",
                config.webhookUrl());
    }
}
