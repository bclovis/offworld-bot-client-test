import { useEffect, useMemo, useRef, useState } from "react";
import "./App.css";

const goodsWithIcons = ["food", "water", "iron_ore", "copper_ore", "silicon"];

const defaultConfig = {
  serverUrl: "/api",
  playerId: "alpha-team",
  apiKey: "alpha-secret-key-001"
};

const hudTabs = [
  { key: "inventory", label: "Inventory", icon: "/images/tab_inventory.png" },
  { key: "orders", label: "Orders", icon: "/images/tab_orders.png" },
  { key: "market", label: "Market", icon: "/images/tab_market.png" },
  { key: "fleet", label: "Fleet", icon: "/images/tab_fleet.png" },
  { key: "build", label: "Build", icon: "/images/station.png" },
  { key: "ranking", label: "Ranking", icon: "/images/tab_ranking.png" }
];

const planetImageById = {
  "Sol-1": "/images/planets/sol-1.png",
  "Sol-2": "/images/planets/sol-2.png",
  "Sol-3": "/images/planets/sol-3.png",
  "Sol-4": "/images/planets/sol-4.png",
  "Sol-5": "/images/planets/sol-5.png",
  "Sol-6": "/images/planets/sol-6.png",
  "Proxima Centauri-1": "/images/planets/proxima-centauri-1.png",
  "Sirius-1": "/images/planets/sirius-1.png"
};

