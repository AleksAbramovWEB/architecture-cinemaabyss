package com.cinemaabyss.proxy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

@Component
public class UpstreamRouter {
    private final ProxyProperties properties;
    private final IntSupplier percentageDraw;

    @Autowired
    public UpstreamRouter(ProxyProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextInt(100));
    }

    UpstreamRouter(ProxyProperties properties, IntSupplier percentageDraw) {
        this.properties = properties;
        this.percentageDraw = percentageDraw;
    }

    public Upstream route(String path) {
        if (inDomain(path, "/api/events")) {
            return new Upstream("events-service", properties.eventsServiceUrl());
        }
        // The monolith has no movies health route: always check the extracted service.
        if ("/api/movies/health".equals(path)) {
            return movies();
        }
        if (inDomain(path, "/api/movies") && (!properties.gradualMigration()
                || percentageDraw.getAsInt() < properties.moviesMigrationPercent())) {
            return movies();
        }
        return new Upstream("monolith", properties.monolithUrl());
    }

    private Upstream movies() {
        return new Upstream("movies-service", properties.moviesServiceUrl());
    }

    private static boolean inDomain(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    public record Upstream(String name, URI origin) {
        URI requestUri(String rawPath, String rawQuery) {
            String base = origin.toString();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            // Appending the raw path keeps the origin fixed and does not double-encode %xx.
            return URI.create(base + rawPath + (rawQuery == null ? "" : "?" + rawQuery));
        }
    }
}
