package com.offworld.webhook;

import com.offworld.AppState;
import com.offworld.model.ConstructionProject;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Exposes internal bot state to monitoring frontend.
 * Endpoint: GET /bot/construction → list of active construction projects.
 *
 * @CrossOrigin allows the Vite frontend (dev) to call this server directly.
 */
@RestController
@RequestMapping("/bot")
@CrossOrigin(origins = "*")
public class BotStatusController {

    private final AppState state;

    public BotStatusController(AppState state) {
        this.state = state;
    }

    @GetMapping("/construction")
    public Mono<List<ConstructionProject>> getConstructionProjects() {
        return Mono.just(state.getConstructionProjectsList());
    }
}
