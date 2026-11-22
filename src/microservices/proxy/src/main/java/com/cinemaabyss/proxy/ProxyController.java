package com.cinemaabyss.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
public class ProxyController {
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private static final String UPSTREAM_HEADER = "X-Cinemaabyss-Upstream";
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "proxy-connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "http2-settings");
    private static final Set<String> MANAGED_REQUEST_HEADERS = Set.of("host", "content-length", "expect");

    private final HttpClient httpClient;
    private final ProxyProperties properties;
    private final UpstreamRouter router;

    public ProxyController(HttpClient httpClient, ProxyProperties properties, UpstreamRouter router) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.router = router;
    }

    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "Strangler Fig Proxy is healthy";
    }

    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var upstream = router.route(request.getRequestURI());
        log.info("Proxy request method={} path={} upstream={}", request.getMethod(), request.getRequestURI(), upstream.name());
        try {
            var builder = HttpRequest.newBuilder(upstream.requestUri(request.getRequestURI(), request.getQueryString()))
                    .timeout(properties.requestTimeout());
            Set<String> excluded = excludedHeaders(Collections.list(request.getHeaders("Connection")));
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!excluded.contains(lower) && !MANAGED_REQUEST_HEADERS.contains(lower)) {
                    for (String value : Collections.list(request.getHeaders(name))) {
                        builder.header(name, value);
                    }
                }
            }
            byte[] body = request.getInputStream().readAllBytes();
            builder.method(request.getMethod(), body.length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            // No application retry/fallback: retrying a write could create a duplicate.
            HttpResponse<byte[]> result = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(result.statusCode());
            Set<String> excludedResponse = excludedHeaders(result.headers().allValues("Connection"));
            for (Map.Entry<String, List<String>> header : result.headers().map().entrySet()) {
                String name = header.getKey();
                if (!excludedResponse.contains(name.toLowerCase(Locale.ROOT))
                        && !"content-length".equalsIgnoreCase(name)) {
                    for (String value : header.getValue()) {
                        response.addHeader(name, value);
                    }
                }
            }
            response.setHeader(UPSTREAM_HEADER, upstream.name());
            if ("HEAD".equals(request.getMethod()) || result.statusCode() == 304) {
                result.headers().firstValue("Content-Length").ifPresent(value -> response.setHeader("Content-Length", value));
            } else if (result.statusCode() != 204) {
                response.setContentLengthLong(result.body().length);
                response.getOutputStream().write(result.body());
            }
        } catch (HttpTimeoutException e) {
            log.warn("Upstream timeout: {}", upstream.name());
            error(response, upstream.name(), 504, "Upstream request timed out");
        } catch (IOException e) {
            log.warn("Upstream request failed: {} ({})", upstream.name(), e.getClass().getSimpleName());
            error(response, upstream.name(), 502, "Upstream request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(response, upstream.name(), 503, "Proxy is shutting down");
        }
    }

    private static Set<String> excludedHeaders(List<String> connectionValues) {
        Set<String> excluded = new HashSet<>(HOP_BY_HOP);
        for (String value : connectionValues) {
            Arrays.stream(value.split(",")).map(String::trim).map(name -> name.toLowerCase(Locale.ROOT)).forEach(excluded::add);
        }
        return excluded;
    }

    private static void error(HttpServletResponse response, String upstream, int status, String message) throws IOException {
        response.reset();
        response.setStatus(status);
        response.setHeader(UPSTREAM_HEADER, upstream);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
