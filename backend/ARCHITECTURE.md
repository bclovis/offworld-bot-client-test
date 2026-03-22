# Architecture — Offworld Bot Client

## Librairie réactive choisie : Project Reactor

Intégrée nativement avec Spring WebFlux. Fournit `Mono` (0-1 élément) et `Flux` (0-N éléments), les opérateurs de composition (`flatMap`, `zip`, `when`), et les schedulers pour gérer les threads.

---

## Structure du projet

```
OffworldApplication       ← point d'entrée, orchestre le démarrage
├── config/               ← AppConfig (YAML), WebClientConfig (WebClient partagé)
├── AppState              ← état partagé thread-safe (ConcurrentHashMap)
├── client/               ← couche HTTP (un client par domaine API)
│   ├── GalaxyClient      ← GET /systems, /planets
│   ├── PlayerClient      ← GET/PUT /players
│   ├── StationClient     ← GET /station, POST /space-elevator/transfer
│   ├── MarketClient      ← GET/POST /market/orders, SSE /market/trades
│   ├── ShipClient        ← GET /ships, POST /trucking, /dock, /undock
│   ├── TradeClient       ← POST/GET /trade
│   └── ConstructionClient← GET /construction
├── service/              ← logique métier réactive
│   ├── GalaxyService     ← scan galaxie + init au démarrage
│   ├── MarketService     ← stream SSE + cache de prix
│   ├── ShipService       ← lifecycle ships (webhooks + polling)
│   └── TradingStrategy   ← boucle de trading automatique
└── webhook/
    └── WebhookController ← POST /webhooks (endpoint exposé au serveur)
```

---

## Les 5 patterns d'interaction

### 1. Synchronous request/response
Tous les `GET` simples (liste des systèmes, prix du marché, inventaire station) :
```java
webClient.get().uri("/systems").retrieve().bodyToFlux(StarSystem.class)
```
Non-bloquant : le thread est libéré pendant l'attente réseau.

### 2. Blocking HTTP call — Space Elevator
Le serveur tient la connexion ouverte plusieurs secondes. On utilise `subscribeOn(Schedulers.boundedElastic())` pour déléguer sur un thread dédié aux I/O bloquantes sans jamais bloquer l'event-loop :
```java
webClient.post().uri("/space-elevator/transfer")
    .bodyValue(body)
    .retrieve().bodyToMono(ElevatorTransferResult.class)
    .timeout(Duration.ofSeconds(60))
    .subscribeOn(Schedulers.boundedElastic())  // ← clé du pattern
```

### 3. Polling — Ship lifecycle
`Flux.interval` génère un tick périodique. À chaque tick, on poll tous les ships actifs **en parallèle** avec `flatMap` :
```java
Flux.interval(Duration.ofMillis(4000))
    .onBackpressureDrop()
    .flatMap(tick -> Flux.fromIterable(activeShips).flatMap(shipClient::getShip))
    .retry()
```

### 4. Server-Sent Events — Market stream
`GET /market/trades` retourne un flux SSE infini. On consomme chaque event pour mettre à jour le cache de prix :
```java
marketClient.streamTrades()          // Flux<TradeEvent> infini
    .doOnNext(e -> state.updatePrice(e.goodName(), e.price()))
    // + retryWhen et onBackpressureBuffer(500) côté MarketClient
```

### 5. Webhooks — Server push
On expose `POST /webhooks`. Le serveur nous envoie les events ship (docking, livraison) et construction. On répond `200 OK` immédiatement et on traite en asynchrone (fire-and-forget) pour ne pas dépasser le timeout du serveur :
```java
@PostMapping
public Mono<ResponseEntity<String>> handleWebhook(@RequestBody Map<String, Object> payload) {
    shipService.handleWebhookEvent(event).subscribe(); // async
    return Mono.just(ResponseEntity.ok("ok"));         // réponse immédiate
}
```

---

## Flux de données global

```
Démarrage (séquentiel)
  GalaxyService.initialize()
    → GET /players/{id}          (profil + crédits)
    → PUT /players/{id}          (enregistre callback_url)
    → GET /systems + /planets    (scan parallèle de toute la galaxie)
    → trouve notre station → stocke dans AppState
  MarketService.initPrices()
    → GET /market/prices         (cache de prix initial)
  ShipService.syncActiveShips()
    → GET /ships                 (ships déjà en vol au redémarrage)
  ElevatorService.initExportDemands()
    → POST /trade                (crée les demandes d'import/export initiales)
  ElevatorService.checkAndTransferToOrbit()
    → POST /space-elevator/transfer  (premier transfert bloquant au démarrage)

Runtime (4 boucles parallèles)
  ┌─ SSE Stream ──────────────────────────────────────────────────┐
  │  MarketClient.streamTrades() → state.updatePrice()            │
  │  Pattern #4 : retryWhen + onBackpressureBuffer(500)           │
  └───────────────────────────────────────────────────────────────┘
  ┌─ Polling Ships (toutes les 4s) ───────────────────────────────┐
  │  ShipClient.getShip(id) × N ships → state.updateShip()       │
  │  Pattern #3 : Flux.interval + flatMap parallèle               │
  └───────────────────────────────────────────────────────────────┘
  ┌─ Trading Strategy (toutes les 20s) ───────────────────────────┐
  │  Mono.zip(getInventory, getOpenOrders)                        │
  │    → sellGoodsWeHave() + cancelOldOrders() [parallèle]       │
  │  Pattern #1 : REST sync non-bloquant                          │
  └───────────────────────────────────────────────────────────────┘
  ┌─ Space Elevator check (toutes les 60s) ───────────────────────┐
  │  ElevatorService.checkAndTransferToOrbit()                    │
  │    → POST /space-elevator/transfer (connexion tenue ~5s)      │
  │  Pattern #2 : subscribeOn(Schedulers.boundedElastic())        │
  └───────────────────────────────────────────────────────────────┘
  ┌─ Webhook Server (HTTP entrant) ───────────────────────────────┐
  │  POST /webhooks → dispatch → ShipService.handleWebhookEvent() │
  │  Pattern #5 : réponse immédiate + traitement async            │
  └───────────────────────────────────────────────────────────────┘
```

---

## Gestion des erreurs et résilience

| Situation | Mécanisme |
|---|---|
| Serveur injoignable au démarrage | `onErrorResume` → continue sans planter |
| Erreur réseau dans le polling | `.retry()` → relance automatiquement |
| Erreur dans un tick de stratégie | `onErrorResume` → on skip le tick, la boucle continue |
| SSE déconnecté | `retryWhen(Retry.backoff(...))` dans MarketClient |
| Appel trop long | `.timeout(Duration.ofSeconds(N))` sur chaque client |
