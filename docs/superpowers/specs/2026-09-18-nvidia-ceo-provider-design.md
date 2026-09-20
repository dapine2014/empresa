# CEO en NVIDIA NIM con fallback a Ollama — diseño

**Fecha**: 2026-09-18
**Estado**: aprobado por el usuario en brainstorming (con spike previo de validación),
pendiente de plan de implementación.

## Contexto y objetivo

Spike (`superpowers:brainstorming`, path spike, resultados no versionados —
solo scripts sueltos fuera del repo) comparando `qwen2.5-coder:14b` (Ollama,
CEO actual) contra `openai/gpt-oss-20b` (NVIDIA NIM, endpoint gratuito de
prototipo) usando el prompt real de consolidación de una misión real
completa (`MISSION-1789701859658`), 3 rondas por proveedor:

- NVIDIA distinguió hecho/hipótesis en 3/3 rondas (Ollama, 1/3) y citó
  fuentes reales de la evidencia ya persistida en 3/3 rondas (Ollama, 0/3
  — nunca citó ninguna). Se cruzó cada URL citada por NVIDIA contra la
  evidencia real en Neo4j: **cero inventadas**.
- Una ronda de NVIDIA falló (`content: null`) — causa raíz identificada:
  `gpt-oss-20b` es un modelo "razonador" que gasta tokens en un campo de
  `reasoning` oculto antes de responder; con `max_tokens=2048` a veces se
  queda sin presupuesto para el contenido final (`finish_reason: "length"`).
  Confirmado que `max_tokens=8192` lo resuelve (`finish_reason: "stop"`).
- El modelo que se había propuesto originalmente
  (`llama-3.3-nemotron-super-49b-v1.5`) tiene su endpoint gratuito
  **deprecado** — solo queda disponible para auto-hospedar con GPU propia
  (requiere mucha más VRAM que la disponible localmente). Por eso el
  candidato real para esta ronda es `openai/gpt-oss-20b`, no el
  originalmente propuesto.

Objetivo: que el CEO (consolidación de misión + chat general) use NVIDIA
NIM como proveedor principal, con fallback automático a Ollama si NVIDIA
falla o se agota un presupuesto diario de llamadas — sin apagar nunca la
empresa por depender de un endpoint gratuito de evaluación. Los agentes
(`sales`/`product`/`finance`/`engineering`/`qa`, vía
`CeoService.executeAgentTask`) **no cambian** — siguen 100% en Ollama
local, sin pasar por esta abstracción.

Este documento es el resultado de una sesión de `superpowers:brainstorming`
(path arquitectural, precedida por el spike) — cada decisión de abajo fue
presentada como alternativas al usuario y aprobada explícitamente antes de
escribir este spec.

## Decisiones de diseño

### 1. `LlmProvider`, abstracción nueva y acotada solo al CEO

Paquete nuevo `com.aicompany.core.llm`:

```java
public interface LlmProvider {
    LlmResponse chat(String operation, List<Map<String, Object>> messages,
                      List<Map<String, Object>> tools);
}

public record LlmResponse(String content, List<Map<String, Object>> toolCalls) {}
```

`LlmResponse` es estructuralmente igual al `ModelMessage` privado que ya
existe en `CeoService` — se reemplaza ese record interno por este, público,
sin cambiar su forma (evita romper el resto del archivo).

Dos implementaciones:

- `OllamaLlmProvider`: envuelve exactamente la lógica de `RestClient` que
  hoy vive en `CeoService.callModel` para las llamadas del CEO (sin
  `format`, sin `think` — esos parámetros son exclusivos de
  `executeAgentTask`, que no pasa por esta abstracción).
- `NvidiaNimLlmProvider`: `RestClient` nuevo apuntando a
  `https://integrate.api.nvidia.com/v1/chat/completions` (compatible
  OpenAI), header `Authorization: Bearer $NVIDIA_API_KEY`, body
  `{model, messages, tools, max_tokens, stream:false}`. Parsea
  `choices[0].message` a `LlmResponse`.

**Solo dos puntos de `CeoService` usan esto**: `executeMission`
(consolidación) y `chat` (incluye el tool-calling de
`query_company_memory`). `executeAgentTask` sigue llamando a
`callModel`/Ollama directo, sin cambios — el diseño es "CEO potente,
workers locales", no una abstracción total del cliente de LLM del
proyecto.

### 2. Normalización del formato de `tool_calls`

`CeoService.parseCompanyMemoryTopic`/`parseStructuredToolCall` esperan
`function.arguments` como un `Map` ya decodificado (así lo devuelve
Ollama). El formato OpenAI que usa NVIDIA NIM entrega `function.arguments`
como un **string JSON serializado**, no un objeto. `NvidiaNimLlmProvider`
decodifica ese string a `Map` (vía el `JsonMapper` ya inyectado en el
proyecto) antes de construir el `LlmResponse.toolCalls()` — así el resto
de `CeoService` no necesita saber qué proveedor respondió.

