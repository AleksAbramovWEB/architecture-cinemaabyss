package ru.cinemaabyss.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

final class EventRequests {
    private EventRequests() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Movie(
            @NotNull Integer movieId,
            @NotBlank String title,
            @NotBlank String action,
            Integer userId,
            BigDecimal rating,
            List<String> genres,
            String description) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record User(
            @NotNull Integer userId,
            String username,
            String email,
            @NotBlank String action,
            @NotNull OffsetDateTime timestamp) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Payment(
            @NotNull Integer paymentId,
            @NotNull Integer userId,
            @NotNull BigDecimal amount,
            @NotBlank String status,
            @NotNull OffsetDateTime timestamp,
            String methodType) {
    }
}
