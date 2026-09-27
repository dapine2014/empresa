# Proveedores remotos por grupo de agentes, con herramientas — diseño

**Fecha**: 2026-09-27
**Estado**: decisiones del fundador tomadas en conversación; pendiente de revisión de este spec.

## Contexto y objetivo

Desde el sandbox (parte 2), Engineering usa `nvidia:moonshotai/kimi-k3` por la API de NVIDIA (`OpenAiCompatibleClient`,
key `NVIDIA_API_KEY`) y con eso pasó de 0 `VERIFIED` en 18 misiones a `VERIFIED` en .NET, Godot y Flutter. El resto de
agentes sigue en Ollama local (`qwen3:8b`, `qwen2.5-coder:14b`), con las limitaciones de calidad ya documentadas.

Objetivo del fundador: que **cada grupo de agentes use un modelo fuerte de NVIDIA con su propia API key** (reparte los
límites de uso de la cuenta gratuita), dejando Engineering como está.

## Decisiones del fundador

1. Engineering no cambia (kimi-k3, `NVIDIA_API_KEY`).
2. NVIDIA (sin costo, créditos gratuitos) para los otros grupos mientras Forjai no tenga ingresos; Anthropic u otros
   proveedores pagos quedan para más adelante, con tope de gasto.
3. Una key por grupo, ya cargadas y probadas en `.env`:

| Grupo | Agentes | Key | Candidato |
|---|---|---|---|
| Discovery | Sofia (`sales`), Luna (`product`), Max (`finance`) | `NVIDIA_API_KEY_DISCOVERY` | `nvidia/nemotron-3-super-120b-a12b` |
| Creative y Marketing | Kael, Maya, Gael, Kira, Nora | `NVIDIA_API_KEY_CREATIVE` | `moonshotai/kimi-k3` (alt. `z-ai/glm-5.3`) |
| CEO | Alex (`ceo`) | `NVIDIA_API_KEY_CEO` | `nvidia/nemotron-3-ultra-550b-a55b` |

4. El modelo definitivo de cada grupo se elige con una prueba corta con tareas reales del grupo.

## 1. Proveedores con nombre

- `Agent.model` = `<proveedor>:<modelo>`. Proveedores remotos: `nvidia` (el actual, `NVIDIA_API_KEY`),
  `nvidia-discovery`, `nvidia-creative`, `nvidia-ceo` (sus keys). Mismo endpoint `integrate.api.nvidia.com/v1`.
- Configuración: `remote-models.providers.<nombre>.api-key` (y `base-url` opcional, default el de NVIDIA). `CoreConfig`
  construye un `OpenAiCompatibleClient` por proveedor; `CeoService` recibe el mapa.
- Un prefijo desconocido o un proveedor sin key falla con un mensaje claro (nunca cae en silencio a Ollama).
- Todo lo que no tiene un prefijo remoto sigue yendo a Ollama sin cambios.

## 2. Herramientas en el proveedor remoto (traducción Ollama ↔ OpenAI)

Verificado en vivo (2026-09-27): los 4 candidatos piden `search_web_evidence` en formato OpenAI (argumentos como texto
JSON, `id` por llamada); kimi-k3 y glm-5.3 piden varias búsquedas a la vez.

- **Pedido**: la definición de herramientas de Ollama ya es la de OpenAI (`type: function` + `function`) → pasa igual.
- **Mensajes**: un `assistant` con `tool_calls` en formato Ollama (`function.arguments` como objeto) se traduce a
  OpenAI (`id`, `type: function`, `arguments` como texto JSON); el `tool` siguiente gana `tool_call_id` con ese `id`.
- **Respuesta**: `tool_calls` de OpenAI se devuelven a `CeoService` en formato Ollama (`arguments` parseado a objeto),
  así `parseStructuredToolCall` / `parseCompanyMemoryTopic` no cambian. Si hay varias llamadas, se usa la primera
  (igual que hoy con Ollama); aprovechar varias búsquedas queda fuera de alcance.
- **Reglas que no cambian**: nunca `format` + `tools` en la misma llamada (el guard aplica igual); el "thinking" remoto
  va siempre apagado (verificado: con el default mezcla el razonamiento en `content`).
- **Tope de salida**: llamadas con herramientas y chat, 4.096 tokens; llamadas de equipo, 16.384 (como hoy).

## 3. Elección de modelos (prueba corta)

Antes de asignar, por grupo y con su key, sobre tareas reales:
- **Discovery**: una tarea `MARKET_DISCOVERY` completa (turno de herramienta + búsqueda real + `AgentResult` final
  validado por los gates reales), candidatos nemotron-3-super y nemotron-3-ultra.
- **Creative y Marketing**: una tarea de análisis del equipo (plan del líder + una tarea de un miembro), candidatos
  kimi-k3 y glm-5.3.
- **CEO**: tres mensajes de chat (uno que necesita `query_company_memory`) y una consolidación de misión, candidatos
  nemotron-3-ultra y kimi-k3.
- Criterios: pasa los gates sin reintentos, cita evidencia real, no inventa datos, latencia. Se presenta una tabla y el
  fundador elige.

## 4. Asignación y verificación en vivo

- Asignación con `PUT /api/company/agents/{id}/model` (reversible por agente).
- Verificación en vivo: una misión de discovery completa, una misión de Creative o Marketing, y un chat con Alex que use
  `query_company_memory`. Si un proveedor falla (key inválida, límite), el agente falla con el motivo; la regla "agent
  failure ≠ mission failure" sigue aplicando.

## Privacidad

Todo lo que procesan estos agentes sale hacia NVIDIA: números de Max, clientes y prospectos de Sofia, historial del chat
con Alex. Aceptado por el fundador al elegir NVIDIA para estas áreas.

## Testing

- Unitarios: traducción de mensajes con `tool_calls` y `tool_call_id`; parseo de `tool_calls` con argumentos como texto;
  varias llamadas → la primera; proveedor por prefijo; prefijo desconocido y key faltante con mensaje claro; guard
  `format` + `tools` también en remoto.
- En vivo: lo de la sección 4.

## Fuera de alcance

- Proveedores pagos (Anthropic, OpenAI) y tope de gasto (queda para cuando haya ingresos; `AiBudgetService` de
  `worktree-prospect-chat-quality` es el punto de partida).
- Usar varias búsquedas por turno.
- Fallback automático a Ollama si un proveedor remoto falla.
