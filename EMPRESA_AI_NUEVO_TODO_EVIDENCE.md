# Empresa AI — Nuevo TODO, Roadmap y Código de Implementación

## 1. Propósito

Este documento consolida el estado actual del MVP y **todo lo nuevo que toca implementar**, incluyendo código base de referencia para:

- Retry / Recovery de agentes.
- Evidence Validation Gate.
- Evidence Acquisition real.
- Búsqueda y lectura de fuentes web.
- Creación de `EvidenceCandidate`.
- Validación y persistencia de evidencia.
- Integración con agentes.
- Re-evaluación de oportunidades.
- Customer Validation, venta y beneficio.
- Memoria empresarial Neo4j.
- Kafka y observabilidad.
- Tests y pruebas E2E.

> **Regla:** los fragmentos de código de este documento son una base de implementación alineada con el código actual del proyecto. Antes de copiarlos, conservar los paquetes/imports/modelos que ya existan y adaptar únicamente las diferencias de nombres o firmas.

---

# 2. Principio de negocio

La empresa es real. Los agentes son los operadores de la empresa.

El objetivo de 60 días es demostrar al menos un negocio legal que:

1. pueda comenzar con máximo US$50;
2. consiga clientes reales;
3. genere una operación real;
4. tenga ingresos y costos demostrables;
5. produzca `netProfitUsd > 50`.

La regla fundamental continúa siendo:

> **La IA puede operar la empresa, pero el mercado decide si la empresa merece existir.**

---

# 3. Estado actual comprobado

## 3.1 Infraestructura

- `company-core` funcionando.
- Java 21.
- Spring Boot funcionando.
- Ollama con GPU.
- Agentes con `qwen2.5-coder:7b`.
- CEO con `qwen2.5-coder:14b`.
- Neo4j funcionando.
- Kafka funcionando.
- Docker network `ai-company-net` funcionando.

## 3.2 Orquestación

- `MissionExecutor` operativo.
- 5 agentes paralelos:
  - `sales`
  - `product`
  - `finance`
  - `engineering`
  - `qa`
- estados hasta `AWAITING_INVESTOR`.
- eventos `EMPRESA_*`.

## 3.3 Contrato de agentes

Completado:

- `AgentResult` estructurado.
- `LenientStringDeserializer`.
- `AgentResultSchema`.
- `AgentResultValidator`.
- validación de cálculos.
- validación de evidencia.
- `verificationStatus`.
- separación `facts / hypotheses / estimates / evidence / evidenceRequired`.

## 3.4 Calidad

Completado:

- `ContradictionDetector`.
- detección de inconsistencias entre agentes.
- detección de confianza alta con `NOT_VALIDATED`.
- detección de contaminación facts/hypotheses.
- `EvidenceValidationGate`.
- QA como quinto agente.

## 3.5 Retry / Recovery

Completado y probado:

- hasta 3 intentos.
- retry ante respuesta no parseable.
- retry ante rechazo de `AgentResultValidator`.
- feedback al siguiente intento.
- `EMPRESA_TASK_RETRY`.
- agotamiento de intentos => `FAILED`.

Prueba real:

```text
Engineering intento 1 -> inválido
Engineering intento 2 -> válido
```

## 3.6 Evidencia

Completado:

- `EvidenceValidationGate`.
- `Evidence` en Neo4j.
- relación `HAS_EVIDENCE`.
- `sourceType` válido.
- `NONE + verified=true` rechazado.
- `WEB + verified=true` exige URL con forma válida.

**Pendiente:** adquisición automática de evidencia externa.

## 3.7 Customer / Transaction

Implementado y probado:

- `CustomerController`.
- `CustomerService`.
- `CustomerMemoryService`.
- cliente.
- transacción.
- evidencia obligatoria.
- `netProfitUsd`.
- Neo4j.

## 3.8 Neo4j

Groundwork implementado:

- constraints de identidad para las entidades empresariales.

Pendiente:

- conectar Role, Agent, Mission, Opportunity, Customer, Decision, Transaction, Lesson, Strategy, Prediction, CapitalAllocation, Tool, Model, Portfolio, etc. con los flujos reales.

## 3.9 Kafka

Implementado:

- `EMPRESA_MISSION_CREATED`.
- actualizaciones de misión.
- eventos de fallo.
- prueba real del topic.

---

# 4. Evidencia obtenida en las últimas pruebas

## `MVP-BUSINESS-001`

