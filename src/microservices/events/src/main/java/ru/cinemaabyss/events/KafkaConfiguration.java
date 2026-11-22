package ru.cinemaabyss.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(topic("movie-events"), topic("user-events"), topic("payment-events"));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        // The MVP logs failed records after the initial attempt plus two retries.
        return new DefaultErrorHandler((record, exception) -> log.error(
                "Failed to process event topic={} partition={} offset={} after retries",
                record.topic(), record.partition(), record.offset(), exception), new FixedBackOff(500L, 2L));
    }
}
