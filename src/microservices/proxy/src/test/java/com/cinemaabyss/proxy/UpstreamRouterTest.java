package com.cinemaabyss.proxy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UpstreamRouterTest {
    static ProxyProperties properties(boolean gradual, int percent) {
        return new ProxyProperties(URI.create("http://monolith:8080"), URI.create("http://movies:8081"),
                URI.create("http://events:8082"), gradual, percent, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void zeroAndHundredPercentAreExact() {
        assertThat(new UpstreamRouter(properties(true, 0), () -> 0).route("/api/movies").name()).isEqualTo("monolith");
        assertThat(new UpstreamRouter(properties(true, 100), () -> 99).route("/api/movies").name()).isEqualTo("movies-service");
    }

    @Test
    void percentageThresholdRoutesTheExpectedPortion() {
        AtomicInteger draw = new AtomicInteger();
        var router = new UpstreamRouter(properties(true, 37), draw::getAndIncrement);
        int migrated = 0;
        for (int i = 0; i < 100; i++) {
            if (router.route("/api/movies").name().equals("movies-service")) {
                migrated++;
            }
        }
        assertThat(migrated).isEqualTo(37);
    }

    @Test
    void disabledFlagFullySwitchesMoviesAndLeavesOtherDomainsAlone() {
        var router = new UpstreamRouter(properties(false, 0), () -> 99);
        assertThat(router.route("/api/movies").name()).isEqualTo("movies-service");
        assertThat(router.route("/api/movies/42").name()).isEqualTo("movies-service");
        assertThat(router.route("/api/movies-extra").name()).isEqualTo("monolith");
        assertThat(router.route("/api/payments").name()).isEqualTo("monolith");
        assertThat(router.route("/api/subscriptions").name()).isEqualTo("monolith");
    }

    @Test
    void eventsAndMoviesHealthBypassMigrationFlag() {
        var router = new UpstreamRouter(properties(true, 0), () -> 0);
        assertThat(router.route("/api/events").name()).isEqualTo("events-service");
        assertThat(router.route("/api/events/movie").name()).isEqualTo("events-service");
        assertThat(router.route("/api/events-extra").name()).isEqualTo("monolith");
        assertThat(router.route("/api/movies/health").name()).isEqualTo("movies-service");
    }

    @Test
    void rawPathAndQueryStayEncodedAndCannotReplaceOrigin() {
        var upstream = new UpstreamRouter.Upstream("movies", URI.create("http://movies:8081/"));
        URI uri = upstream.requestUri("/api/movies", "title=a%2Bb&genre=Drama&genre=Sci-Fi");
        assertThat(uri.toString()).isEqualTo("http://movies:8081/api/movies?title=a%2Bb&genre=Drama&genre=Sci-Fi");
        assertThat(upstream.requestUri("//other.example/path", null).getHost()).isEqualTo("movies");
    }

    @Test
    void invalidPercentageAndOriginsFailFast() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true, 101));
        for (String invalid : new String[]{"ftp://movies", "http://movies/path", "http://user:pass@movies", "http://movies?query=1"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ProxyProperties(URI.create(invalid),
                    URI.create("http://movies:8081"), URI.create("http://events:8082"), true, 50,
                    Duration.ofSeconds(1), Duration.ofSeconds(2)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ProxyProperties(URI.create("http://monolith"),
                URI.create("http://movies"), URI.create("http://events"), true, 50, Duration.ZERO, Duration.ofSeconds(2)));
    }
}
