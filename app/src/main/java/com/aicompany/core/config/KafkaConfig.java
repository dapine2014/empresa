package com.aicompany.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    @Bean
    NewTopic companyEventsTopic(@Value("${company.events.topic:EMPRESA_EVENTS}") String topic){return TopicBuilder.name(topic).partitions(3).replicas(1).build();}
}
