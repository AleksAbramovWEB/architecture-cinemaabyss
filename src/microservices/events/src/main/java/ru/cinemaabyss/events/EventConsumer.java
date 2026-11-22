package ru.cinemaabyss.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private final ObjectMapper mapper;

    public EventConsumer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(id = "event-processor", idIsGroup = false,
            topics = {"movie-events", "user-events", "payment-events"})
    public void process(ConsumerRecord<String, String> record) throws JsonProcessingException {
        Event event = mapper.readValue(record.value(), Event.class);
        if (event.id() == null || event.type() == null || event.timestamp() == null || event.payload() == null) {
            throw new IllegalArgumentException("Incomplete event envelope");
        }
        // The MVP's processing action is logging. Never log passwords or the full personal-data payload.
        log.info("Processed event id={} type={} topic={} partition={} offset={}",
                event.id(), event.type(), record.topic(), record.partition(), record.offset());
    }
}
