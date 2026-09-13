package com.aicompany.core.event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CompanyEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(CompanyEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper mapper;
    private final String topic;

    public CompanyEventPublisher(
            KafkaTemplate<String, String> kafka,
            JsonMapper mapper,
            @Value("${company.events.topic:EMPRESA_EVENTS}") String topic) {

        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
    }

    public void publish(
            String eventType,
            String missionId,
            String taskId,
            String agentId,
            Map<String, Object> data) {

        if (eventType == null || !eventType.startsWith("EMPRESA_")) {
            throw new IllegalArgumentException(
                    "Todos los eventos de AI Company deben iniciar con EMPRESA_");
        }

        var event = CompanyEvent.of(
                eventType,
                missionId,
                taskId,
                agentId,
                data
        );

        final String payload;

        try {
            payload = mapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            log.error(
                    "No se pudo serializar eventId={}",
                    event.eventId(),
                    ex
            );
            return;
        }

        kafka.send(
                topic,
                missionId != null ? missionId : event.eventId(),
                payload
        ).whenComplete((result, error) -> {

            if (error != null) {

                log.error(
                        "No se pudo publicar {} eventId={}",
                        event.eventType(),
                        event.eventId(),
                        error
                );

            } else {

                log.debug(
                        "Publicado {} eventId={} partition={} offset={}",
                        event.eventType(),
                        event.eventId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset()
                );
            }
        });
    }

    public void publishMission(
            String eventType,
            String missionId,
            String status,
            int progress,
            String currentStep,
            String message) {

        publish(
                eventType,
                missionId,
                null,
                "ceo",
                Map.of(
                        "status", status,
                        "progress", progress,
                        "currentStep", currentStep,
                        "message", message == null ? "" : message
                )
        );
    }

    public void publishTask(
            String eventType,
            String taskId,
            String missionId,
            String agentId,
            String status,
            String result) {

        publish(
                eventType,
                missionId,
                taskId,
                agentId,
                Map.of(
                        "status", status,
                        "result", result == null ? "" : result
                )
        );
    }
}
