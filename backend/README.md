# Offworld Bot Client

Client Java réactif pour interagir avec le serveur **Offworld Trading Manager**.

## Librairie choisie : Spring WebFlux / Project Reactor

On a choisi **Project Reactor** via **Spring WebFlux** parce que :
- On a déjà travaillé avec dans le TP1 (practical-reactor)
- Le `WebClient` de Spring est natif Reactor, pas besoin d'adaptateur
- Spring Boot gère l'injection de dépendances et le serveur webhook (Netty) automatiquement
- La doc est bonne et les opérateurs couvrent tous les patterns demandés

## Prérequis

- Java 21
- Maven 3.6+
- Rust (pour compiler le serveur du prof) — `curl https://sh.rustup.rs -sSf | sh`
- Le serveur `offworld-trading-manager` qui tourne (voir section suivante): https://github.com/arendsyl/offworld-trading-manager

## Lancer le projet — étape par étape

### Étape 1 : Lancer le serveur du prof

Le serveur est fourni par l'enseignant (repo `offworld-trading-manager`).

```bash
# Aller dans le dossier du serveur
cd offworld-trading-manager

# Compiler (une seule fois, ~1-2 min)
cargo build

# Lancer avec le fichier de données
./target/debug/offworld-trading-manager --seed seed.json
```

Le serveur démarre sur **`http://localhost:3000`**. Vérifier qu'il tourne :

```bash
curl -s -H "Authorization: Bearer alpha-secret-key-001" http://localhost:3000/systems | head -c 100
# → doit retourner un tableau JSON de systèmes stellaires
```

> Si vous voyez `Address already in use` : `pkill -f offworld-trading-manager` puis relancer.

### Étape 2 : Récupérer vos credentials

Ouvrir le fichier `seed.json` du serveur et noter votre `id` et `api_key` :

```json
"players": [
  { "id": "alpha-team", "api_key": "alpha-secret-key-001", ... }
]
```

### Étape 3 : Configurer le bot

Modifier `src/main/resources/application.yml` avec vos valeurs :

```yaml
offworld:
  server-url: http://localhost:3000
  player-id: "alpha-team"           # ← votre id du seed.json
  api-key: "alpha-secret-key-001"   # ← votre api_key du seed.json
  webhook-url: "http://localhost:8081/webhooks"
  ship-polling-interval-ms: 4000
  strategy-interval-ms: 20000

server:
  port: 8081
```

> Si le serveur tourne sur une autre machine, remplacer `localhost:3000` par l'IP de la machine.
> Si le serveur doit accéder à vos webhooks depuis l'extérieur, utiliser ngrok :
> `ngrok http 8081` et renseigner l'URL ngrok dans `webhook-url`.

### Étape 4 : Lancer le bot

Dans un **nouveau terminal** (le serveur doit déjà tourner) :

```bash
cd offworld-bot-client
mvn spring-boot:run
```

Vous devriez voir dans les logs :

```
=== Démarrage du bot Offworld | serveur=http://localhost:3000 joueur=alpha-team ===
Webhook URL enregistrée: http://localhost:8081/webhooks
Galaxie scannée: N systèmes, notre station: <nom de la station>
Initialisation terminée — lancement des boucles réactives
[SSE #1/1e] Trade marché : 30× water @ 3 crédits | ...
[TICK] Inventaire station: ...u stockées | N ordres ouverts
```

Le bot tourne maintenant en autonome. Pour arrêter : `Ctrl+C`.

### Lancer les tests

```bash
mvn test
# → 64 tests, BUILD SUCCESS
```

## Ce que fait le bot

1. **Au démarrage** : scan de la galaxie, trouve notre station, charge les prix du marché, enregistre l'URL de webhook, crée les export demands via l'ascenseur spatial
2. **Stream SSE** (`GET /market/trades`) : reçoit tous les trades en temps réel (`Flux<ServerSentEvent>`) et met à jour le cache de prix
3. **Boucle de stratégie** (toutes les 20s) : place des ordres buy/sell sur le marché, et envoie un ship de trucking si le stock dépasse le seuil
4. **Ascenseur spatial** (toutes les 60s, thread bloquant) : transfère les marchandises depuis la surface vers la station orbitale
5. **Webhook server** (`POST /webhooks`) : le serveur notifie les événements ships → autorisation docking/undocking
6. **Polling ships** (toutes les 4s) : vérifie l'état des ships actifs, gère les transitions de statut

