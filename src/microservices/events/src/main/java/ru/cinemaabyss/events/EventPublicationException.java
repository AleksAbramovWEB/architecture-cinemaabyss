package ru.cinemaabyss.events;

final class EventPublicationException extends RuntimeException {
    EventPublicationException(Throwable cause) {
        super("Unable to confirm event publication to Kafka", cause);
    }
}
