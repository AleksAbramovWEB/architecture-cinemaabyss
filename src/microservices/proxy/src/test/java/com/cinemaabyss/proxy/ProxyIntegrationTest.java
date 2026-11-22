package com.cinemaabyss.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Stub MONOLITH = stub("monolith");
    private static final Stub MOVIES = stub("movies-service");
    private static final Stub EVENTS = stub("events-service");
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("proxy.monolith-url", MONOLITH::origin);
        registry.add("proxy.movies-service-url", MOVIES::origin);
        registry.add("proxy.events-service-url", EVENTS::origin);
        registry.add("proxy.movies-migration-percent", () -> 100);
        registry.add("proxy.request-timeout", () -> "250ms");
    }

    @BeforeEach
    void clearRequests() {
        List.of(MONOLITH, MOVIES, EVENTS).forEach(stub -> stub.requests.clear());
    }

    @AfterAll
    static void stopServers() {
        List.of(MONOLITH, MOVIES, EVENTS).forEach(stub -> stub.server.stop(0));
        EXECUTOR.close();
    }

    @Test
    void localHealthDoesNotCallAnyUpstream() throws Exception {
        var result = send("GET", "/health", new byte[0]);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("Strangler Fig Proxy is healthy");
        assertThat(MONOLITH.requests).isEmpty();
        assertThat(MOVIES.requests).isEmpty();
        assertThat(EVENTS.requests).isEmpty();
    }

    @Test
    void forwardsMethodRawQueryBinaryBodyStatusAndHeaders() throws Exception {
        byte[] body = {0, 1, 2, (byte) 0xff, 13, 10};
        HttpRequest request = HttpRequest.newBuilder(local("/api/movies?title=a%2Bb&genre=Drama&genre=Sci-Fi"))
                .header("Content-Type", "application/octet-stream")
                .header("X-Request-Token", "keep-me")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var result = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.body()).isEqualTo(body);
        assertThat(result.headers().firstValue("X-Cinemaabyss-Upstream")).contains("movies-service");
        assertThat(result.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(result.headers().firstValue("X-Upstream-Token")).contains("preserved");
        assertThat(result.headers().firstValue("X-Transient")).isEmpty();
        Captured captured = MOVIES.requests.element();
        assertThat(captured.method).isEqualTo("POST");
        assertThat(captured.rawPath).isEqualTo("/api/movies");
        assertThat(captured.rawQuery).isEqualTo("title=a%2Bb&genre=Drama&genre=Sci-Fi");
        assertThat(captured.body).isEqualTo(body);
        assertThat(captured.header("X-Request-Token")).containsExactly("keep-me");
        assertThat(MOVIES.requests).hasSize(1);
        assertThat(MONOLITH.requests).isEmpty();
    }

    @Test
    void sendsEventsToEventsAndOtherResourcesToMonolith() throws Exception {
        assertThat(send("POST", "/api/events/movie", "{}".getBytes(StandardCharsets.UTF_8))
                .headers().firstValue("X-Cinemaabyss-Upstream")).contains("events-service");
        assertThat(send("GET", "/api/users?id=7", new byte[0])
                .headers().firstValue("X-Cinemaabyss-Upstream")).contains("monolith");
        assertThat(EVENTS.requests.element().rawPath).isEqualTo("/api/events/movie");
        assertThat(MONOLITH.requests.element().rawQuery).isEqualTo("id=7");
    }

    @Test
    void formPutBodyIsForwardedWithoutServletParsing() throws Exception {
        byte[] body = "title=a%2Bb&tag=one&tag=two".getBytes(StandardCharsets.UTF_8);
        var result = CLIENT.send(HttpRequest.newBuilder(local("/api/movies"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(MOVIES.requests.element().method).isEqualTo("PUT");
        assertThat(MOVIES.requests.element().body).isEqualTo(body);
        assertThat(result.body()).isEqualTo(body);
    }

    @Test
    void multipartBodyIsForwardedWithoutServletParsing() throws Exception {
        byte[] body = ("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"movie.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nmovie data\r\n--boundary--\r\n").getBytes(StandardCharsets.UTF_8);
        var result = CLIENT.send(HttpRequest.newBuilder(local("/api/movies"))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(MOVIES.requests.element().body).isEqualTo(body);
        assertThat(result.body()).isEqualTo(body);
    }

    @Test
    void forwardsUpstreamErrorWithoutChangingItsBody() throws Exception {
        var result = send("GET", "/fail", new byte[0]);
        assertThat(result.statusCode()).isEqualTo(422);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"rejected\"}");
    }

    @Test
    void returnsRedirectToCallerInsteadOfFollowingIt() throws Exception {
        var result = send("POST", "/redirect", "request".getBytes(StandardCharsets.UTF_8));
        assertThat(result.statusCode()).isEqualTo(307);
        assertThat(result.headers().firstValue("Location")).contains("/api/users");
        assertThat(MONOLITH.requests).hasSize(1);
    }

    @Test
    void timeoutReturns504WithoutSendingTheWriteToAnotherUpstream() throws Exception {
        var result = send("POST", "/slow", "request".getBytes(StandardCharsets.UTF_8));
        assertThat(result.statusCode()).isEqualTo(504);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).contains("timed out");
        assertThat(MONOLITH.requests).hasSize(1);
        assertThat(MOVIES.requests).isEmpty();
    }

    @Test
    void transportFailureReturns502WithoutFallback() throws Exception {
        var result = send("POST", "/disconnect", "request".getBytes(StandardCharsets.UTF_8));
        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(MONOLITH.requests).hasSize(1);
        assertThat(MOVIES.requests).isEmpty();
    }

    @Test
    void stripsRequestHeadersNamedByConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            String raw = "POST /api/movies HTTP/1.1\r\nHost: localhost\r\nConnection: close, X-Transient\r\n"
                    + "X-Transient: remove-me\r\nX-End-To-End: keep-me\r\nContent-Length: 3\r\n\r\nabc";
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 201");
        }
        assertThat(MOVIES.requests.element().header("X-Transient")).isEmpty();
        assertThat(MOVIES.requests.element().header("X-End-To-End")).containsExactly("keep-me");
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(local(path)).timeout(Duration.ofSeconds(4))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Stub stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Stub stub = new Stub(server, new ConcurrentLinkedQueue<>());
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> handle(stub, name, exchange));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void handle(Stub stub, String name, HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            var headers = new java.util.HashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((key, value) -> headers.put(key, new ArrayList<>(value)));
            stub.requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body));
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/disconnect")) {
                return;
            }
            if (path.equals("/slow")) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            int status = exchange.getRequestMethod().equals("POST") ? 201 : 200;
            byte[] result = body.length == 0 ? name.getBytes(StandardCharsets.UTF_8) : body;
            if (path.equals("/fail")) {
                status = 422;
                result = "{\"error\":\"rejected\"}".getBytes(StandardCharsets.UTF_8);
            }
            if (path.equals("/redirect")) {
                status = 307;
                exchange.getResponseHeaders().add("Location", "/api/users");
            }
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Set-Cookie", "first=1");
            exchange.getResponseHeaders().add("Set-Cookie", "second=2");
            exchange.getResponseHeaders().add("X-Upstream-Token", "preserved");
            exchange.getResponseHeaders().add("Connection", "X-Transient");
            exchange.getResponseHeaders().add("X-Transient", "remove-me");
            exchange.sendResponseHeaders(status, result.length);
            exchange.getResponseBody().write(result);
        }
    }

    private record Stub(HttpServer server, ConcurrentLinkedQueue<Captured> requests) {
        String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private record Captured(String method, String rawPath, String rawQuery, Map<String, List<String>> headers, byte[] body) {
        List<String> header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst().map(Map.Entry::getValue).orElseGet(List::of);
        }
    }
}
