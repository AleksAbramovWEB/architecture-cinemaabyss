package ru.cinemaabyss.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {"movie-events", "user-events", "payment-events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class KafkaEventsIntegrationTest {
    @Autowired
    EventPublisher publisher;
    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void publishesAndProcessesAllThreeEventTypes(CapturedOutput output) {
        var listener = listeners.getListenerContainer("event-processor");
        ContainerTestUtils.waitForAssignment(listener, 3);
        var timestamp = OffsetDateTime.parse("2026-11-22T12:00:00Z");
        var movie = publisher.publish(EventType.MOVIE, 1,
                new EventRequests.Movie(1, "Film", "viewed", 2, null, null, null));
        var user = publisher.publish(EventType.USER, 2,
                new EventRequests.User(2, "viewer", null, "registered", timestamp));
        var payment = publisher.publish(EventType.PAYMENT, 3,
                new EventRequests.Payment(3, 2, new java.math.BigDecimal("9.99"), "completed", timestamp, "credit_card"));

        for (var response : java.util.List.of(movie, user, payment)) {
            assertThat(response.partition()).isZero();
            assertThat(response.offset()).isGreaterThanOrEqualTo(0);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(output.getOut()).contains("Processed event id=" + response.event().id()));
        }
    }
}
