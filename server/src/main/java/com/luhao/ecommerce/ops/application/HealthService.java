package com.luhao.ecommerce.ops.application;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private final JdbcTemplate jdbc;

    public HealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Report check() {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String database = "DOWN";
        String vector = "UNKNOWN";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            database = "UP";
            var versions = jdbc.queryForList(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            vector = versions.isEmpty() ? "MISSING" : versions.getFirst();
        } catch (DataAccessException e) {
            log.warn("health requestId={} errorType={}", requestId, e.getClass().getSimpleName());
        }
        boolean healthy = database.equals("UP") && !vector.equals("UNKNOWN") && !vector.equals("MISSING");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("health requestId={} status={} elapsedMs={}", requestId, healthy ? "UP" : "DOWN", elapsedMs);
        return new Report(healthy, Map.of(
                "status", healthy ? "UP" : "DOWN", "service", "UP", "database", database,
                "pgvector", vector, "requestId", requestId, "elapsedMs", elapsedMs));
    }

    public record Report(boolean healthy, Map<String, Object> body) {}
}
