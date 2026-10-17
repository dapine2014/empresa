package com.aicompany.core.event;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bitácora (spec 2026-10-17): el publicador guarda el evento antes de Kafka y una falla de Neo4j no lo frena. */
class CompanyEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final MissionEventLog log = mock(MissionEventLog.class);
    private final CompanyEventPublisher publisher = new CompanyEventPublisher(kafka, JsonMapper.builder().build(), "EMPRESA_EVENTS");

    {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        publisher.setMissionEventLog(log);
    }

    @Test void aMissionEventIsRecordedAndSent() {
        publisher.publish("EMPRESA_SANDBOX_STARTED", "M-1", null, "sandbox", Map.of("round", 0));
        verify(log).record(argThat(e -> "M-1".equals(e.missionId()) && "EMPRESA_SANDBOX_STARTED".equals(e.eventType())));
        verify(kafka).send(eq("EMPRESA_EVENTS"), eq("M-1"), anyString());
    }

    // Neo4j caído no frena el evento.
    @Test void aLogFailureStillSendsToKafka() {
        doThrow(new RuntimeException("neo4j down")).when(log).record(any());
        assertDoesNotThrow(() -> publisher.publish("EMPRESA_TASK_COMMITTED", "M-1", "T-1", "qa", Map.of()));
        verify(kafka).send(eq("EMPRESA_EVENTS"), eq("M-1"), anyString());
    }

    @Test void eventsWithoutMissionAreNotRecorded() {
        publisher.publish("EMPRESA_MODEL_DOWN", null, null, "system", Map.of("model", "x"));
        verify(log, never()).record(any());
        verify(kafka).send(eq("EMPRESA_EVENTS"), anyString(), anyString());
    }

    @Test void roundAndIncrementComeFromTheData() {
        assertEquals(3, MissionEventLog.roundOf(Map.of("round", 3)));
        assertEquals(2, MissionEventLog.roundOf(Map.of("repairRound", 2)));
        assertNull(MissionEventLog.roundOf(Map.of()));
        assertEquals(2, MissionEventLog.incrementOf(Map.of("increment", 2)));
        assertNull(MissionEventLog.incrementOf(Map.of("increment", "x")));
    }

    @Test void agentNameFallsBackToTheId() {
        assertEquals("Neo", MissionEventLog.nameOf(Map.of("engineering", "Neo"), "engineering"));
        assertEquals("qa", MissionEventLog.nameOf(Map.of(), "qa"));
        assertNull(MissionEventLog.nameOf(Map.of(), null));
    }
}
