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
 * Expose l'état interne du bot au frontend de monitoring.
 * Endpoint: GET /bot/construction → liste des projets de construction actifs.
 *
 * @CrossOrigin permet au frontend Vite (dev) d'appeler directement ce serveur.
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