La empresa seleccionó:

> Servicio de Asesoramiento Empresarial Virtual para Microempresarios Colombianos.

Resultado: `NO-GO` por falta de evidencia verificable.

## `MVP-EVIDENCE-001`

Resultado: `NO-GO`.

Conclusión: el sistema puede reconocer que no tiene evidencia suficiente.

## `MVP-EVIDENCE-002`

Falló un agente por cálculo inconsistente.

Esto demostró la función del Validator, pero también justificó el Retry / Recovery.

## `MVP-EVIDENCE-003`

Demostró Retry / Recovery real y finalización de una misión con 5 agentes.

---

# 5. Problema actual

El sistema ya puede validar evidencia que recibe, pero **todavía no posee una capacidad completa de adquisición de evidencia web**.

Actualmente tenemos:

```text
Agent
  ↓
AgentResult
  ↓
EvidenceValidationGate
  ↓
Neo4j
```

Necesitamos:

```text
Agent
  ↓
EvidenceAcquisitionService
  ↓
WebSearchClient
  ↓
Web Fetch
  ↓
EvidenceCandidate
  ↓
EvidenceValidationGate
  ↓
Neo4j
  ↓
CEO
```

---

# 6. Objetivo nuevo: Evidence Acquisition Engine

## 6.1 Responsabilidades

El nuevo componente debe poder:

1. recibir una consulta;
2. buscar fuentes web;
3. obtener URL, título y resumen;
4. recuperar el contenido de la URL cuando sea necesario;
5. producir candidatos de evidencia;
6. validar formato y fuente;
7. persistir evidencia aceptada;
8. entregar evidencia al CEO.

## 6.2 Regla de verificación

Una página encontrada no equivale automáticamente a evidencia verificada.

Debe existir el flujo:

```text
SEARCH
  ↓
candidate
  ↓
FETCH
  ↓
contenido disponible
  ↓
dato concreto
  ↓
Evidence
  ↓
Validation
  ↓
verified=true solo si corresponde
```

Nunca convertir una hipótesis en `verified=true` simplemente porque un buscador devolvió una URL.

---

# 7. Paquetes nuevos

Crear:

```text
src/main/java/com/aicompany/core/evidence/
```

Archivos:

```text
EvidenceAcquisitionService.java
WebSearchClient.java
WebSearchResult.java
EvidenceCandidate.java
WebPageFetcher.java
```

Opcionalmente:

```text
EvidenceVerificationService.java
EvidenceAcquisitionException.java
```

---

# 8. Código — modelos

## 8.1 `WebSearchResult.java`

```java
package com.aicompany.core.evidence;

public record WebSearchResult(
        String title,
        String url,
        String description
) {
}
```

## 8.2 `EvidenceCandidate.java`

```java
package com.aicompany.core.evidence;

public record EvidenceCandidate(
        String description,
        String source,
        String sourceType,
        boolean verified
) {
}
```

> Para una primera iteración, `verified` debe comenzar en `false` al salir del buscador.

---

# 9. Código — WebSearchClient

## 9.1 Configuración

En `application.yml`:

```yaml
evidence:
  web-search:
    base-url: ${EVIDENCE_WEB_SEARCH_BASE_URL:https://api.search.brave.com}
    api-key: ${EVIDENCE_WEB_SEARCH_API_KEY:}
    country: ${EVIDENCE_WEB_SEARCH_COUNTRY:CO}
    language: ${EVIDENCE_WEB_SEARCH_LANGUAGE:es}
```

Nunca hardcodear la API key.

## 9.2 Cliente

```java
package com.aicompany.core.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class WebSearchClient {

    private final RestClient client;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String country;
    private final String language;

    public WebSearchClient(
            @Value("${evidence.web-search.base-url}") String baseUrl,
            @Value("${evidence.web-search.api-key}") String apiKey,
            @Value("${evidence.web-search.country:CO}") String country,
            @Value("${evidence.web-search.language:es}") String language,
            JsonMapper jsonMapper) {

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
        this.country = country;
        this.language = language;
    }

    public List<WebSearchResult> search(String query, int count) {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query es obligatorio");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "EVIDENCE_WEB_SEARCH_API_KEY no está configurada");
        }

        var response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/web/search")
                        .queryParam("q", query)
                        .queryParam("country", country)
                        .queryParam("search_lang", language)
                        .queryParam("count", Math.min(Math.max(count, 1), 20))
                        .build())
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .retrieve()
                .body(String.class);

        return parseResults(response);
    }

    private List<WebSearchResult> parseResults(String response) {

        try {
            JsonNode root = jsonMapper.readTree(response);
            JsonNode results = root.path("web").path("results");

            var output = new ArrayList<WebSearchResult>();

            if (!results.isArray()) {
                return output;
            }

            for (JsonNode item : results) {
                var title = item.path("title").asString(null);
                var url = item.path("url").asString(null);
                var description = item.path("description").asString(null);

                if (url != null && !url.isBlank()) {
                    output.add(new WebSearchResult(
                            title,
                            url,
                            description));
                }
            }

            return List.copyOf(output);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No se pudo interpretar la respuesta del buscador", ex);
        }
    }
}
```

