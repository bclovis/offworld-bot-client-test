package com.offworld.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.offworld.model.SpaceElevatorInfo;
import com.offworld.model.StationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class StationClient {

    private static final Logger log = LoggerFactory.getLogger(StationClient.class);
    private final WebClient webClient;

    public StationClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<StationInfo> getMyStation(String systemName, String planetId) {
        return webClient.get()
                .uri("/settlements/{system}/{planet}/station", systemName, planetId)
                .retrieve()
                .bodyToMono(StationInfo.class)
                .timeout(Duration.ofSeconds(10));
    }

    public Mono<SpaceElevatorInfo> getElevatorStatus(String systemName, String planetId) {
        return webClient.get()
                .uri("/settlements/{system}/{planet}/space-elevator", systemName, planetId)
                .retrieve()
                .bodyToMono(SpaceElevatorInfo.class)
                .timeout(Duration.ofSeconds(10));
    }

    // direction: "to_surface" ou "to_orbit"
    public Mono<ElevatorTransferResult> transferGoods(
            String systemName,
            String planetId,
            String direction,
            List<TransferItem> items
    ) {
        log.debug("Transfer {} vers {} direction {}", items, planetId, direction);

        var body = Map.of("direction", direction, "items", items);

        return webClient.post()
                .uri("/settlements/{system}/{planet}/space-elevator/transfer", systemName, planetId)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), resp ->
                        resp.bodyToMono(String.class)
                                .doOnNext(err -> log.warn("[ELEVATOR] 400 body: {}", err))
                                .then(Mono.error(new RuntimeException("400: " + resp.statusCode())))
                )
                .bodyToMono(ElevatorTransferResult.class)
                .timeout(Duration.ofSeconds(60))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(r -> {
                    if (r.success()) {
                        log.info("Transfer OK - {} units via cabin {}", r.totalQuantity(), r.cabinId());
                    } else {
                        log.warn("Transfer FAILED - {}", r.failureReason());
                    }
                });
    }

    public record TransferItem(
            @JsonProperty("good_name") String goodName,
            long quantity
    ) {}

    public record ElevatorTransferResult(
            boolean success,
            @JsonProperty("cabin_id") int cabinId,
            @JsonProperty("duration_secs") int durationSecs,
            List<TransferItem> items,
            @JsonProperty("total_quantity") long totalQuantity,
            @JsonProperty("failure_reason") String failureReason
    ) {}
}