## Structure du projet

```
src/main/java/com/offworld/
├── OffworldApplication.java      -- Main + lancement des boucles réactives
├── AppState.java                 -- État partagé thread-safe (ConcurrentHashMap)
├── config/
│   ├── AppConfig.java            -- Binding application.yml
│   └── WebClientConfig.java      -- WebClient avec auth Bearer
├── model/                        -- POJOs / records Jackson
│   ├── webhook/                  -- Events webhook (sealed interfaces Java 21)
├── client/                       -- Couche HTTP (un par domaine API)
│   ├── GalaxyClient.java
│   ├── PlayerClient.java
│   ├── StationClient.java        -- dont transfer elevator (bloquant)
│   ├── MarketClient.java         -- dont stream SSE
│   ├── ShipClient.java
│   ├── ConstructionClient.java
│   └── TradeClient.java
├── service/
│   ├── GalaxyService.java        -- Exploration + init
│   ├── ShipService.java          -- Lifecycle ships (webhooks + polling)
│   ├── MarketService.java        -- SSE stream + init prix
│   └── TradingStrategy.java      -- Boucle de décision automatique
└── webhook/
    └── WebhookController.java    -- Endpoint POST /webhooks
```

---

## Architecture réactive

### Vue d'ensemble

Le bot suit une architecture **réactive non-bloquante** basée sur Project Reactor. Toutes les interactions réseau sont des `Mono` ou `Flux`, et les boucles de décision s'appuient sur `Flux.interval()` pour cadencer les ticks sans bloquer de thread.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Serveur de jeu (port 3000)                       │
│  GET /systems  GET /market/prices  GET /ships  POST /trade  GET /market/trades (SSE) │
└────────┬──────────────────────┬──────────────┬────────────┬─────────────┘
         │ Mono<T>              │ Flux<SSE>    │ Mono<T>    │ Mono<T>
         ▼                      ▼              ▼            ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          Couche Client (WebClient)                      │
│  GalaxyClient  MarketClient  ShipClient  TradeClient  StationClient ... │
└────────┬──────────────────────┬──────────────┬────────────┬────────────┘
         │                      │              │            │
         ▼                      ▼              ▼            ▼
┌────────────────────────────────────────────────────────────────────────┐
│                            Couche Service                               │
│  GalaxyService   MarketService   ShipService   TradingStrategy  Elevator│
│  (init sync)     (SSE stream)    (polling 4s)  (polling 20s)   (60s)   │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │ lecture/écriture
                                 ▼
                        ┌─────────────────┐
                        │   AppState      │
                        │ ConcurrentHashMap│
                        │ (état partagé)  │
                        └────────┬────────┘
                                 │ lecture
                                 ▼
                     ┌───────────────────────┐
                     │  WebhookController    │
                     │  POST /webhooks       │
                     │  (Netty, port 8081)   │
                     └───────────────────────┘
                               ▲
                               │ notifications push
                     ┌─────────────────────┐
                     │  Serveur de jeu     │
                     │  (ship events)      │
                     └─────────────────────┘
```

---

### Flux de données par mode d'interaction

#### 1. Initialisation synchrone (démarrage)

Au démarrage, `OffworldApplication` enchaîne une séquence bloquante de `Mono` pour initialiser l'état avant de lancer les boucles :

```
OffworldApplication.run()
  └─ GalaxyService.init()           Mono<Void>  → scan /systems, trouve notre station
  └─ MarketService.initPrices()     Mono<Void>  → charge /market/prices
  └─ GalaxyService.registerWebhook()Mono<Void>  → POST /players/{id}/webhook
  └─ ElevatorService.initElevator() Mono<Void>  → crée les export demands
  └─ lancement des boucles réactives (non-bloquant)
