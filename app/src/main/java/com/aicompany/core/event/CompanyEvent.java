package com.aicompany.core.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CompanyEvent(
        String eventId, String eventType, Instant timestamp, String companyId,
        String missionId, String taskId, String agentId, Map<String,Object> data) {
    public static CompanyEvent of(String eventType,String missionId,String taskId,String agentId,Map<String,Object> data){
        return new CompanyEvent(UUID.randomUUID().toString(),eventType,Instant.now(),"AI-COMPANY",missionId,taskId,agentId,data==null?Map.of():data);
    }
}
