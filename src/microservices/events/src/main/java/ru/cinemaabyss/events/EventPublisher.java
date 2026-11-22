package ru.cinemaabyss.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, Clock clock,
            @Value("${events.publish-timeout:5s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("events.publish-timeout must be positive");
        }
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
        this.timeout = timeout;
    }

    EventResponse publish(EventType type, Integer entityId, Object payload) {
        var event = new Event(UUID.randomUUID().toString(), type.type(), clock.instant(), mapper.valueToTree(payload));
        try {
            String json = mapper.writeValueAsString(event);
            var result = kafka.send(type.topic(), String.valueOf(entityId), json)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var metadata = result.getRecordMetadata();
            log.info("Published event id={} type={} topic={} partition={} offset={}",
                    event.id(), event.type(), metadata.topic(), metadata.partition(), metadata.offset());
            return new EventResponse("success", metadata.partition(), metadata.offset(), event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(event, e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException | RuntimeException e) {
            throw failed(event, e);
        }
    }

    private EventPublicationException failed(Event event, Exception cause) {
        log.error("Failed to publish event id={} type={}", event.id(), event.type(), cause);
        return new EventPublicationException(cause);
    }
}
