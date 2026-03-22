# Offworld Bot Client

Client Java réactif pour automatiser le trading sur le serveur Offworld Trading Manager.

## Librairie réactive choisie

Nous avons choisi **Project Reactor** via **Spring WebFlux**.

Pourquoi :

- `WebClient` est nativement réactif et couvre tous les appels HTTP du projet
- Reactor fournit directement `Mono`, `Flux`, `flatMap`, `zip`, `retry`, `timeout` et `Flux.interval`
- Spring Boot simplifie le serveur webhook et l'injection de dépendances
- la pile couvre tous les patterns demandés : sync non bloquant, polling, SSE et webhooks

## Build

```bash
cd backend
mvn test
```

## Configuration

Fichier principal : `src/main/resources/application.yml`

```yaml
offworld:
  server-url: http://localhost:3000
  player-id: "alpha-team"
  api-key: "alpha-secret-key-001"
  webhook-url: "http://localhost:8081/webhooks"
  ship-polling-interval-ms: 4000
  strategy-interval-ms: 20000

server:
  port: 8081
```

## Exécution

### 1. Démarrer le serveur de jeu

Depuis `server/` :

```bash
cargo run -- --seed seed.json
```

### 2. Démarrer le bot

Depuis `backend/` :

```bash
mvn spring-boot:run
```

## Ce que fait l'application

- charge la galaxie et les prix au démarrage
- enregistre l'URL de webhook du joueur
- écoute le flux SSE du marché
- poll l'état des vaisseaux
- exécute une boucle de stratégie périodique
- traite les événements push via `POST /webhooks`

## Pipeline réactif

```mermaid
flowchart TD
    A[OffworldApplication] --> B[Init Mono chain]
    B --> C[GalaxyService]
    B --> D[MarketService]
    B --> E[Webhook registration]
    A --> F[SSE market stream]
    A --> G[Polling ships]
    A --> H[Trading strategy]
    I[WebhookController] --> J[ShipService]
    F --> K[AppState]
    G --> K
    H --> K
    J --> K
```

## Architecture

Le document d'architecture court est dans `ARCHITECTURE.md`.

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