function App() {
  const [config, setConfig] = useState(defaultConfig);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState("");
  const [systems, setSystems] = useState([]);
  const [leaderboard, setLeaderboard] = useState([]);
  const [prices, setPrices] = useState({});
  const [profile, setProfile] = useState(null);
  const [orders, setOrders] = useState([]);
  const [ships, setShips] = useState([]);
  const [tradeRequests, setTradeRequests] = useState([]);
  const [recentTrades, setRecentTrades] = useState([]);
  const [constructionProjects, setConstructionProjects] = useState([]);
  const [creditDelta, setCreditDelta] = useState(null);
  const prevCreditsRef = useRef(null);
  const [syncOk, setSyncOk] = useState(false);
  const [pollingOk, setPollingOk] = useState(false);
  const [sseOk, setSseOk] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [activeHudView, setActiveHudView] = useState("inventory");
  const [selectedPlanetId, setSelectedPlanetId] = useState(null);

  // Additional health checks
  const [botBackendOk, setBotBackendOk] = useState(false);
  const [serverLag, setServerLag] = useState(0);
  const [marketActivity, setMarketActivity] = useState(false);
  const [dataFreshness, setDataFreshness] = useState(0); // seconds ago
  const [myRank, setMyRank] = useState(null);
  const [prevRank, setPrevRank] = useState(null);

  const eventSourceRef = useRef(null);
  const loopsRef = useRef([]);
  const tradeLayerRef = useRef(null);
  const autoConnectRef = useRef(false);
  const reconnectTimeoutRef = useRef(null);
  const errorTimeoutRef = useRef(null);
  const lastDataUpdateRef = useRef(Date.now());
  const lastTradeTimeRef = useRef(Date.now());

  useEffect(() => {
    const saved = localStorage.getItem("offworld-ui-config");
    if (!saved) {
      return;
    }

    try {
      setConfig((prev) => ({ ...prev, ...JSON.parse(saved) }));
    } catch {
      // ignore invalid local config
    }
  }, []);

  useEffect(() => {
    if (autoConnectRef.current) {
      return;
    }

    if (!config.playerId || !config.apiKey) {
      return;
    }

    autoConnectRef.current = true;
    connect(true);
  }, [config.playerId, config.apiKey, config.serverUrl]);

  useEffect(() => {
    return () => {
      teardown();
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (errorTimeoutRef.current) {
        clearTimeout(errorTimeoutRef.current);
      }
    };
  }, []);

  // Monitor health checks
  useEffect(() => {
    const healthCheckInterval = setInterval(async () => {
      // Check bot backend
      try {
        const botStart = Date.now();
        const botRes = await fetch("/bot/construction");
        const botLag = Date.now() - botStart;
        setBotBackendOk(botRes.ok);
      } catch {
        setBotBackendOk(false);
      }

      // Update data freshness
      const timeSinceUpdate = Math.round((Date.now() - lastDataUpdateRef.current) / 1000);
      setDataFreshness(timeSinceUpdate);

      // Check market activity (trades in last 30 seconds)
      const now = Date.now();
      const recentTradeActivity = (now - lastTradeTimeRef.current) < 30000;
      setMarketActivity(recentTradeActivity);
    }, 2000);

    return () => clearInterval(healthCheckInterval);
  }, []);

  // Track server lag on each API call
  const measureServerLag = async (runtimeConfig, path) => {
    const start = Date.now();
    const headers = { "Content-Type": "application/json" };
    if (runtimeConfig.apiKey) {
      headers.Authorization = `Bearer ${runtimeConfig.apiKey}`;
    }
    const res = await fetch(`${runtimeConfig.serverUrl}${path}`, { headers });
    const lag = Date.now() - start;
    setServerLag(lag);
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`${res.status} ${res.statusText} ${body}`.trim());
    }
    return res.json();
  };

  const planetCoords = useMemo(() => {
    const map = new Map();
    const planets = systems.flatMap((system) =>
      (system.planets || []).map((planet) => ({
        id: planet.id,
        key: `${system.name}-${planet.id}`
      }))
    );
    const total = planets.length || 1;
    const goldenAngle = Math.PI * (3 - Math.sqrt(5));

    planets
      .sort((a, b) => a.key.localeCompare(b.key))
      .forEach((planet, index) => {
        const r = Math.sqrt((index + 0.5) / total);
        const theta = index * goldenAngle + (hash(planet.key) % 15) * 0.01;
        const x = 47 + Math.cos(theta) * r * 48;
        const y = 30 + Math.sin(theta) * r * 42;
        const z = (hash(planet.id) % 60) - 24;

        map.set(planet.id, {
          x: clamp(x, 8, 88),
          y: clamp(y, 6, 74),
          z
        });
      });

    return map;
  }, [systems]);

  const myTrades = useMemo(() => {
    return recentTrades
      .filter((t) => readField(t, "buyer_id", "buyerId") === config.playerId || readField(t, "seller_id", "sellerId") === config.playerId)
      .slice(0, 10);
  }, [recentTrades, config.playerId]);

  const myProfit = useMemo(() => {
    const me = leaderboard.find((p) => readField(p, "player_id", "playerId") === config.playerId);
    return readField(me, "profit", "total_profit") ?? 0;
  }, [leaderboard, config.playerId]);

  const planetInventories = useMemo(() => {
    return systems.flatMap((system) =>
      (system.planets || [])
        .map((planet) => {
          const station = planet.status?.station || planet.station;
          const elevator = planet.status?.space_elevator || planet.space_elevator;
          const stationInventory = station?.inventory || {};
          const warehouseInventory = elevator?.warehouse?.inventory || {};
          const hasInventory = Object.keys(stationInventory).length > 0 || Object.keys(warehouseInventory).length > 0;
          const hasStorage = Boolean(station || elevator);

          if (!hasInventory && !hasStorage) {
            return null;
          }

          return {
            systemName: system.name,
            planetId: planet.id,
            planetName: planet.name,
            hasStorage,
            stationInventory,
            warehouseInventory
          };
        })
        .filter(Boolean)
    );
  }, [systems]);

  const inventoryManifest = useMemo(() => {
    const totals = new Map();

    const locations = planetInventories
      .map((entry) => {
        const stationItems = Object.entries(entry.stationInventory || {}).map(([good, qty]) => ({
          source: "station",
          good,
          qty
        }));

        const warehouseItems = Object.entries(entry.warehouseInventory || {}).map(([good, qty]) => ({
          source: "warehouse",
          good,
          qty
        }));

        for (const item of [...stationItems, ...warehouseItems]) {
          totals.set(item.good, (totals.get(item.good) || 0) + item.qty);
        }

        const stationByGood = new Map(stationItems.map((item) => [item.good, item.qty]));
        const warehouseByGood = new Map(warehouseItems.map((item) => [item.good, item.qty]));
        const goods = [...new Set([...stationByGood.keys(), ...warehouseByGood.keys()])]
          .sort((a, b) => a.localeCompare(b));

        const rows = goods.map((good) => {
          const stationQty = stationByGood.get(good) || 0;
          const warehouseQty = warehouseByGood.get(good) || 0;
          return {
            good,
            stationQty,
            warehouseQty,
            totalQty: stationQty + warehouseQty
          };
        });

        return {
          id: entry.planetId,
          planetName: entry.planetName,
          systemName: entry.systemName,
          hasStorage: entry.hasStorage,
          rows
        };
      });

    const totalItems = [...totals.entries()]
      .map(([good, qty]) => ({ good, qty }))
      .sort((a, b) => b.qty - a.qty);

    const totalUnits = totalItems.reduce((acc, item) => acc + item.qty, 0);

    return {
      locations,
      totalItems,
      totalUnits
    };
  }, [planetInventories]);

  const selectedInventoryLocation = useMemo(() => {
    return inventoryManifest.locations.find((location) => location.id === selectedPlanetId) || null;
  }, [inventoryManifest.locations, selectedPlanetId]);

  const selectedPlanet = useMemo(() => {
    if (!selectedPlanetId) {
      return null;
    }

    for (const system of systems) {
      const planet = (system.planets || []).find((entry) => entry.id === selectedPlanetId);
      if (planet) {
        return {
          id: planet.id,
          name: planet.name,
          systemName: system.name
        };
      }
    }

    return null;
  }, [selectedPlanetId, systems]);

  const visibleInventoryLocations = useMemo(() => {
    if (!selectedPlanetId) {
      return inventoryManifest.locations;
    }

    return selectedInventoryLocation ? [selectedInventoryLocation] : [];
  }, [inventoryManifest.locations, selectedInventoryLocation, selectedPlanetId]);

  const visibleInventorySummary = useMemo(() => {
    const totals = new Map();

    for (const location of visibleInventoryLocations) {
      for (const row of location.rows) {
        totals.set(row.good, (totals.get(row.good) || 0) + row.totalQty);
      }
    }

    const totalItems = [...totals.entries()]
      .map(([good, qty]) => ({ good, qty }))
      .sort((left, right) => right.qty - left.qty);

    return {
      totalUnits: totalItems.reduce((acc, item) => acc + item.qty, 0),
      totalItems
    };
  }, [visibleInventoryLocations]);

  const selectedPlanetName = selectedInventoryLocation?.planetName || selectedPlanet?.name || "";

  function togglePlanetInventorySelection(planetId) {
    setSelectedPlanetId((prev) => (prev === planetId ? null : planetId));
    setActiveHudView("inventory");
  }

  async function connect(silent = false) {
    setConnecting(true);
    if (!silent) {
      setError("");
    }
    teardown();

    const normalizedConfig = {
      ...config,
      serverUrl: normalizeServerUrl(config.serverUrl),
      playerId: config.playerId.trim(),
      apiKey: config.apiKey.trim()
    };
    setConfig(normalizedConfig);
    localStorage.setItem("offworld-ui-config", JSON.stringify(normalizedConfig));

    try {
      setSyncOk(false);
      setPollingOk(false);
      setSseOk(false);
      
      await refreshPublicData(normalizedConfig);
      setSyncOk(true);
      
      await refreshPrivateData(normalizedConfig);
      setPollingOk(true);
      
      startSse(normalizedConfig);
      startPolling(normalizedConfig);
      setConnected(true);
      setError(""); // Clear any previous errors
    } catch (err) {
      setConnected(false);
      const errorMessage = `Connection failed: ${err.message}`;
      setError(errorMessage);
      
      // Auto-retry connection after 5 seconds if it was a silent auto-connect
      if (silent) {
        reconnectTimeoutRef.current = setTimeout(() => {
          connect(true);
        }, 5000);
      }
    } finally {
      setConnecting(false);
    }
  }

  function teardown() {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    
    for (const id of loopsRef.current) {
      clearInterval(id);
    }
    loopsRef.current = [];

    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }

    setSseOk(false);
    setPollingOk(false);
  }

  function startPolling(runtimeConfig) {
    const publicLoop = setInterval(() => {
      refreshPublicData(runtimeConfig)
        .then(() => {
          setSyncOk(true);
          // Clear error if polling succeeds
          setError((prev) => prev?.includes("Public refresh failed") ? "" : prev);
        })
        .catch((err) => {
          setSyncOk(false);
          setError(`Public refresh failed: ${err.message}`);
          // Auto-clear error after 8 seconds
          if (errorTimeoutRef.current) {
            clearTimeout(errorTimeoutRef.current);
          }
          errorTimeoutRef.current = setTimeout(() => {
            setError((prev) => prev?.includes("Public refresh failed") ? "" : prev);
          }, 8000);
        });
    }, 10000);

    const privateLoop = setInterval(() => {
      refreshPrivateData(runtimeConfig)
        .then(() => {
          setPollingOk(true);
          // Clear error if polling succeeds
          setError((prev) => prev?.includes("Private refresh failed") ? "" : prev);
        })
        .catch((err) => {
          setPollingOk(false);
          setError(`Private refresh failed: ${err.message}`);
          // Auto-clear error after 8 seconds
          if (errorTimeoutRef.current) {
            clearTimeout(errorTimeoutRef.current);
          }
          errorTimeoutRef.current = setTimeout(() => {
            setError((prev) => prev?.includes("Private refresh failed") ? "" : prev);
          }, 8000);
        });
    }, 5000);

    loopsRef.current.push(publicLoop, privateLoop);
    setPollingOk(true);
  }

  function startSse(runtimeConfig) {
    const controller = new AbortController();
    eventSourceRef.current = { close: () => controller.abort() };

    const headers = {};
    if (runtimeConfig.apiKey) {
      headers.Authorization = `Bearer ${runtimeConfig.apiKey}`;
    }

    fetch(`${runtimeConfig.serverUrl}/market/trades`, {
      headers,
      signal: controller.signal
    })
      .then(async (res) => {
        if (!res.ok) {
          const body = await res.text();
          throw new Error(`${res.status} ${res.statusText} ${body}`.trim());
        }

        if (!res.body) {
          throw new Error("SSE stream unavailable");
        }

        setSseOk(true);
        setError((prev) => prev?.includes("SSE failed") ? "" : prev); // Clear SSE error if connection succeeds
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const normalized = buffer.replace(/\r\n/g, "\n");
          const chunks = normalized.split("\n\n");
          buffer = chunks.pop() ?? "";

          for (const chunk of chunks) {
            const dataLines = chunk
              .split("\n")
              .filter((line) => line.startsWith("data:"))
              .map((line) => line.slice(5).trim());

            if (dataLines.length === 0) {
              continue;
            }

            try {
              const trade = normalizeTradeEvent(JSON.parse(dataLines.join("\n")));
              setRecentTrades((prev) => [...prev, trade]
                .sort((left, right) => right.receivedAtMs - left.receivedAtMs)
                .slice(0, 100));
              animateTrade(trade);
              setSseOk(true);
              lastTradeTimeRef.current = Date.now(); // Track market activity
            } catch {
              // ignore malformed events
            }
          }
        }

        if (!controller.signal.aborted) {
          setSseOk(false);
          // Attempt to reconnect after 3 seconds
          reconnectTimeoutRef.current = setTimeout(() => {
            startSse(runtimeConfig);
          }, 3000);
        }
      })
      .catch((err) => {
        if (controller.signal.aborted) {
          return;
        }

        setSseOk(false);
        setError(`SSE failed: ${err.message}`);
        
        // Auto-clear error after 8 seconds
        if (errorTimeoutRef.current) {
          clearTimeout(errorTimeoutRef.current);
        }
        errorTimeoutRef.current = setTimeout(() => {
          setError((prev) => prev?.includes("SSE failed") ? "" : prev);
        }, 8000);
        
        // Attempt to reconnect after 3 seconds
        reconnectTimeoutRef.current = setTimeout(() => {
          startSse(runtimeConfig);
        }, 3000);
      });
  }

  async function refreshPublicData(runtimeConfig) {
    const [systemsData, leaderboardData, pricesData] = await Promise.all([
      requestJson(runtimeConfig, "/systems"),
      requestJson(runtimeConfig, "/leaderboard"),
      requestJson(runtimeConfig, "/market/prices")
    ]);

    setSystems(systemsData);
    setLeaderboard(leaderboardData);
    setPrices(pricesData);
    setSyncOk(true);
    lastDataUpdateRef.current = Date.now();

    // Track rank changes
    const playerRank = leaderboardData?.findIndex((p) => p.name === config.playerId) + 1;
    if (playerRank > 0) {
      if (myRank !== playerRank) {
        setPrevRank(myRank);
        setMyRank(playerRank);
      }
    }
  }

  async function refreshPrivateData(runtimeConfig) {
    const [profileData, ordersData, shipsData, tradeData] = await Promise.all([
      requestJson(runtimeConfig, `/players/${runtimeConfig.playerId}`),
      requestJson(runtimeConfig, "/market/orders?status=open"),
      requestJson(runtimeConfig, "/ships"),
      requestJson(runtimeConfig, "/trade")
    ]);

    setProfile(profileData);
    setOrders(ordersData);
    setShips(shipsData);
    setTradeRequests(tradeData);
    setPollingOk(true);

    // Track credit changes
    const newCredits = profileData?.credits;
    if (typeof newCredits === "number" && prevCreditsRef.current !== null && newCredits !== prevCreditsRef.current) {
      const diff = newCredits - prevCreditsRef.current;
      setCreditDelta(diff);
      setTimeout(() => setCreditDelta(null), 2500);
    }
    if (typeof newCredits === "number") prevCreditsRef.current = newCredits;

    // Optional: fetch construction projects from Java bot backend (port 8081)
    fetch("/bot/construction")
      .then((r) => r.ok ? r.json() : [])
      .then((data) => setConstructionProjects(Array.isArray(data) ? data : []))
      .catch(() => {});
  }

  async function requestJson(runtimeConfig, path) {
    const headers = { "Content-Type": "application/json" };
    if (runtimeConfig.apiKey) {
      headers.Authorization = `Bearer ${runtimeConfig.apiKey}`;
    }

    const res = await fetch(`${runtimeConfig.serverUrl}${path}`, { headers });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`${res.status} ${res.statusText} ${body}`.trim());
    }

    return res.json();
  }

  function animateTrade(trade) {
    const layer = tradeLayerRef.current;
    if (!layer) {
      return;
    }

    const from = planetCoords.get(readField(trade, "seller_station", "sellerStation"));
    const to = planetCoords.get(readField(trade, "buyer_station", "buyerStation"));
    if (!from || !to) {
      return;
    }

    const ship = document.createElement("img");
    ship.src = "/images/ship.png";
    ship.alt = "ship";
    ship.className = "trade-ship";
    ship.style.left = `${from.x}%`;
    ship.style.top = `${from.y}%`;
    layer.appendChild(ship);

    const duration = 900 + Math.floor(Math.random() * 900);
    ship.animate(
      [
        { transform: "translate(-50%, -50%) scale(0.9)", left: `${from.x}%`, top: `${from.y}%`, opacity: 0.6 },
        { transform: "translate(-50%, -50%) scale(1)", left: `${to.x}%`, top: `${to.y}%`, opacity: 1 }
      ],
      { duration, easing: "linear" }
    );

    setTimeout(() => ship.remove(), duration + 30);
  }

  return (
    <>
      <div className="space-backdrop" />
      <div className="grain" />

      <header className="topbar">
        <h1>offworld-bot-client-test</h1>
        <div className={`status-dot ${pollingOk || syncOk ? "on" : connecting ? "connecting" : "off"}`}>
          {pollingOk || syncOk ? "ONLINE" : connecting ? "CONNECTING..." : "OFFLINE"}
        </div>
      </header>

      <main className="layout">
        <aside className="panel controls">
          <h2>Connection</h2>
          <label>
            Server URL
            <input value={config.serverUrl} onChange={(e) => setConfig((c) => ({ ...c, serverUrl: e.target.value }))} />
          </label>
          <label>
            Player ID
            <input value={config.playerId} onChange={(e) => setConfig((c) => ({ ...c, playerId: e.target.value }))} />
          </label>
          <label>
            API Key
            <input value={config.apiKey} onChange={(e) => setConfig((c) => ({ ...c, apiKey: e.target.value }))} />
          </label>
          <button className="connect-btn" onClick={connect} disabled={connecting}>{connecting ? "LINKING..." : "CONNECT & SYNC"}</button>
          <p className="error">{error}</p>

          <h2>Profile</h2>
          <div className="profile-card">
            <img src="/images/npc.png" alt="npc" />
            <div>
              <p>{profile?.name || "Unknown Pilot"}</p>
              <p className={creditDelta !== null ? (creditDelta >= 0 ? "credit-up" : "credit-down") : ""}>
                Credits: {formatNumber(profile?.credits)}
                {creditDelta !== null && (
                  <span className="credit-delta">{creditDelta > 0 ? `+${formatNumber(creditDelta)}` : formatNumber(creditDelta)}</span>
                )}
              </p>
              <p>Profit: {formatNumber(myProfit)}</p>
            </div>
          </div>

          <h2>Live Checks</h2>
          <div className="checks">
            <span className={`badge ${syncOk ? "on" : error?.includes("Public") ? "retrying" : ""}`}>
              Sync APIs
            </span>
            <span className={`badge ${pollingOk ? "on" : error?.includes("Private") ? "retrying" : ""}`}>
              Polling
            </span>
            <span className={`badge ${sseOk ? "on" : error?.includes("SSE") ? "retrying" : ""}`}>
              SSE
            </span>
          </div>

          <h2>System Health</h2>
          <div className="checks">
            <span className={`badge ${botBackendOk ? "on" : ""}`}>
              Bot Backend
            </span>
            <span className={`badge ${marketActivity ? "on" : ""}`}>
              Market Active
            </span>
          </div>

          <h2>Data Status</h2>
          <div className="checks">
            <span className={`badge ${dataFreshness < 10 ? "on" : dataFreshness < 30 ? "warning" : "off"}`}>
              Fresh: {dataFreshness}s
            </span>
            <span className={`badge ${orders.length > 0 ? "on" : ""}`}>
              Orders: {orders.length}
            </span>
            <span className={`badge ${ships.length > 0 ? "on" : ""}`}>
              Ships: {ships.length}
            </span>
          </div>

          {myRank && (
            <h2>Your Rank</h2>
          )}
          {myRank && (
            <div className="rank-display">
              <div className={`rank-badge ${prevRank && prevRank > myRank ? "rank-up" : prevRank && prevRank < myRank ? "rank-down" : ""}`}>
                <strong>#{myRank}</strong>
                {prevRank && prevRank !== myRank && (
                  <span className="rank-change">
                    {prevRank > myRank ? "↑" : "↓"} {Math.abs(prevRank - myRank)}
                  </span>
                )}
              </div>
            </div>
          )}
        </aside>

        <section className="center-column">
          <section className="panel game-panel">
            <div className="panel-title">Galaxy Tactical View</div>
            <div className="galaxy-scene">
              <div className="planet-layer">
                {systems.flatMap((system) =>
                  (system.planets || []).map((planet) => {
                    const coords = planetCoords.get(planet.id);
                    if (!coords) {
                      return null;
                    }

                    const station = planet.status?.station || planet.station;
                    const isPlayerStation = station?.owner_id === config.playerId;

                    return (
                      <div
                        key={`${system.name}-${planet.id}`}
                        className={`planet-node ${planet.id === "Sol-6" ? "planet-sol-6" : ""} ${isPlayerStation ? "player" : ""} ${selectedPlanetId === planet.id ? "selected" : ""}`}
                        onClick={() => togglePlanetInventorySelection(planet.id)}
                        role="button"
                        tabIndex={0}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            togglePlanetInventorySelection(planet.id);
                          }
                        }}
                        style={{
                          left: `${coords.x}%`,
                          top: `${coords.y}%`,
                          transform: `translate(-50%, -50%) translateZ(${coords.z}px)`
                        }}
                      >
                        <img
                          src={getPlanetImage(planet)}
                          alt={planet.name}
                        />
                        {station && <img src="/images/station.png" alt="station" className="station-mark" />}
                        <div className="planet-label">{planet.name}</div>
                      </div>
                    );
                  })
                )}
              </div>
              <div className="trade-layer" ref={tradeLayerRef} />
            </div>
          </section>

          <section className="panel feed-panel">
            <div className="panel-title">Trade Feed</div>
            <ul className="feed">
              {recentTrades.slice(0, 20).map((trade) => (
                <li key={getTradeKey(trade)} className="feed-item">
                  <span className="feed-time">{formatTradeTime(trade)}</span>
                  <span className="feed-text">
                    {readField(trade, "good_name", "goodName")} x{readField(trade, "quantity", "qty")} @ {readField(trade, "price", "unit_price")} | {readField(trade, "seller_id", "sellerId")} to {readField(trade, "buyer_id", "buyerId")}
                  </span>
                </li>
              ))}
              {recentTrades.length === 0 && <li>No trade events yet</li>}
            </ul>
          </section>
        </section>

        <aside className="panel right-column">
          <div className="hud-tabs" role="tablist" aria-label="Data views">
            {hudTabs.map((tab) => (
              <button
                key={tab.key}
                className={`hud-tab ${activeHudView === tab.key ? "active" : ""}`}
                onClick={() => setActiveHudView(tab.key)}
                aria-label={tab.label}
                title={tab.label}
              >
                <span className="hud-tab-icon-frame">
                  <img src={tab.icon} alt={tab.label} className="hud-tab-icon" />
                </span>
                <span className="hud-tab-label">{tab.label}</span>
              </button>
            ))}
          </div>

          <div className="hud-view-stack">
            {activeHudView === "inventory" && (
              <section className="side-section inventory-section">
                <div className="inventory-header">
                  <h2>Inventory Manifest</h2>
                  <span>{visibleInventoryLocations.length} hubs | {formatNumber(visibleInventorySummary.totalUnits)} units</span>
                </div>

                <div className="inventory-controls">
                  <span>
                    {selectedPlanetId
                      ? `Selected: ${selectedPlanetName} (click it again on map to clear)`
                      : "Selected: all planets (click a planet on map to focus)"}
                  </span>
                </div>

                <div className="inventory-totals">
                  {visibleInventorySummary.totalItems.slice(0, 10).map((item) => (
                    <div className="inventory-total-pill" key={`total-${item.good}`}>
                      <img src={getGoodIcon(item.good)} alt={item.good} />
                      <span>{item.good}</span>
                      <strong>{formatNumber(item.qty)}</strong>
                    </div>
                  ))}
                  {visibleInventorySummary.totalItems.length === 0 && (
                    <div className="inventory-empty">No station inventory found</div>
                  )}
                </div>

                <div className="inventory-locations">
                  {visibleInventoryLocations.map((location) => (
                    <article className="inventory-location" key={location.id}>
                      <div className="inventory-location-head">
                        <strong>{location.planetName}</strong>
                        <span>{location.systemName}</span>
                      </div>

                      {location.rows.length > 0 ? (
                        <div className="inventory-table-wrap">
                          <div className="inventory-table">
                            <div className="inventory-table-head">Good</div>
                            <div className="inventory-table-head">Station</div>
                            <div className="inventory-table-head">Warehouse</div>
                            <div className="inventory-table-head">Total</div>

                            {location.rows.flatMap((row) => ([
                              <div className="inventory-good-cell" key={`${location.id}-${row.good}-good`}>
                                <img src={getGoodIcon(row.good)} alt={row.good} />
                                <span>{row.good}</span>
                              </div>,
                              <div className="inventory-value-cell" key={`${location.id}-${row.good}-station`}>{formatNumber(row.stationQty)}</div>,
                              <div className="inventory-value-cell" key={`${location.id}-${row.good}-warehouse`}>{formatNumber(row.warehouseQty)}</div>,
                              <div className="inventory-value-cell total" key={`${location.id}-${row.good}-total`}>{formatNumber(row.totalQty)}</div>
                            ]))}
                          </div>
                        </div>
                      ) : (
                        <div className="inventory-empty">No goods stored on this planet.</div>
                      )}
                    </article>
                  ))}
                  {selectedPlanetId && !selectedInventoryLocation && (
                    <div className="inventory-empty">Selected planet has no station inventory.</div>
                  )}
                </div>
              </section>
            )}

            {activeHudView === "orders" && (
              <section className="side-section">
                <h2>Open Orders</h2>
                <ul className="order-grid">
                  {orders.slice(0, 10).map((order) => (
                    <li key={readField(order, "id", "order_id", "orderId")} className="order-card">
                      <img src={getGoodIcon(readField(order, "good_name", "goodName"))} alt={readField(order, "good_name", "goodName")} />
                      <div className="order-info">
                        <span className={`order-side ${readField(order, "side", "order_side") === "buy" ? "buy" : "sell"}`}>
                          {String(readField(order, "side", "order_side") || "-").toUpperCase()}
                        </span>
                        <strong>{readField(order, "good_name", "goodName")}</strong>
                        <span>Qty {formatNumber(readField(order, "quantity", "qty"))}</span>
                        <span>@ {formatNumber(readField(order, "price", "unit_price"))} cr</span>
                      </div>
                    </li>
                  ))}
                  {orders.length === 0 && <li className="order-empty">No open orders</li>}
                </ul>
              </section>
            )}

            {activeHudView === "market" && (
              <section className="side-section hud-split">
                <div>
                  <h2>Market Prices</h2>
                  <div className="prices-grid">
                    {Object.entries(prices)
                      .sort((a, b) => a[0].localeCompare(b[0]))
                      .slice(0, 12)
                      .map(([good, price]) => (
                        <div className="price-item" key={good}>
                          <img src={getGoodIcon(good)} alt={good} />
                          <div>{good}: {formatNumber(price)}</div>
                        </div>
                      ))}
                  </div>
                </div>

                <div>
                  <h2>Bot Activity</h2>
                  <ul className="mini-feed hud-list">
                    {myTrades.map((trade) => {
                      const action = readField(trade, "buyer_id", "buyerId") === config.playerId ? "BUY" : "SELL";
                      return <li key={`mine-${readField(trade, "id", "trade_id", "tradeId")}`}>{action} {readField(trade, "good_name", "goodName")} x{readField(trade, "quantity", "qty")} @ {readField(trade, "price", "unit_price")}</li>;
                    })}
                    {myTrades.length === 0 && <li>No bot trade events yet</li>}
                  </ul>
                </div>
              </section>
            )}

            {activeHudView === "fleet" && (
              <section className="side-section hud-split">
                <div>
                  <h2>Ships</h2>
                  <ul className="mini-feed hud-list">
                    {ships.slice(0, 12).map((ship) => (
                      <li key={readField(ship, "id", "ship_id", "shipId")}>{readField(ship, "status", "state")} | {readField(ship, "origin_planet_id", "originPlanetId")} to {readField(ship, "destination_planet_id", "destinationPlanetId")}</li>
                    ))}
                    {ships.length === 0 && <li>No active ships</li>}
                  </ul>
                </div>

                <div>
                  <h2>Trade Requests</h2>
                  <ul className="mini-feed hud-list">
                    {tradeRequests.slice(0, 12).map((req) => (
                      <li key={readField(req, "id", "request_id", "requestId")}>{readField(req, "direction", "side")} {readField(req, "good_name", "goodName")} | {readField(req, "status", "state")} | +{readField(req, "cumulative_generated", "cumulativeGenerated")}</li>
                    ))}
                    {tradeRequests.length === 0 && <li>No active trade requests</li>}
                  </ul>
                </div>
              </section>
            )}

            {activeHudView === "build" && (
              <section className="side-section">
                <h2>Construction Projects</h2>
                {constructionProjects.length === 0 ? (
                  <div className="construction-empty">
                    <p>No active construction projects.</p>
                    <p className="construction-hint">
                      Bot backend must be running on port 8081 to show data here.
                    </p>
                  </div>
                ) : (
                  <ul className="construction-list">
                    {constructionProjects.map((proj) => {
                      const id = readField(proj, "id", "project_id", "projectId");
                      const type = readField(proj, "project_type", "projectType") ?? "unknown";
                      const status = readField(proj, "status") ?? "unknown";
                      const target = readField(proj, "target_planet_id", "targetPlanetId") ?? "?";
                      const source = readField(proj, "source_planet_id", "sourcePlanetId");
                      const createdAt = readField(proj, "created_at", "createdAt");
                      const completionAt = readField(proj, "completion_at", "completionAt");
                      const progress = getProjectProgress(createdAt, completionAt);
                      return (
                        <li key={id} className="construction-card">
                          <div className="construction-card-header">
                            <span className={`construction-badge construction-badge-${status.replace(/_/g, "-")}`}>
                              {status.replace(/_/g, " ")}
                            </span>
                            <strong className="construction-type">
                              {formatProjectType(type)}
                            </strong>
                          </div>
                          <div className="construction-card-body">
                            {source && <span className="construction-route">{source} → {target}</span>}
                            {!source && <span className="construction-route">{target}</span>}
                          </div>
                          {progress !== null && (
                            <div className="construction-progress-wrap">
                              <div className="construction-progress-bar" style={{ width: `${progress}%` }} />
                              <span className="construction-progress-label">{progress}%</span>
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            )}

            {activeHudView === "ranking" && (
              <section className="side-section">
                <h2>Leaderboard</h2>
                <ul className="mini-feed hud-list">
                  {leaderboard.slice(0, 16).map((player, idx) => (
                    <li key={readField(player, "player_id", "playerId")}>#{idx + 1} {readField(player, "player_name", "playerName", "name") || readField(player, "player_id", "playerId")} | {formatNumber(readField(player, "profit", "total_profit"))}</li>
                  ))}
                  {leaderboard.length === 0 && <li>No rankings</li>}
                </ul>
              </section>
            )}
          </div>
        </aside>
      </main>
    </>
  );
}

function formatNumber(value) {
  if (typeof value !== "number") {
    return value ?? "--";
  }

  return new Intl.NumberFormat("en-US").format(value);
}

function formatTradeTime(trade) {
  const value = readField(
    trade,
    "received_at",
    "receivedAt",
    "created_at",
    "createdAt",
    "executed_at",
    "executedAt",
    "timestamp"
  );
  const date = value ? new Date(value) : null;

  if (!date || Number.isNaN(date.getTime())) {
    return "--:--:--";
  }

  return new Intl.DateTimeFormat("fr-FR", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(date);
}

function normalizeTradeEvent(trade) {
  const receivedAtIso = new Date().toISOString();

  return {
    ...trade,
    receivedAt: readField(trade, "received_at", "receivedAt", "created_at", "createdAt", "executed_at", "executedAt", "timestamp") ?? receivedAtIso,
    receivedAtMs: Date.now()
  };
}

function getTradeKey(trade) {
  return String(
    readField(trade, "id", "trade_id", "tradeId")
      ?? `${readField(trade, "good_name", "goodName")}-${readField(trade, "buyer_id", "buyerId")}-${trade.receivedAtMs}`
  );
}

function hash(input) {
  let h = 2166136261;
  for (let i = 0; i < input.length; i += 1) {
    h ^= input.charCodeAt(i);
    h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
  }
  return Math.abs(h >>> 0);
}

function clamp(n, min, max) {
  return Math.max(min, Math.min(max, n));
}

function readField(obj, ...keys) {
  if (!obj) {
    return undefined;
  }

  for (const key of keys) {
    if (obj[key] !== undefined && obj[key] !== null) {
      return obj[key];
    }
  }

  return undefined;
}

function getProjectProgress(createdAt, completionAt) {
  if (!completionAt || !createdAt) return null;
  const now = Date.now();
  const progress = ((now - createdAt) / (completionAt - createdAt)) * 100;
  return Math.min(100, Math.max(0, Math.round(progress)));
}

function formatProjectType(type) {
  const labels = {
    install_station: "Install Station",
    found_settlement: "Found Settlement",
    upgrade_station: "Upgrade Station",
    upgrade_elevator: "Upgrade Elevator"
  };
  return labels[type] ?? type.replace(/_/g, " ");
}

function getGoodIcon(good) {
  return goodsWithIcons.includes(good) ? `/images/${good}.png` : "/images/default.png";
}

function getPlanetImage(planet) {
  return planetImageById[planet.id]
    || (planet.planet_type?.category === "gas_giant" ? "/images/planet_gas.png" : "/images/planet_telluric.png");
}

function normalizeServerUrl(url) {
  const trimmed = (url || "").trim().replace(/\/$/, "");
  if (!trimmed) {
    return "/api";
  }

  if (
    trimmed === "http://localhost:3000" ||
    trimmed === "http://127.0.0.1:3000" ||
    trimmed === "localhost:3000" ||
    trimmed === "127.0.0.1:3000"
  ) {
    return "/api";
  }

  return trimmed;
}

export default App;
