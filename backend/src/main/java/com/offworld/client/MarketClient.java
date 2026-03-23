package com.offworld.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offworld.model.MarketOrder;
import com.offworld.model.OrderBook;
import com.offworld.model.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class MarketClient {

    private static final Logger log = LoggerFactory.getLogger(MarketClient.class);
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public MarketClient(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public Flux<TradeEvent> streamTrades() {
        return webClient.get()
                .uri("/market/trades")
                // On retire le header auth car cet endpoint est public
                .retrieve()
                .bodyToFlux(ServerSentEvent.class)
                .filter(sse -> sse.data() != null)
                .flatMap(sse -> {
                    try {
                        // Le serveur envoie parfois du Rust Debug format : {key=value, ...}
                        // We normalize to valid JSON before deserialization
                        String data = normalizeToJson(sse.data().toString());
                        TradeEvent event = objectMapper.readValue(data, TradeEvent.class);
                        return Mono.just(event);
                    } catch (Exception e) {
                        log.warn("Impossible de parser le trade event: {}", e.getMessage());
                        return Mono.empty();
                    }
                })
                .onBackpressureBuffer(500)
                .doOnSubscribe(s -> log.info("Connected to SSE stream /market/trades"))
                .doOnError(e -> log.error("Erreur sur le stream SSE: {}", e.getMessage()))
                .retryWhen(reactor.util.retry.Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(5)));
    }

    public Mono<Map<String, Long>> getAllPrices() {
        return webClient.get()
                .uri("/market/prices")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Long>>() {})
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<OrderBook> getOrderBook(String goodName) {
        return webClient.get()
                .uri("/market/book/{good}", goodName)
                .retrieve()
                .bodyToMono(OrderBook.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<MarketOrder> placeOrder(MarketOrder.PlaceOrderRequest req) {
        log.info("Placement ordre {} {} {} @ {}", req.side(), req.quantity(), req.goodName(), req.price());
        return webClient.post()
                .uri("/market/orders")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(MarketOrder.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Flux<MarketOrder> getMyOrders(String status) {
        return webClient.get()
                .uri(u -> {
                    var builder = u.path("/market/orders");
                    if (status != null) builder.queryParam("status", status);
                    return builder.build();
                })
                .retrieve()
                .bodyToFlux(MarketOrder.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<MarketOrder> cancelOrder(String orderId) {
        log.info("Annulation de l'ordre {}", orderId);
        return webClient.delete()
                .uri("/market/orders/{id}", orderId)
                .retrieve()
                .bodyToMono(MarketOrder.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("Impossible d'annuler l'ordre {}: {}", orderId, e.getMessage());
                    return Mono.empty();
                });
    }

    // Convertit {key=value} (Rust Debug) en JSON valide
    private static String normalizeToJson(String raw) {
        if (raw == null) return "{}";
        raw = raw.trim();
        // If it's already JSON (starts with '{' or '['), leave it as is
        if (raw.startsWith("{\"") || raw.startsWith("[")) return raw;
        // Retire les accolades
        String inner = raw.replaceAll("^\\{", "").replaceAll("\\}$", "");
        // Split by ", " (comma-space between fields)
        String[] pairs = inner.split(",\\s*(?=[a-zA-Z_]+=)");
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i].trim();
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim();
            String val = pair.substring(eq + 1).trim();
            if (i > 0) sb.append(",");
            sb.append("\"").append(key).append("\":");
            // Garde les nombres/null sans guillemets, le reste entre guillemets
            if (val.matches("-?\\d+(\\.\\d+)?") || val.equals("null") || val.equals("true") || val.equals("false")) {
                sb.append(val);
            } else {
                sb.append("\"").append(val.replace("\"", "\\\"")).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
