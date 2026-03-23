package com.offworld;

import com.offworld.model.ConstructionProject;
import com.offworld.model.OrderBook;
import com.offworld.model.Planet;
import com.offworld.model.Ship;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared application state.
 * Accessible by all services via Spring injection.
 * We use ConcurrentHashMap to avoid concurrency issues
 * between the SSE thread, the webhook controller and the strategy loop.
 */
@Component
public class AppState {

    // ID of our main planet (set at startup)
    private volatile String myPlanetId;
    private volatile String mySystemName;

    // Price cache: good_name -> last observed price (from SSE)
    private final ConcurrentHashMap<String, Long> lastPrices = new ConcurrentHashMap<>();

    // Order books updated by the strategy
    private final ConcurrentHashMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    // Active ships we track (ship_id -> ship)
    private final ConcurrentHashMap<String, Ship> activeShips = new ConcurrentHashMap<>();

    // Connected planets we discovered (planet_id -> planet)
    private final ConcurrentHashMap<String, Planet> connectedPlanets = new ConcurrentHashMap<>();

    // Active construction projects (project_id -> project)
    private final ConcurrentHashMap<String, ConstructionProject> constructionProjects = new ConcurrentHashMap<>();

    // Our current credits (updated periodically)
    private volatile long credits = 0;

    public String getMyPlanetId() { return myPlanetId; }
    public void setMyPlanetId(String id) { this.myPlanetId = id; }

    public String getMySystemName() { return mySystemName; }
    public void setMySystemName(String name) { this.mySystemName = name; }

    public void updatePrice(String goodName, long price) {
        lastPrices.put(goodName, price);
    }

    public Long getPrice(String goodName) {
        return lastPrices.get(goodName);
    }

    public Map<String, Long> getAllPrices() { return lastPrices; }

    public void updateOrderBook(String goodName, OrderBook book) {
        orderBooks.put(goodName, book);
    }

    public OrderBook getOrderBook(String goodName) {
        return orderBooks.get(goodName);
    }

    public void trackShip(Ship ship) {
        activeShips.put(ship.id(), ship);
    }

    public void updateShip(Ship ship) {
        activeShips.put(ship.id(), ship);
    }

    public void removeShip(String shipId) {
        activeShips.remove(shipId);
    }

    public Map<String, Ship> getActiveShips() { return activeShips; }

    public Ship getShip(String shipId) { return activeShips.get(shipId); }

    public void addConnectedPlanet(Planet planet) {
        connectedPlanets.put(planet.id(), planet);
    }

    public Map<String, Planet> getConnectedPlanets() { return connectedPlanets; }

    public long getCredits() { return credits; }
    public void setCredits(long credits) { this.credits = credits; }

    public void putConstructionProjects(List<ConstructionProject> projects) {
        constructionProjects.clear();
        projects.forEach(p -> constructionProjects.put(p.id(), p));
    }

    public void removeConstructionProject(String id) {
        constructionProjects.remove(id);
    }

    public List<ConstructionProject> getConstructionProjectsList() {
        return new ArrayList<>(constructionProjects.values());
    }
}
