package com.offworld;

import com.offworld.config.AppConfig;
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

    private final GalaxyService    galaxyService;
    private final MarketService    marketService;
    private final ShipService      shipService;
    private final TradingStrategy  tradingStrategy;
    private final ElevatorService  elevatorService;
    private final AppConfig        config;


    private final List<Disposable> subscriptions = new ArrayList<>();

    public OffworldApplication(GalaxyService galaxyService, MarketService marketService,
                               ShipService shipService, TradingStrategy tradingStrategy,
                               ElevatorService elevatorService, AppConfig config) {
        this.galaxyService   = galaxyService;
        this.marketService   = marketService;
        this.shipService     = shipService;
        this.tradingStrategy = tradingStrategy;
        this.elevatorService = elevatorService;
        this.config          = config;
    }

    public static void main(String[] args) {
        SpringApplication.run(OffworldApplication.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("=== Démarrage du bot Offworld | serveur={} joueur={} ===",
                config.serverUrl(), config.playerId());


        galaxyService.initialize()
                .then(marketService.initPrices())
                .then(shipService.syncActiveShips())
                .then(Mono.defer(() -> elevatorService.initExportDemands()))
                .then(Mono.defer(() -> elevatorService.checkAndTransferToOrbit()))
                .onErrorResume(e -> {
                    log.warn("Init incomplète (serveur injoignable ?) : {}", e.getMessage());
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(60));

        log.info("Initialisation terminée — lancement des boucles réactives");

        // [3] Stream SSE marché
        subscriptions.add(
                marketService.startMarketStream()
                        .subscribe(
                                e -> { /* traitement dans doOnNext du service */ },
                                err -> log.error("[SSE] Stream arrêté : {}", err.getMessage())
                        )
        );

        // [4] Polling ships
        subscriptions.add(
                shipService.startPolling(Duration.ofMillis(config.shipPollingIntervalMs()))
                        .subscribe(
                                ship -> { /* transitions gérées dans ShipService */ },
                                err -> log.error("[POLL] Polling ships arrêté : {}", err.getMessage())
                        )
        );

        // [1+2] Boucle de stratégie
        subscriptions.add(
                tradingStrategy.startStrategyLoop(Duration.ofMillis(config.strategyIntervalMs()))
                        .subscribe(
                                v -> {},
                                err -> log.error("[STRATEGY] Boucle arrêtée : {}", err.getMessage())
                        )
        );

        // [2] Check ascenseur toutes les 60s
        subscriptions.add(
                Flux.interval(Duration.ofSeconds(60))
                        .onBackpressureDrop()
                        .flatMap(tick -> elevatorService.checkAndTransferToOrbit()
                                .onErrorResume(e -> {
                                    log.warn("[ELEVATOR] Erreur tick {} : {}", tick, e.getMessage());
                                    return Mono.empty();
                                }))
                        .subscribe(
                                v -> {},
                                err -> log.error("[ELEVATOR] Boucle arrêtée : {}", err.getMessage())
                        )
        );

        log.info("[OK] Bot opérationnel — 4 boucles actives + webhook server sur port {}",
                config.webhookUrl());
    }
}
