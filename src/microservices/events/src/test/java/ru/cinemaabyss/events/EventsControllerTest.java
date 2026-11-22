package ru.cinemaabyss.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventsController.class)
class EventsControllerTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    EventPublisher publisher;

    @Test
    void reportsHealth() throws Exception {
        mvc.perform(get("/api/events/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(true));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @CsvSource({"movie,MOVIE,movie_id,7", "user,USER,user_id,8", "payment,PAYMENT,payment_id,9"})
    void publishesEverySupportedEvent(String path, EventType type, String idField, int id) throws Exception {
        String body = switch (type) {
            case MOVIE -> """
                    {"movie_id":7,"title":"Film","action":"viewed","user_id":8,
                     "rating":8.5,"genres":["Drama"],"description":"Story"}
                    """;
            case USER -> """
                    {"user_id":8,"username":"viewer","email":"viewer@example.com",
                     "action":"logged_in","timestamp":"2026-11-22T12:00:00Z"}
                    """;
            case PAYMENT -> """
                    {"payment_id":9,"user_id":8,"amount":9.99,"status":"completed",
                     "timestamp":"2026-11-22T12:00:00Z","method_type":"credit_card"}
                    """;
        };
        var envelope = new Event("event-id", type.type(), Instant.parse("2026-11-22T12:01:00Z"), mapper.readTree(body));
        when(publisher.publish(eq(type), eq(id), any())).thenReturn(new EventResponse("success", 2, 42, envelope));
        mvc.perform(post("/api/events/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.partition").value(2))
                .andExpect(jsonPath("$.offset").value(42))
                .andExpect(jsonPath("$.event.id").value("event-id"))
                .andExpect(jsonPath("$.event.type").value(type.type()))
                .andExpect(jsonPath("$.event.payload." + idField).value(id));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "movie|{}",
            "movie|{\"movie_id\":1,\"title\":\"Film\"}",
            "movie|{\"movie_id\":1.5,\"title\":\"Film\",\"action\":\"viewed\"}",
            "movie|{\"movie_id\":\"1\",\"title\":\"Film\",\"action\":\"viewed\"}",
            "user|{\"user_id\":1,\"action\":\"registered\"}",
            "user|{\"user_id\":1,\"action\":\"registered\",\"timestamp\":\"yesterday\"}",
            "payment|{\"payment_id\":1,\"user_id\":2,\"status\":\"completed\",\"timestamp\":\"2026-11-22T12:00:00Z\"}",
            "movie|not-json"
    })
    void rejectsInvalidBodiesBeforePublishing(String path, String body) throws Exception {
        mvc.perform(post("/api/events/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(publisher);
    }

    @Test
    void reportsFailureInsteadOfFakeSuccessWhenKafkaDoesNotAcknowledge() throws Exception {
        when(publisher.publish(eq(EventType.MOVIE), eq(1), any()))
                .thenThrow(new EventPublicationException(new TimeoutException()));
        mvc.perform(post("/api/events/movie").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movie_id\":1,\"title\":\"Film\",\"action\":\"viewed\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").isString());
    }
}
