package ru.cinemaabyss.events;

enum EventType {
    MOVIE("movie", "movie-events"),
    USER("user", "user-events"),
    PAYMENT("payment", "payment-events");

    private final String type;
    private final String topic;

    EventType(String type, String topic) {
        this.type = type;
        this.topic = topic;
    }

    String type() {
        return type;
    }

    String topic() {
        return topic;
    }
}
