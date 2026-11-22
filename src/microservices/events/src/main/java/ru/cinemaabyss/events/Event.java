package ru.cinemaabyss.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record Event(String id, String type, Instant timestamp, JsonNode payload) {
}