> Ajustar los nombres `JsonNode`/métodos de lectura si la versión exacta de Jackson del proyecto expone una API diferente. El proyecto actual usa Jackson 3 / `tools.jackson`.

---

# 10. Código — WebPageFetcher

El buscador solo entrega candidatos. Para poder verificar debemos intentar recuperar la página.

```java
package com.aicompany.core.evidence;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebPageFetcher {

    private final RestClient client;

    public WebPageFetcher() {
        this.client = RestClient.builder()
                .build();
    }

    public String fetch(String url) {

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url es obligatoria");
        }

        return client.get()
                .uri(url)
                .header("User-Agent", "AI-Company-EvidenceBot/1.0")
                .retrieve()
                .body(String.class);
    }
}
```

### Seguridad mínima del fetcher

Antes de usarlo en producción, agregar:

- timeout de conexión;
- timeout de lectura;
- límite de tamaño de respuesta;
- allowlist/denylist si corresponde;
- protección SSRF;
- bloqueo de `localhost`, IPs privadas y metadata endpoints;
- validación estricta de `http/https`.

**El fetcher no debe poder conectarse indiscriminadamente a la red interna.**

---

# 11. Código — EvidenceAcquisitionService

```java
package com.aicompany.core.evidence;

import com.aicompany.core.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EvidenceAcquisitionService {

    private final WebSearchClient searchClient;
    private final WebPageFetcher pageFetcher;
    private final EvidenceValidationGate evidenceGate;
    private final MissionMemoryService memory;

    public EvidenceAcquisitionService(
            WebSearchClient searchClient,
            WebPageFetcher pageFetcher,
            EvidenceValidationGate evidenceGate,
            MissionMemoryService memory) {

        this.searchClient = searchClient;
        this.pageFetcher = pageFetcher;
        this.evidenceGate = evidenceGate;
        this.memory = memory;
    }

    public List<AgentResult.Evidence> searchEvidence(
            String query,
            String missionId,
            String agentId) {

        var results = searchClient.search(query, 10);

        var candidates = results.stream()
                .map(result -> new AgentResult.Evidence(
                        result.title() + ": " + safe(result.description()),
                        result.url(),
                        "WEB",
                        false
                ))
                .toList();

        var validation = evidenceGate.validate(candidates);

        if (!validation.valid()) {
            throw new IllegalStateException(
                    "Evidencia adquirida inválida: "
                            + String.join("; ", validation.errors()));
        }

        return candidates;
    }

    public AgentResult.Evidence verifyUrl(
            String url,
            String description) {

        var content = pageFetcher.fetch(url);

        if (content == null || content.isBlank()) {
            throw new IllegalStateException(
                    "La URL no devolvió contenido: " + url);
        }

        // En la primera versión solo demostramos que:
        // 1) la URL existe y responde;
        // 2) hay contenido recuperable.
        // La verificación semántica del dato debe evolucionar después.
        return new AgentResult.Evidence(
                description,
                url,
                "WEB",
                true
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
```

> **Importante:** `verifyUrl(...)->verified=true` en este ejemplo representa únicamente una verificación técnica mínima de recuperación de fuente. Para afirmar que el **dato concreto** está verificado, necesitamos una segunda capa semántica. Esa parte debe implementarse antes de usar `verified=true` como prueba de una afirmación empresarial.

---

# 12. Mejor diseño recomendado: separar candidate y verified

Para evitar ambigüedad, la evolución recomendada es:

```java
public record EvidenceCandidate(
        String claim,
        String url,
        String title,
        String snippet,
        String sourceType
) {
}
```

y después:

