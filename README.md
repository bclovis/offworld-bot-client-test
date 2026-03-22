# Offworld Bot Client Test

Projet Offworld full-stack composé de trois parties :

- un serveur de jeu Rust qui simule la galaxie partagée, le marché, les vaisseaux, la construction et les échanges
- un bot Java réactif qui automatise les décisions de trading contre ce serveur
- un tableau de bord React qui visualise la galaxie et l'activité du marché en temps réel

Le dépôt est organisé pour permettre une exécution locale complète : démarrer le serveur, connecter le bot Java dessus, puis ouvrir éventuellement le dashboard React pour observer l'état du jeu en direct.

## Vue d'ensemble du projet

Cet espace de travail réunit toute la stack Offworld :

- `server/` : le serveur multijoueur construit avec Rust, Axum et Tokio
- `backend/` : le bot réactif Java 21 construit avec Spring Boot WebFlux et Project Reactor
- `frontend/` : le dashboard React 19 + Vite pour l'exploration et le monitoring

À l'exécution, les composants interagissent ainsi :

1. Le serveur Rust expose l'API REST, le flux SSE du marché et les mécaniques pilotées par webhooks.
2. Le bot Java se connecte au serveur, consomme les données de marché, gère les vaisseaux et place automatiquement des ordres.
3. Le frontend React se connecte au même serveur et affiche la galaxie, les trades, le classement et l'état du joueur.

## Résumé de l'architecture

### `server/` Serveur de jeu Rust

Responsable de :

- l'état de la galaxie et des joueurs
- les ordres de marché et le flux de trades en direct (`/market/trades`)
- le cycle de vie des vaisseaux et les notifications webhook
- les systèmes de construction et de demandes commerciales
- la configuration via `config.toml`, variables d'environnement ou options CLI

Stack principale :

- Rust édition 2024
- Axum
- Tokio
- Serde
- intégration Apache Pulsar pour les flux mass-driver

### `backend/` Bot Java réactif

Responsable de :

- initialiser l'état du joueur et scanner la galaxie
- s'abonner aux événements de marché en direct via SSE
- exécuter des boucles périodiques de stratégie et de polling des vaisseaux
- recevoir les callbacks webhook sur son propre endpoint HTTP
- coordonner le trading, le trucking et les opérations d'ascenseur spatial

Stack principale :

- Java 21
- Spring Boot 3.2
- Spring WebFlux
- Project Reactor
- Maven

### `frontend/` Dashboard React

Responsable de :

- visualiser la galaxie et la disposition des stations
- afficher les trades du marché dans un flux en direct
- montrer les crédits du joueur, les ordres, les vaisseaux, le classement et l'activité
- interroger régulièrement les endpoints API publics et privés pour l'état courant

Stack principale :

- React 19
- Vite 8
- ESLint 9

## Structure du dépôt

```text
.
├── backend/   # Bot client Java réactif
├── frontend/  # Dashboard React
├── server/    # Offworld Trading Manager en Rust
├── default.png
├── space.png
└── Aldrich-Regular.ttf
```

## Prérequis

Installez les éléments suivants avant d'exécuter le projet complet :

- Java 21
- Maven 3.6+
- Node.js 18+
- npm
- toolchain Rust (`cargo`)

Optionnel mais utile :

- Docker, si vous voulez lancer les tests d'intégration Rust qui utilisent `testcontainers`
- ngrok ou un tunnel équivalent si le webhook du bot Java doit être accessible depuis l'extérieur de votre machine

## Ports par défaut

- serveur Rust : `3000`
- serveur webhook du bot Java : `8081`
- serveur de développement du dashboard React : `5173`

## Démarrage rapide

### 1. Démarrer le serveur Rust

```bash
cd server
cargo run -- --seed seed.json
```

Par défaut, l'API est disponible sur `http://localhost:3000`.

Si vous voulez simplement lancer le serveur sans surcharge du seed :

```bash
cd server
cargo run
```

### 2. Configurer le bot Java

Modifiez `backend/src/main/resources/application.yml` pour que le bot utilise un joueur valide provenant des données seed du serveur :

```yaml
offworld:
  server-url: http://localhost:3000
  player-id: "alpha-team"
  api-key: "alpha-secret-key-001"
  webhook-url: "http://localhost:8081/webhooks"

server:
  port: 8081
```