### 3. Fallback automático a Ollama

`CeoService` gana un `LlmProvider ceoProvider` (el configurado,
normalmente `NvidiaNimLlmProvider`) y mantiene `OllamaLlmProvider` como
respaldo fijo. Antes de cada llamada del CEO, se consulta el presupuesto
diario (ver decisión 4); si ya se agotó, o si `ceoProvider.chat(...)`
lanza una excepción, o si la respuesta viene con `content` vacío/nulo (el
caso de truncamiento del spike), se reintenta la misma llamada contra
`OllamaLlmProvider` — la misión nunca falla por esto. Se publica
`EMPRESA_CEO_PROVIDER_FALLBACK` (`data: {operation, reason}`, `reason` uno
de `RATE_LIMIT_OR_ERROR`/`BUDGET_EXHAUSTED`) cada vez que esto ocurre, para
que quede auditable en `EMPRESA_EVENTS` — no se mezcla con las alertas por
correo existentes (esas son exclusivas de `AWAITING_INVESTOR`/`FAILED` de
misiones).

### 4. Presupuesto diario simple, en memoria

Nuevo `AiBudgetService`: un contador (`AtomicInteger`) de llamadas al CEO
vía NVIDIA en el día calendario actual, con límite
`company.nvidia-daily-call-limit` (default `20`, configurable). Resetea al
cambiar la fecha (comparación simple contra la fecha del último reset, no
un scheduler). **No persiste entre restarts** — mismo criterio ya
documentado para otros límites del proyecto (`Agent.status` sin
reconciliación, `MissionExecutor` sin sobrevivir un restart): es un tope de
cortesía sobre un tier gratuito de evaluación, no un control de gasto real
(NVIDIA no cobra nada en este tier), así que un reset accidental por
restart no tiene costo económico.

### 5. Configuración nueva

Mismo patrón que el resto del proyecto (`${ENV_VAR:default}` en
`application.yml`, documentado en `docker-compose.yml`):

```yaml
llm:
  ceo-provider: ${LLM_CEO_PROVIDER:ollama}   # "ollama" | "nvidia"

nvidia:
  api-key: ${NVIDIA_API_KEY:}
  base-url: ${NVIDIA_BASE_URL:https://integrate.api.nvidia.com/v1}
  ceo-model: ${NVIDIA_CEO_MODEL:openai/gpt-oss-20b}
  max-tokens: ${NVIDIA_MAX_TOKENS:8192}

company:
  nvidia-daily-call-limit: ${NVIDIA_DAILY_CALL_LIMIT:20}
```

**Default `llm.ceo-provider=ollama`** — a propósito: nadie activa NVIDIA
sin querer, hay que setear la variable explícitamente (en `.env`/
`docker-compose.yml`) para probarlo, mismo criterio que
`Mission.environment` default `PRODUCTION` solo cuando corresponde y otros
defaults conservadores ya establecidos en el proyecto. `Spring` elige qué
bean de `LlmProvider` inyectar como `ceoProvider` según ese valor
(`@ConditionalOnProperty` o un `@Bean` factory simple — detalle de
implementación, no de diseño).

## Testing

- `NvidiaNimLlmProviderTest`: parseo de una respuesta real (fixture JSON)
  a `LlmResponse`, incluyendo la normalización de `tool_calls` con
  `arguments` como string; manejo de `content` nulo/vacío.
- `AiBudgetServiceTest`: cuenta hasta el límite, resetea al cambiar de
  fecha (inyectando un reloj fake, no `Instant.now()` real).
- `CeoServiceTest` (nuevo o extendiendo el existente): fallback a Ollama
  cuando `ceoProvider` lanza; fallback cuando el presupuesto ya se agotó;
  camino feliz sin fallback; evento `EMPRESA_CEO_PROVIDER_FALLBACK`
  publicado con el `reason` correcto en cada caso de fallback.
- Sin test de integración real contra NVIDIA (mismo criterio que Ollama:
  fuera de la suite automatizada, se verifica en vivo aparte).

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Presupuesto persistido / por misión / por agente**: el presupuesto de
  esta ronda es un contador global simple del CEO. Límites más finos
  (por misión, por agente, mensual) quedan para cuando haya uso real que
  medir.
- **Que los agentes (`sales`/`product`/etc.) también puedan usar NVIDIA**:
  deliberadamente fuera de alcance — el diseño aprobado es "CEO potente,
  workers locales", no una migración general del proyecto a proveedores
  externos.
- **Auto-hospedar un modelo NVIDIA localmente** (ej.
  `llama-3.3-nemotron-super-49b-v1.5` vía NIM self-hosted): evaluado por
  separado, fuera de este spec — requiere hardware que hoy no está
  disponible (ver conversación previa sobre VRAM de la RTX 5060).
