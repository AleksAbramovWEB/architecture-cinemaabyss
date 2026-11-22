package ru.cinemaabyss.events;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventsController {
    private final EventPublisher publisher;

    public EventsController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("status", true);
    }

    @PostMapping("/movie")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse movie(@Valid @RequestBody EventRequests.Movie request) {
        return publisher.publish(EventType.MOVIE, request.movieId(), request);
    }

    @PostMapping("/user")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse user(@Valid @RequestBody EventRequests.User request) {
        return publisher.publish(EventType.USER, request.userId(), request);
    }

    @PostMapping("/payment")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse payment(@Valid @RequestBody EventRequests.Payment request) {
        return publisher.publish(EventType.PAYMENT, request.paymentId(), request);
    }
}