```java
public record VerifiedEvidence(
        String claim,
        String source,
        String sourceType,
        boolean verified,
        String verificationMethod
) {
}
```

Flujo:

```text
SearchResult
   ↓
EvidenceCandidate
   ↓
Fetch
   ↓
Evidence Verification
   ↓
VerifiedEvidence
   ↓
AgentResult.Evidence
   ↓
Neo4j
```

Esto es más seguro que marcar inmediatamente un resultado de buscador como evidencia verificada.

---

# 13. Integración con AgentRuntime

El agente no debería recibir automáticamente todo Internet en el prompt.

El patrón recomendado es:

```text
Agent
  ↓
request tool
  ↓
EvidenceAcquisitionService
  ↓
resultados resumidos
  ↓
Agent
  ↓
AgentResult
```

Una primera interfaz:

```java
package com.aicompany.core.evidence;

import com.aicompany.core.AgentResult;

import java.util.List;

public interface EvidenceTool {

    List<AgentResult.Evidence> search(
            String query,
            String missionId,
            String agentId);
}
```

Implementación:

```java
package com.aicompany.core.evidence;

import com.aicompany.core.AgentResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebEvidenceTool implements EvidenceTool {

    private final EvidenceAcquisitionService acquisitionService;

    public WebEvidenceTool(EvidenceAcquisitionService acquisitionService) {
        this.acquisitionService = acquisitionService;
    }

    @Override
    public List<AgentResult.Evidence> search(
            String query,
            String missionId,
            String agentId) {

        return acquisitionService.searchEvidence(
                query,
                missionId,
                agentId);
    }
}
```

---

# 14. Integración con el LLM

La integración definitiva debe usar tool/function calling si está habilitado en la capa Ollama del proyecto.

Contrato conceptual:

```json
{
  "name": "search_web_evidence",
  "description": "Busca fuentes web públicas para validar una afirmación empresarial.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string"
      }
    },
    "required": ["query"]
  }
}
```

El modelo debería poder emitir una solicitud equivalente a:

```json
{
  "name": "search_web_evidence",
  "arguments": {
    "query": "precios asesoría empresarial para microempresas Colombia"
  }
}
```

La aplicación ejecuta el tool y devuelve el resultado al modelo.

**No confiar en que el LLM invente la URL. La URL debe venir del tool.**

---

# 15. Código — persistencia de evidencia

Ya existe `MissionMemoryService.recordEvidence(...)`.

La integración debe asegurar que:

```text
EvidenceAcquisitionService
        ↓
EvidenceValidationGate
        ↓
MissionMemoryService.recordEvidence
        ↓
(:Evidence)
        ↓
(:Task)-[:HAS_EVIDENCE]->(:Evidence)
```

La persistencia debe conservar al menos:

```text
id
missionId
agentId
description
source
sourceType
verified
createdAt
```

Recomendación futura:

```text
claim
verificationMethod
retrievedAt
contentHash
```

`contentHash` será útil para demostrar que la fuente almacenada corresponde al contenido recuperado en una determinada fecha.

---

# 16. Refuerzo de EvidenceValidationGate

Actualmente el gate valida:

- `sourceType`.
- `NONE` no puede estar verificado.
- `WEB` verificado requiere URL.

Siguiente evolución:

```text
WEB + verified=true
    ↓
URL válida
    ↓
GET exitoso
    ↓
contenido no vacío
    ↓
fecha de recuperación
    ↓
verificación semántica
```

Nuevas reglas recomendadas:

1. `WEB verified=true` requiere `https://` preferentemente.
2. bloquear URLs privadas/locales.
3. guardar `retrievedAt`.
4. guardar `verificationMethod`.
5. detectar fuentes duplicadas.
6. distinguir `search-result` de `page-verified`.

---

# 17. Evidence deduplication

Crear una clave estable:

```text
SHA-256(source URL normalizada + claim normalizado)
```

Ejemplo:

```java
public String evidenceId(String source, String claim) {
    return sha256(
            normalize(source)
                    + "|"
                    + normalize(claim));
}
```

Esto evita guardar la misma evidencia diez veces porque distintos agentes encontraron la misma fuente.

---

# 18. Evidence Scoring

Agregar después:

```text
EvidenceScore
```

Factores sugeridos:

```text
Fuente oficial                 +30
Fuente primaria                +25
Cliente/Transaction real       +30
Fuente secundaria              +15
URL recuperable                +10
Dato concreto                  +10
Fuente desactualizada          -10
Snippet sin página             -15
Fuente duplicada               -20
```