Les valeurs par défaut ciblent déjà le serveur Rust local et les identifiants d'exemple `alpha-team` présents dans `server/seed.json`.

### 3. Démarrer le bot Java

```bash
cd backend
mvn spring-boot:run
```

Le bot va :

- initialiser l'état du joueur et de la galaxie
- enregistrer son URL de webhook
- charger les prix du marché
- s'abonner au flux SSE du marché
- lancer les boucles automatisées de trading et de gestion des vaisseaux

### 4. Démarrer le dashboard React

```bash
cd frontend
npm install
npm run dev
```

Ouvrez l'URL affichée par Vite, généralement `http://localhost:5173`.

Le serveur de développement redirige les requêtes `/api/*` vers `http://localhost:3000`.

## Ordre de lancement recommandé

Pour le workflow local complet :

1. Démarrer `server/`
2. Démarrer `backend/`
3. Démarrer `frontend/`
4. Ouvrir le dashboard et se connecter avec les mêmes identifiants joueur que ceux utilisés par le bot

Si le serveur n'est pas lancé en premier, le backend et le frontend ne pourront pas charger les données.

## Notes de configuration

### Serveur Rust

Sources de configuration, par ordre de priorité décroissant :

1. options CLI
2. variables d'environnement
3. `config.toml`

Paramètres courants :

- `PORT`
- `ADMIN_TOKEN`
- `PULSAR_URL`
- `BISCUIT_PRIVATE_KEY`

### Bot Java

Les principaux réglages d'exécution se trouvent dans `backend/src/main/resources/application.yml` :

- `offworld.server-url`
- `offworld.player-id`
- `offworld.api-key`
- `offworld.webhook-url`
- `offworld.ship-polling-interval-ms`
- `offworld.strategy-interval-ms`

### Frontend React

Le frontend s'appuie actuellement sur le proxy Vite configuré dans `frontend/vite.config.js` :

- `/api/*` -> `http://localhost:3000`

L'interface demande elle-même l'URL du serveur, le `player-id` et l'`api-key` au moment de la connexion.

## Commandes de développement

### Serveur

```bash
cd server
cargo build
cargo test
```

### Backend

```bash
cd backend
mvn test
mvn spring-boot:run
```

### Frontend

```bash
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

## Tests

Chaque module se teste indépendamment :

- `server/` : `cargo test`
- `backend/` : `mvn test`
- `frontend/` : `npm run lint` et `npm run build`

Certains tests d'intégration Rust nécessitent Docker car ils utilisent `testcontainers`.

## Carte de la documentation

Utilisez les documents spécifiques à chaque module quand vous avez besoin de détails d'implémentation :

- `backend/README.md` : installation du backend, comportement et usage réactif
- `backend/ARCHITECTURE.md` : architecture réactive Java détaillée et patterns d'interaction
- `server/README.md` : démarrage rapide et configuration du serveur Rust
- `server/ASSIGNMENT.md` : contexte du sujet et exigences réactives visées pour le client Java
- `server/docs/` : référence API endpoint par endpoint
- `frontend/README.md` : fonctionnalités du dashboard, utilisation et flux de données frontend

## Cas d'usage typiques

### Exécuter la démo complète en local

- démarrer le serveur Rust avec `seed.json`
- lancer le bot Java avec des identifiants correspondants
- ouvrir le dashboard React pour observer l'activité automatisée

### Travailler uniquement sur le bot

- démarrer le serveur Rust
- lancer le backend Java
- utiliser l'API et les logs sans démarrer le frontend

### Travailler uniquement sur le dashboard

- démarrer le serveur Rust
- lancer le frontend React
- se connecter manuellement avec un identifiant joueur et une clé API

## Notes

- Le backend et le frontend s'attendent tous deux à ce que le serveur de jeu soit accessible sur `http://localhost:3000` sauf reconfiguration.
- Le bot Java expose par défaut un endpoint webhook sur `http://localhost:8081/webhooks`.
- Les assets image/police situés à la racine sont des ressources du dépôt, mais les points d'entrée exécutables se trouvent dans `server/`, `backend/` et `frontend/`.