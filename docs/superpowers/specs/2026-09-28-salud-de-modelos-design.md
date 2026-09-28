# Salud de los modelos remotos con suplente local — diseño

**Fecha**: 2026-09-28
**Estado**: diseño acordado con el fundador en conversación; pendiente de revisión de este spec.

## Contexto y objetivo

El 2026-09-28 `moonshotai/kimi-k3` dejó de responder en NVIDIA durante horas (504, sin respuesta, cuerpo no JSON) con
dos keys distintas, mientras otros modelos con la misma key respondían en 0,7 s. Forjai no se enteró: cada agente de
Engineering esperó 10 minutos por intento, 3 intentos más una re-ejecución, y una discovery quedó más de 2 horas trabada.

Objetivo (pedido del fundador: "es necesario colocar un validador de ese tipo"; "en caso de caída los modelos locales
pueden ser los suplentes"): que Forjai **detecte** la caída de un modelo remoto, **no vuelva a esperarlo**, pase a los
agentes afectados a un **suplente local** de forma explícita, **detecte solo** cuando vuelve y **avise** al fundador.

## Decisiones

1. **Suplente local por agente** (fundador): editable en el Command Center (Agents), default `qwen3-coder:30b` para todos
   (prueba en vivo 2026-09-28: Engineering compila y pasa 13/13 tests tras una corrección; una discovery completa 5/5 en
   7 minutos con menos evidencia y cálculos que NVIDIA). Si el suplente está vacío, el agente no tiene suplente y falla
   rápido con el motivo.
2. **Cambio automático pero explícito**: nunca en silencio (correo, chat, eventos y marca en las tareas). Reemplaza la
   decisión anterior "fuera de alcance: fallback automático a Ollama".
3. **Solo modelos remotos** (proveedores `nvidia*`) se vigilan; un modelo local no tiene suplente.

## 1. Detección (`ModelHealthService`, en memoria)

- Estado por modelo remoto (`proveedor:modelo`): `UP` o `DOWN` con `since`, último error y fallos seguidos.
- Cuenta como **fallo de disponibilidad**: timeout o error de conexión, HTTP 5xx o 429 que siguen tras los reintentos
  del cliente, respuesta sin `choices`, cuerpo ilegible (p. ej. `application/octet-stream`). **No** cuentan los 4xx
  distintos de 429 (son errores del pedido, no del modelo).
- **2 fallos de disponibilidad seguidos → `DOWN`**. Un éxito vuelve el contador a 0.
- El estado vive en memoria (se recalcula tras un reinicio: el primer fallo lo vuelve a detectar).

## 2. Qué pasa con las llamadas (`CeoService.callModel`)

- Modelo remoto `UP`: se llama como hoy; el resultado alimenta `ModelHealthService`. Si esa llamada lo deja `DOWN`, la
  misma llamada se repite con el suplente del agente (no se pierde el trabajo).
- Modelo remoto `DOWN`: **no se llama**; la llamada va directo al suplente del agente (Ollama). Sin suplente → falla al
  instante con "El modelo X no responde en NVIDIA desde HH:MM y <agente> no tiene suplente".
- El agente (`actor`) se usa para buscar su suplente (`Agent.fallbackModel` en Neo4j).
- El suplente pasa por el mismo camino de Ollama (incluye el reintento sin `think`).

## 3. Recuperación

- Un chequeo programado cada **30 segundos**, solo para modelos `DOWN`: una llamada mínima (10 tokens, timeout corto de
  20 s, no el de 10 minutos). Si responde → `UP`, y las llamadas siguientes vuelven a NVIDIA solas.

## 4. Avisos y visibilidad

- **Correo** al fundador cuando un modelo cae y cuando vuelve (`AlertMailService`), con los agentes afectados y su
  suplente.
- **Eventos** `EMPRESA_MODEL_DOWN` / `EMPRESA_MODEL_UP` (`{model, since, error, affectedAgents}`).
- **Chat** (Java): "estado de los modelos" lista cada modelo remoto en uso (✅/⚠ desde cuándo) y quién trabaja con
  suplente; "dame un status" agrega la línea de modelos caídos si hay alguno; el estado de los agentes marca
  "(con suplente X)".
- **Tareas**: una tarea que termina mientras el modelo principal de su agente está `DOWN` queda marcada
  `modelUsed = <suplente>`; el detalle de la misión y el chat lo muestran.
- **Command Center (Agents)**: campo "Suplente local" junto al modelo, y marca ⚠ en los agentes con su modelo caído.

## Testing

- `ModelHealthService`: 2 fallos seguidos → DOWN; un éxito reinicia; 4xx no cuenta; probe exitoso → UP con evento y
  correo; probe fallido → sigue DOWN.
- `CeoService`: DOWN → va al suplente sin llamar a NVIDIA; la llamada que deja DOWN se repite con el suplente; sin
  suplente → falla rápido con el motivo; UP → NVIDIA como hoy.
- Chat: estado de los modelos, status con modelo caído, agente con suplente.
- `AgentRuntime`/tareas: marca `modelUsed` cuando se usó suplente.
- En vivo: aprovechar una caída real (kimi-k3, si sigue) o simularla con un modelo inexistente en un agente de prueba;
  verificar correo, chat, suplente y recuperación.

## Fuera de alcance

- Elegir el suplente por calidad o por tipo de tarea (uno por agente, editable); cadenas de varios suplentes; vigilar
  los modelos locales; persistir el historial de caídas (queda en eventos y logs).
