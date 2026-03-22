package com.offworld.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STATION CLIENT TEST
 * * Purpose: Tests the communication between our bot and the space station's API.
 * How it works: It uses a fake server (MockWebServer) to pretend to be the real game server.
 * * What it checks:
 * 1. Can the bot successfully send a request to transfer goods?
 * 2. Does the bot handle errors correctly (like a broken cabin or a dead server)?
 * 3. Does it correctly read and translate the station's JSON data (snake_case) into Java objects?
 */
class StationClientTest {

    private MockWebServer mockServer;
    private StationClient stationClient;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule());

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> {
                    cfg.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
                    cfg.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                })
                .build();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .exchangeStrategies(strategies)
                .build();

        stationClient = new StationClient(webClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }


    @Test
    @DisplayName("transferGoods — transfert réussi retourne le bon résultat")
    void transferGoods_success() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setBodyDelay(100, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "success": true,
                          "cabin_id": 2,
                          "duration_secs": 5,
                          "items": [{"good_name":"food","quantity":100}],
                          "total_quantity": 100,
                          "failure_reason": null
                        }
                        """));

        var items = List.of(new StationClient.TransferItem("food", 100L));

        // WHEN / THEN
        StepVerifier.create(stationClient.transferGoods("Sol", "p1", "to_orbit", items))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.cabinId()).isEqualTo(2);
                    assertThat(result.totalQuantity()).isEqualTo(100L);
                    assertThat(result.durationSecs()).isEqualTo(5);
                })
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/settlements/Sol/p1/space-elevator/transfer");
        assertThat(request.getBody().readUtf8()).contains("to_orbit", "food");
    }

    @Test
    @DisplayName("transferGoods — cabine en panne, success=false + failureReason")
    void transferGoods_failure() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "success": false,
                          "cabin_id": 1,
                          "duration_secs": 0,
                          "items": [],
                          "total_quantity": 0,
                          "failure_reason": "cabin_broken"
                        }
                        """));

        var items = List.of(new StationClient.TransferItem("iron_ore", 50L));

        StepVerifier.create(stationClient.transferGoods("Sol", "p1", "to_surface", items))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.failureReason()).isEqualTo("cabin_broken");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("transferGoods — erreur 503 propage une exception réactive")
    void transferGoods_serverError_propagatesError() {
        mockServer.enqueue(new MockResponse().setResponseCode(503));

        var items = List.of(new StationClient.TransferItem("food", 10L));

        StepVerifier.create(stationClient.transferGoods("Sol", "p1", "to_orbit", items))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("transferGoods — direction to_surface envoyée correctement dans la requête")
    void transferGoods_toSurface_requestIsCorrect() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"success":true,"cabin_id":1,"duration_secs":3,
                         "items":[],"total_quantity":200,"failure_reason":null}
                        """));

        var items = List.of(
                new StationClient.TransferItem("water", 100L),
                new StationClient.TransferItem("food",  100L)
        );

        StepVerifier.create(stationClient.transferGoods("Sol", "p5", "to_surface", items))
                .assertNext(r -> assertThat(r.success()).isTrue())
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getBody().readUtf8()).contains("to_surface", "water", "food");
    }

    
    @Test
    @DisplayName("getElevatorStatus — désérialise correctement le JSON snake_case")
    void getElevatorStatus_parsesCorrectly() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "warehouse": {"owner_id":"alpha-team","inventory":{"food":500}},
                          "config": {"cabin_count":3,"cabin_capacity":1000,
                                     "transfer_duration_secs":5,"failure_rate":0.1,
                                     "repair_duration_secs":30},
                          "cabins": [
                            {"id":1,"state":"available","available_in_secs":null},
                            {"id":2,"state":"busy","available_in_secs":3}
                          ]
                        }
                        """));

        StepVerifier.create(stationClient.getElevatorStatus("Sol", "p1"))
                .assertNext(info -> {
                    assertThat(info.hasAvailableCabin()).isTrue();
                    assertThat(info.cabins()).hasSize(2);
                    assertThat(info.warehouse().ownerId()).isEqualTo("alpha-team");
                    assertThat(info.config().cabinCount()).isEqualTo(3);
                })
                .verifyComplete();
    }
}