```

Chaque étape est un `Mono<Void>` enchaîné par `.then()`. Le `.block()` final attend que toute la chaîne soit complète avant de rendre la main à Spring.

---

#### 2. Stream SSE — Marché en temps réel

```
MarketClient.streamTrades()
  └─ WebClient GET /market/trades
       └─ Flux<ServerSentEvent<String>>
            └─ .flatMap(sse → parse JSON)
                 └─ Flux<Trade>
                      └─ MarketService.handleTrade(trade)
                           └─ AppState.updatePrice(good, price)
```

Pattern utilisé : **`Flux` infini** avec `retryWhen(Retry.backoff(...))` pour reconnecter automatiquement si le stream SSE se coupe.

---

#### 3. Polling périodique — Ships (toutes les 4 secondes)

```
Flux.interval(Duration.ofMillis(4000))
  └─ .flatMap(_ → ShipClient.getMyShips())   Mono<List<Ship>>
       └─ ShipService.processShips(ships)
            └─ AppState.updateShips(ships)
            └─ si ship DOCKED → orchestrer docking / planifier prochain départ
```

Pattern utilisé : **`Flux.interval()` + `flatMap`** pour garder un polling non-bloquant. `flatMap` (et non `concatMap`) permet de ne pas accumuler de délai si une requête est lente.

---

#### 4. Polling périodique — Stratégie de trading (toutes les 20 secondes)

```
Flux.interval(Duration.ofMillis(20000))
  └─ .flatMap(_ → TradingStrategy.tick())
       └─ lit AppState (prix, inventaire, ordres)
       └─ TradeClient.placeOrder(buy/sell)    Mono<Order>
       └─ ShipClient.sendShip(route)           Mono<Ship>
```

Pattern utilisé : **`Flux.interval()` + `flatMap` + `Mono` chaînés** pour composer des décisions multi-étapes de façon réactive.

---

#### 5. Webhooks — Événements ships (push)

Le serveur envoie des événements `POST /webhooks` quand un ship arrive ou repart.

```
Serveur de jeu
  └─ POST http://localhost:8081/webhooks  { "type": "ship_docked", ... }
       └─ WebhookController.handleEvent(event)   Mono<ResponseEntity>
            └─ switch(event.type)
                 ├─ SHIP_DOCKED    → ShipService.authorizeDocking(shipId)
                 └─ SHIP_UNDOCKED  → ShipService.authorizeUndocking(shipId)
```

Pattern utilisé : **handler réactif Spring WebFlux** — `@PostMapping` retourne un `Mono<ResponseEntity>`, Spring Netty traite la requête sans bloquer. Les `sealed interfaces` Java 21 rendent le pattern matching exhaustif et sûr.

---

#### 6. Ascenseur spatial (toutes les 60 secondes, thread dédié)

L'API de l'ascenseur est synchrone côté serveur (délai artificiel ~2s). Elle est isolée dans un thread bloquant via `Schedulers.boundedElastic()` pour ne pas bloquer le thread réactif :

```
Flux.interval(Duration.ofSeconds(60))
  └─ .flatMap(_ → Mono.fromCallable(() → StationClient.transferElevator())
                       .subscribeOn(Schedulers.boundedElastic()))
```

Pattern utilisé : **`Mono.fromCallable()` + `subscribeOn(boundedElastic)`** — isolation du code bloquant dans un pool de threads dédié, sans contaminer le scheduler NIO.

---

### Résumé des patterns réactifs utilisés

| Mode              | Pattern Reactor                          | Classe(s) concernée(s)              |
|-------------------|------------------------------------------|--------------------------------------|
| Init synchrone    | `Mono` chaîné par `.then()` + `.block()` | `OffworldApplication`, `GalaxyService` |
| SSE temps réel    | `Flux<ServerSentEvent>` + `retryWhen`    | `MarketClient`, `MarketService`      |
| Polling ships     | `Flux.interval()` + `flatMap`            | `ShipService`                        |
| Stratégie trading | `Flux.interval()` + `flatMap` + `Mono`   | `TradingStrategy`                    |
| Webhooks push     | `@PostMapping` → `Mono<ResponseEntity>`  | `WebhookController`                  |
| Appel bloquant    | `Mono.fromCallable()` + `boundedElastic` | `ElevatorService`, `StationClient`   |