No permitir que el score convierta por sí mismo una hipótesis en un hecho.

---

# 19. Customer Validation

El flujo ya existe parcialmente.

Ahora hay que conectarlo con Evidence:

```text
Opportunity
    ↓
Customer candidate
    ↓
contact
    ↓
interés real
    ↓
offer
    ↓
transaction
    ↓
Evidence CUSTOMER / TRANSACTION
    ↓
Neo4j
```

La empresa debe poder diferenciar:

```text
LEAD
PROSPECT
CUSTOMER
PAYING_CUSTOMER
```

---

# 20. Transaction / Profit Gate

Nunca pedir al LLM que determine por sí solo el beneficio final.

Debe calcularse en código:

```java
public double netProfit(
        double revenue,
        double directCosts,
        double acquisitionCosts,
        double otherCosts) {

    return revenue
            - directCosts
            - acquisitionCosts
            - otherCosts;
}
```

El criterio de éxito empresarial:

```text
netProfitUsd > 50
```

La fórmula debe ser determinista.

---

# 21. Mejorar MissionExecutor: Agent Failure != Mission Failure

Actualmente, si un agente agota sus retries, la misión cae.

Siguiente evolución:

```text
Agent A ✅
Agent B ✅
Agent C ❌ agotó retries
Agent D ✅
Agent QA ✅
       ↓
Resultado parcial
       ↓
QA / CEO decide
```

Crear un resultado de recuperación:

```java
public record AgentExecutionOutcome(
        String agentId,
        boolean completed,
        boolean recoverable,
        int attempts,
        AgentResult result,
        List<String> errors
) {
}
```

La misión no debería morir automáticamente por un solo agente no recuperable.

---

# 22. Eventos nuevos

Mantener siempre el prefijo `EMPRESA_`.

Eventos recomendados:

```text
EMPRESA_TASK_RETRY
EMPRESA_EVIDENCE_SEARCH_STARTED
EMPRESA_EVIDENCE_SEARCH_COMPLETED
EMPRESA_EVIDENCE_CANDIDATE_CREATED
EMPRESA_EVIDENCE_VERIFIED
EMPRESA_EVIDENCE_REJECTED
EMPRESA_AGENT_DEGRADED
EMPRESA_MISSION_REPLANNED
EMPRESA_CUSTOMER_CREATED
EMPRESA_TRANSACTION_CREATED
EMPRESA_PROFIT_CALCULATED
```

Topic principal:

```text
EMPRESA_EVENTS
```

---

# 23. Modelo Neo4j objetivo

```text
(:Company)
   |
   +--(:Mission)
   |      |
   |      +--(:Agent)
   |      |      |
   |      |      +--(:Evidence)
   |      |
   |      +--(:Opportunity)
   |             |
   |             +--(:Customer)
   |                    |
   |                    +--(:Transaction)
   |                           |
   |                           +--(:Evidence)
   |
   +--(:Decision)
   |
   +--(:Strategy)
   |
   +--(:Lesson)
   |
   +--(:Prediction)
```

La memoria futura debe permitir preguntas como:

```cypher
MATCH (o:Opportunity)-[:SUPPORTED_BY]->(e:Evidence)
RETURN o, collect(e);
```

```cypher
MATCH (c:Customer)-[:MADE]->(t:Transaction)
RETURN c, t, t.netProfitUsd;
```

---

# 24. Tests nuevos

## 24.1 WebSearchClientTest

Debe comprobar:

- query vacía => reject.
- API key ausente => reject.
- respuesta válida => parse correcto.
- resultado sin URL => ignorado/rechazado.
- respuesta inválida => excepción controlada.

## 24.2 EvidenceAcquisitionServiceTest

Casos:

```text
search -> candidatos
search -> cero resultados
verify URL -> contenido
verify URL -> error HTTP
```

## 24.3 EvidenceSecurityTest

Obligatorio:

```text
http://localhost
http://127.0.0.1
http://169.254.169.254
http://10.x.x.x
http://172.16.x.x
http://192.168.x.x
```

deben bloquearse en el fetcher.

## 24.4 Evidence dedup test

Misma URL + mismo claim:

```text
2 entradas
  ↓
1 Evidence
```

## 24.5 AgentRuntime retry test

Ya existe y debe conservarse:

