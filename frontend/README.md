# Offworld Pixel Ops — Dashboard React

Dashboard React en style rétro 8-bit qui visualise l'écosystème du serveur Offworld comme un écran tactique de jeu.

## Fonctionnalités

- Carte galactique pseudo-3D avec planètes et marqueurs de stations
- Flux de trades en direct via SSE (`/market/trades`) avec animation
- Panneau d'activité centré sur le bot (achats/ventes du joueur)
- Suivi des crédits et du classement par profit
- Panneaux ordres ouverts, ships actifs, et demandes de commerce

## Prérequis

- Node.js 18+
- Le serveur de jeu `offworld-trading-manager` qui tourne sur `http://localhost:3000`
- (Optionnel) Le bot backend Java qui tourne sur `http://localhost:8081` pour voir les ordres automatisés

## Installation et lancement

```bash
cd frontend
npm install
npm run dev
```

Ouvrir l'URL affichée par Vite (généralement `http://localhost:5173`).

### Autres commandes

```bash
npm run build    # Compilation production dans dist/
npm run preview  # Prévisualiser le build de production localement
```

## Configuration

Le fichier `vite.config.js` configure un proxy de développement :

```
/api  →  http://localhost:3000  (préfixe /api supprimé)
```

Toutes les requêtes vers `/api/systems`, `/api/market/prices`, etc. sont redirigées vers le serveur de jeu sans exposer l'URL directement dans le code React.

Au démarrage, le panneau de connexion de l'interface permet de saisir :
- L'URL du serveur (par défaut `http://localhost:3000`)
- Votre `player-id` (ex. `alpha-team`)
- Votre `api-key` (ex. `alpha-secret-key-001`)

## Assets

Tous les assets graphiques sont dans `public/images/`. Les sprites sont des placeholders pixel-art :

| Fichier             | Usage                |
|---------------------|----------------------|
| `space.png`         | Fond d'écran spatial |
| `planet_telluric.png` | Planètes telluriques |
| `planet_gas.png`    | Géantes gazeuses     |
| `station.png`       | Stations orbitales   |
| `ship.png`          | Vaisseaux            |
| `water.png`, `food.png`, `iron_ore.png`, `copper_ore.png`, `silicon.png` | Icônes de ressources |

---

## Architecture réactive du frontend

### Vue d'ensemble

Le frontend utilise les **API natives du navigateur** pour la réactivité : `EventSource` / `fetch` + `ReadableStream` pour le SSE, et `setInterval` pour le polling. Pas de librairie réactive tierce — React + hooks suffisent pour gérer l'état et déclencher les re-renders.

```
┌─────────────────────────────────────────────────────────────┐
│               Serveur de jeu (port 3000)                    │
│  GET /systems  GET /market/prices  GET /market/trades (SSE) │
│  GET /players/{id}  GET /ships  GET /market/orders  ...     │
└────────┬──────────────────────────┬──────────────────────────┘
         │ fetch + JSON             │ SSE (ReadableStream)
         │ (polling setInterval)    │ (flux continu)
         ▼                         ▼
┌────────────────────────────────────────────────────────────┐
│                  App.jsx  (React + hooks)                   │
│                                                            │
│  useState / useRef                                         │
│  ├─ galaxy, ships, orders, prices, myTrades, ranking ...   │
│                                                            │
│  useEffect (démarrage)                                     │
│  ├─ connectSSE()        → flux SSE /market/trades          │
│  ├─ startPublicPolling()→ setInterval 10s (public data)    │
│  └─ startPrivatePolling()→ setInterval 5s (private data)   │
│                                                            │
│  Render                                                    │
│  ├─ Galaxy Tactical View (carte)                           │
│  ├─ Trade Feed (SSE live)                                  │
│  └─ HUD Tabs (inventaire, ordres, marché, fleet, ranking)  │
└────────────────────────────────────────────────────────────┘
```

---

### Modes d'interaction

#### 1. Initialisation synchrone (au clic "Connect")

Quand l'utilisateur clique sur "Connect", une série de `fetch` est lancée en séquence pour charger l'état initial :

```
handleConnect()
  └─ fetch /systems                 → galaxyData (planètes + stations)
  └─ fetch /market/prices           → prixInitiaux
  └─ fetch /players/{playerId}      → infoJoueur (crédits, inventaire)
  └─ connectSSE()                   → ouvre le stream SSE
  └─ startPublicPolling()           → démarre polling 10s
  └─ startPrivatePolling()          → démarre polling 5s
```

---

#### 2. Stream SSE — Flux de trades en temps réel

```
fetch('/api/market/trades', { headers: { Authorization: ... } })
  └─ response.body.getReader()          ReadableStream
       └─ lecture ligne par ligne
            └─ parse "data: {...}" JSON
                 └─ setTradeFeed(prev => [trade, ...prev].slice(0, 50))
                      └─ React re-render → Trade Feed UI mis à jour
```

Le stream reste ouvert en permanence. Si la connexion se coupe, une tentative de reconnexion est déclenchée via `setTimeout`.

---

#### 3. Polling public — Données partagées (toutes les 10 secondes)

```
setInterval(10 000 ms)
  └─ fetch /systems            → mise à jour galaxyData
  └─ fetch /market/prices      → mise à jour des prix
  └─ fetch /leaderboard        → mise à jour du classement
```

Ces données sont publiques (pas d'authentification requise) et changent lentement — 10 secondes est un compromis raisonnable entre fraîcheur et charge réseau.

---

#### 4. Polling privé — Données du joueur (toutes les 5 secondes)

```
setInterval(5 000 ms)
  └─ fetch /players/{playerId}         → crédits, inventaire station
  └─ fetch /market/orders?status=open  → ordres en cours
  └─ fetch /ships                      → flotte active
  └─ fetch /trade                      → historique des trades du joueur
```

Ces endpoints nécessitent le header `Authorization: Bearer {apiKey}`. Le polling plus fréquent (5s) reflète le fait que l'état du joueur change plus vite (ordres exécutés, ships qui bougent).

---

### Compatibilité des champs API — `readField()`

Le serveur peut renvoyer des champs en `snake_case` (`good_id`) ou `camelCase` (`goodId`) selon la version. Le helper `readField` résout la clé disponible :

```js
function readField(obj, ...keys) {
  if (!obj) return undefined;
  for (const key of keys) {
    if (obj[key] !== undefined && obj[key] !== null) return obj[key];
  }
  return undefined;
}

// Usage
const goodId = readField(trade, 'good_id', 'goodId');
const price  = readField(order, 'price_per_unit', 'pricePerUnit', 'price');
```

---

### Résumé des patterns frontend

| Mode                  | Mécanisme navigateur                  | Fréquence       | Données                        |
|-----------------------|---------------------------------------|-----------------|--------------------------------|
| Init connexion        | `fetch` séquentiel                    | Une fois        | Galaxie, prix, profil joueur   |
| Stream trades live    | `fetch` + `ReadableStream` (SSE)      | Continu (push)  | Tous les trades du marché      |
| Polling public        | `setInterval` + `fetch`               | Toutes les 10s  | Systèmes, prix, classement     |
| Polling privé         | `setInterval` + `fetch` + Auth header | Toutes les 5s   | Crédits, ordres, ships, trades |
