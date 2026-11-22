package ru.cinemaabyss.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class EventPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsBrokerMetadataAndPublishesCompleteEnvelopeWithEntityKey() throws Exception {
        var metadata = new RecordMetadata(new TopicPartition("movie-events", 2), 42, 0, 0L, 0, 0);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>("movie-events", "7", "body"), metadata)));
        var publisher = new EventPublisher(kafka, mapper, clock, Duration.ofSeconds(1));
        var input = new EventRequests.Movie(7, "Film", "rated", 8, new BigDecimal("8.5"), List.of("Drama"), "Story");

        EventResponse response = publisher.publish(EventType.MOVIE, 7, input);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.partition()).isEqualTo(2);
        assertThat(response.offset()).isEqualTo(42);
        assertThat(UUID.fromString(response.event().id())).isNotNull();
        assertThat(response.event().timestamp()).isEqualTo(clock.instant());
        var json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("movie-events"), org.mockito.ArgumentMatchers.eq("7"), json.capture());
        var sent = mapper.readTree(json.getValue());
        assertThat(sent.path("id").asText()).isEqualTo(response.event().id());
        assertThat(sent.path("type").asText()).isEqualTo("movie");
        assertThat(sent.path("timestamp").asText()).isEqualTo("2026-11-22T12:00:00Z");
        assertThat(sent.path("payload").path("movie_id").asInt()).isEqualTo(7);
        assertThat(sent.path("payload").path("genres").get(0).asText()).isEqualTo("Drama");
        assertThat(sent.path("payload").path("rating").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(sent.path("payload").path("description").asText()).isEqualTo("Story");
    }

    @Test
    void failsWhenBrokerRejectsPublication() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        var publisher = new EventPublisher(kafka, mapper, clock, Duration.ofSeconds(1));
        assertThatThrownBy(() -> publisher.publish(EventType.USER, 7, mapper.createObjectNode()))
                .isInstanceOf(EventPublicationException.class);
    }

    @Test
    void boundsWaitForBrokerAcknowledgement() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        var publisher = new EventPublisher(kafka, mapper, clock, Duration.ofMillis(5));
        assertThatThrownBy(() -> publisher.publish(EventType.PAYMENT, 7, mapper.createObjectNode()))
                .isInstanceOf(EventPublicationException.class).hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