```text
invalid
 ↓
retry
 ↓
valid
```

## 24.6 E2E

```text
Mission
 ↓
5 agents
 ↓
Evidence tool
 ↓
Evidence Gate
 ↓
Neo4j
 ↓
CEO
 ↓
AWAITING_INVESTOR
```

---

# 25. Nueva prueba: MVP-EVIDENCE-004

Objetivo:

> Demostrar que la empresa puede adquirir al menos una fuente web real, transformarla en evidencia candidata, validarla y persistirla en Neo4j.

Criterio mínimo:

```text
Search real               ✅
URL real                  ✅
Fetch real                ✅
EvidenceCandidate         ✅
EvidenceValidationGate    ✅
Neo4j                     ✅
CEO receives evidence    ✅
```

## Comando de prueba

```bash
curl -s -X POST http://localhost:8081/api/company/missions \
  -H 'Content-Type: application/json' \
  -d '{
    "missionId": "MVP-EVIDENCE-004",
    "instruction": "Adquiere evidencia web real para validar la oportunidad Servicio de Asesoramiento Empresarial Virtual para Microempresarios Colombianos. Usa la herramienta web disponible. No inventes URLs ni fuentes. Busca al menos una fuente concreta sobre demanda, competencia o precios. Registra la fuente y describe exactamente qué afirma. Separa candidate evidence de verified evidence. No gastar dinero. No contactar clientes.
  }' | jq
```

## Logs

```bash
docker logs ai-company-core --since 15m 2>&1 \
  | grep -E 'MVP-EVIDENCE-004|EVIDENCE_SEARCH|EVIDENCE_CANDIDATE|EVIDENCE_VERIFIED|EVIDENCE_REJECTED|AGENT_RESULT_|AWAITING_INVESTOR|FAILED'
```

## Neo4j

```cypher
MATCH (e:Evidence)
WHERE e.missionId = 'MVP-EVIDENCE-004'
RETURN e
ORDER BY e.createdAt;
```

---

# 26. Nueva prueba: MVP-CUSTOMER-001

Solo después de pasar `MVP-EVIDENCE-004`.

Objetivo:

```text
Evidence
  ↓
GO
  ↓
Customer real
  ↓
Offer
  ↓
Transaction real
```

No usar clientes ficticios.

---

# 27. Nueva prueba definitiva: MVP-BUSINESS-002

Después de que Evidence Acquisition y Customer Validation estén operativos:

```text
CEO descubre
   ↓
Evidence Acquisition
   ↓
QA
   ↓
CEO GO
   ↓
Investor approval
   ↓
Offer
   ↓
Customer real
   ↓
Transaction
   ↓
Revenue
   ↓
Costs
   ↓
Net Profit
```

Criterio final:

```text
netProfitUsd > 50
```

---

# 28. Seguridad de las herramientas

Antes de permitir que los agentes usen herramientas reales:

- aplicar allowlist de herramientas.
- registrar quién invocó la herramienta.
- registrar query y parámetros.
- limitar frecuencia.
- limitar timeout.
- limitar tamaño de respuesta.
- bloquear SSRF.
- evitar ejecución arbitraria de URLs.
- mantener API keys solo en variables/secret store.
- auditar cada llamada en Kafka y/o Neo4j.

Registro recomendado:

```text
ToolInvocation
  id
  missionId
  agentId
  tool
  argumentsHash
  startedAt
  finishedAt
  status
```

---

# 29. Observabilidad

Ya tenemos métricas de Ollama.

Agregar métricas para herramientas:

```text
evidence.search.count
evidence.search.errors
evidence.fetch.count
evidence.fetch.errors
evidence.verified.count
evidence.rejected.count
evidence.duplicate.count
agent.retry.count
agent.retry.success
agent.retry.exhausted
```

---

# 30. Rendimiento

El cuello de botella actual sigue siendo el CEO 14B.

En pruebas anteriores:

```text
7B ≈ 70 tok/s
14B ≈ 4–5 tok/s
```

Por eso:

- mantener prompts compactos.
- reducir información duplicada.
- pasar al CEO solo evidencia relevante.
- resumir fuentes antes de consolidar.
- no enviar HTML completo al CEO.
- separar adquisición y síntesis.

Arquitectura recomendada:

```text
WEB
 ↓
Fetcher
 ↓
Extractor
 ↓
Evidence
 ↓
Short evidence summary
 ↓
CEO 14B
```

