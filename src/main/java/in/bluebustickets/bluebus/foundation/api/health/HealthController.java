package in.bluebustickets.bluebus.foundation.api.health;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * Liveness probe only: success means the application process can serve HTTP.
     * It is deliberately not a PostgreSQL or Flyway readiness check.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "blue-bus-backend",
                "timestamp", Instant.now().toString()));
    }
}
