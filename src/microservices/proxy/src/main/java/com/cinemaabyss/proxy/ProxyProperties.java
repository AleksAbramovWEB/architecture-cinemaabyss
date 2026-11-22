package com.cinemaabyss.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("proxy")
public record ProxyProperties(
        URI monolithUrl,
        URI moviesServiceUrl,
        URI eventsServiceUrl,
        boolean gradualMigration,
        int moviesMigrationPercent,
        Duration connectTimeout,
        Duration requestTimeout) {

    public ProxyProperties {
        validateOrigin(monolithUrl, "MONOLITH_URL");
        validateOrigin(moviesServiceUrl, "MOVIES_SERVICE_URL");
        validateOrigin(eventsServiceUrl, "EVENTS_SERVICE_URL");
        if (moviesMigrationPercent < 0 || moviesMigrationPercent > 100) {
            throw new IllegalArgumentException("MOVIES_MIGRATION_PERCENT must be between 0 and 100");
        }
        validateTimeout(connectTimeout, "PROXY_CONNECT_TIMEOUT");
        validateTimeout(requestTimeout, "PROXY_REQUEST_TIMEOUT");
    }

    private static void validateOrigin(URI uri, String name) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) origin without credentials, query or path");
        }
    }

    private static void validateTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