---

# 31. Memoria empresarial avanzada

Una vez estabilizado el flujo anterior, conectar:

```text
Opportunity
Decision
Evidence
Customer
Transaction
Lesson
Strategy
Prediction
CapitalAllocation
Tool
ModelAssignment
```

y permitir consultas como:

```text
¿Qué oportunidades rechazamos y por qué?
¿Qué fuentes demostraron ser útiles?
¿Qué tipos de clientes compraron?
¿Qué canal convirtió mejor?
¿Qué agente predijo mejor?
¿Qué estrategia generó beneficio?
```

---

# 32. Autonomía avanzada

Después del primer negocio real:

1. replanificación automática;
2. asignación dinámica de agentes;
3. portfolio de oportunidades;
4. capital allocation;
5. prediction market;
6. aprendizaje de qué agentes/modelos funcionan mejor;
7. creación de herramientas nuevas;
8. lessons learned;
9. cambios de roles con aprobación según gobierno.

---

# 33. Orden recomendado de implementación

```text
1. EvidenceAcquisitionService
2. WebSearchClient
3. WebPageFetcher seguro
4. EvidenceCandidate
5. Evidence Verification
6. Neo4j Evidence persistence
7. Tool calling con Ollama
8. Evidence events Kafka
9. Tests unitarios
10. MVP-EVIDENCE-004
11. Agent Failure != Mission Failure
12. MVP-CUSTOMER-001
13. Transaction/Profit Gate
14. MVP-BUSINESS-002
15. Neo4j Company Memory funcional
16. Autonomía avanzada
```

---

# 34. Checklist final del nuevo bloque

## Evidence Acquisition

- [ ] `WebSearchClient`.
- [ ] API key segura.
- [ ] `WebPageFetcher`.
- [ ] protección SSRF.
- [ ] `EvidenceCandidate`.
- [ ] verificación semántica.
- [ ] deduplicación.
- [ ] persistencia.
- [ ] tool calling.
- [ ] eventos.
- [ ] observabilidad.
- [ ] tests.

## Recovery

- [x] Retry 3 intentos.
- [x] feedback al agente.
- [x] `EMPRESA_TASK_RETRY`.
- [ ] Agent failure aislado de Mission failure.
- [ ] replanificación automática.

## Customer / Revenue

- [x] Customer.
- [x] Transaction.
- [x] `netProfitUsd`.
- [ ] flujo Opportunity → Customer.
- [ ] flujo Customer → Transaction.
- [ ] evidencia real de venta.
- [ ] primer ingreso real.
- [ ] beneficio > US$50.

## Company Memory

- [x] constraints.
- [ ] relaciones funcionales.
- [ ] decisiones.
- [ ] lessons.
- [ ] strategy.
- [ ] predictions.
- [ ] capital allocation.

---

# 35. Definition of Done — Evidence Acquisition

La fase se considera completada únicamente cuando:

```text
✅ agente solicita búsqueda
✅ tool ejecuta búsqueda real
✅ fuente real obtenida
✅ URL real recuperada
✅ EvidenceCandidate creado
✅ evidencia validada
✅ evidencia persistida en Neo4j
✅ CEO recibe la evidencia
✅ misión termina correctamente
✅ tests pasan
```

Y **no** se considera completada solo porque el LLM diga:

```text
"Encontré evidencia..."
```

---

# 36. Definition of Done — Empresa real

El experimento completo se considera exitoso cuando exista evidencia de:

```text
✅ oportunidad real
✅ demanda real
✅ cliente real
✅ oferta real
✅ pago/compromiso real
✅ ingresos reales
✅ costos reales
✅ beneficio neto > US$50
✅ trazabilidad Neo4j
✅ trazabilidad Kafka
✅ evidencia documental
```

---

# 37. Principio de cierre

El sistema ya dejó de ser solamente:

```text
LLM + agentes + orquestador
```

y está evolucionando hacia:

```text
Empresa AI
   ↓
Observa
   ↓
Investiga
   ↓
Obtiene evidencia
   ↓
Decide
   ↓
Actúa
   ↓
Vende
   ↓
Cobra
   ↓
Mide beneficio
   ↓
Aprende
   ↓
Reinvierte
```

El siguiente hito técnico es **`MVP-EVIDENCE-004`**.

El siguiente hito empresarial es **el primer cliente real**.

El hito definitivo es **netProfitUsd > US$50**.
