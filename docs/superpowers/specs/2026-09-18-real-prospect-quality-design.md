# Calidad de prospectos: empresas reales, no segmentos de mercado (Sub-proyecto B) — diseño

**Fecha**: 2026-09-18
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Verificado a mano en Neo4j (misión real `MISSION-1789701859658`): los 7
`Customer{status:'LEAD'}` que generaron los agentes tienen nombres que
suenan a empresas (`"Studio PixelCraft"`, `"GameForge Studios"`) pero su
`Evidence` enlazada cita como fuente un artículo genérico de tendencias del
sector (`"...video-game-industry-trends-2023/"`), no la página de esa
empresa puntual — es decir, son **segmentos de mercado con nombre**, no
empresas verificadas y ubicables. El objetivo del usuario ("que la empresa
busque clientes reales") no se cumple con este tipo de candidato: no hay
forma de ubicar ni contactar a "Studio PixelCraft" a partir de un artículo
sobre tendencias generales de la industria.

No existe forma determinística de validar en código "esto es una empresa
real y específica" (a diferencia de `sourceType`, que es un enum simple) —
el resto del proyecto evita gates donde "el modelo se revisa a sí mismo",
y esta ronda no rompe ese criterio: la única palanca es reforzar qué le
pide el prompt al agente, aceptando que es una mejora probabilística, no
una garantía dura (mismo tipo de limitación ya documentada para
`ClaimRelevanceChecker`, heurística léxica sin garantía dura).

Este documento es el resultado de una sesión de `superpowers:brainstorming`
(path arquitectural, continuación del Sub-proyecto A) — la decisión de
abajo fue presentada como alternativas al usuario y aprobada explícitamente
antes de escribir este spec.

## Decisión de diseño

### Solo prompt — sin gate nuevo, sin búsqueda adicional obligatoria

Se descartó explícitamente agregar una segunda búsqueda forzada por
candidato (más llamadas a Ollama/`search_web_evidence` por misión, más
lento) — la única palanca de esta ronda es reescribir las instrucciones de
`customerCandidates` en `AgentRuntime.buildPrompt`.

Texto actual de la regla (bloque `REGLAS`):

```
- customerCandidates es para perfiles de clientes
  concretos y reales que hayas identificado en tu
  investigación (no genéricos como "microempresarios en
  general") — nunca un cliente real ni contactado, eso
  solo lo registra un humano. Si no identificaste ninguno,
  déjalo como lista vacía; no inventes uno para llenarlo.
```

Nuevo texto — mantiene todo lo anterior (sigue sin ser un cliente real
contactado, sigue permitiendo lista vacía) y agrega el requisito de
especificidad + la instrucción de `confidence` honesto (`confidence` es del
Sub-proyecto A, esta ronda solo agrega la frase que le da sentido real):

```
- customerCandidates es para EMPRESAS CONCRETAS Y NOMBRADAS
  que hayas identificado en tu investigación — no un
  segmento de mercado con nombre inventado ("Studio
  PixelCraft" citando un artículo genérico de tendencias
  del sector NO es un candidato válido). La fuente
  (`source`) debe ser la página, perfil o mención específica
  de ESA empresa puntual, nunca un artículo general de la
  industria. Nunca un cliente real ni contactado, eso solo
  lo registra un humano. Si no identificaste ninguna empresa
  real y específica, dejá la lista vacía — no inventes una
  ni la fuerces a partir de un segmento genérico.
- confidence de cada candidato: alto (>0.7) solo si es una
  empresa real, nombrada, con fuente específica de ESA
  empresa; bajo (<0.4) si en realidad es más un segmento de
  mercado que una empresa puntual verificable. Nunca reportes
  alto solo porque el nombre suena a empresa real.
```

Se actualiza también la plantilla `CUSTOMER_CANDIDATE` del prompt (bloque
`FORMATO OBLIGATORIO`) para incluir `"confidence": 0.0` (dependencia
directa del Sub-proyecto A, ya que ambos tocan el mismo bloque de texto).

## Testing

- Sin test unitario nuevo más allá de lo que ya cubre el Sub-proyecto A
  para el campo `confidence` en sí — el contenido de un prompt no es
  testeable de forma determinista (es texto para el modelo, no lógica).
- **Verificación en vivo pendiente** (misma convención del resto del
  proyecto): correr una misión real después del cambio y confirmar a mano
  si los candidatos que reporta el agente `sales` mejoran en
  especificidad — no hay forma de automatizar esa verificación.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Segunda búsqueda forzada por candidato genérico**: se evaluó y se
  descartó — más lento, más complejo, sin garantía de mejor resultado que
  reforzar el prompt primero y medir en vivo.
- **Gate automático de "especificidad"**: no existe una forma
  determinística confiable de detectar esto en código; si en el futuro se
  encuentra una heurística razonable (p. ej. comparar el dominio de
  `source` contra un patrón conocido de agregadores de noticias), se
  evalúa en una ronda aparte con datos reales de qué falla en la práctica.
