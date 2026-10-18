# Revisión conjunta Neo + Aria de HU y diseño (2026-10-15)

## Por qué

En el Pong `MISSION-KAEL-PONG-1792113455` el diseño de Kael contradecía las HU de Aria cinco veces y nadie en Forjai lo detectó: aceleración del 5 % contra "velocidad constante", game over en `x > 640` con 800×600, paleta a la izquierda cuando el game over es por la derecha, y rebotes sin dirección (la pelota invierte cada frame mientras se superpone; muy probablemente la causa de las 4 partidas que fallaron igual en 4 rondas del Pong anterior). Las corrigió a mano el asistente, que actuó como Neo y Aria.

**Decisión del fundador (2026-10-15):** "cuando se generan contradicciones el líder técnico decide junto con el PO". Eligió la **revisión conjunta**: Neo detecta y propone, Aria (dueña del alcance) decide, Java aplica. Al fundador le llega todo ya coherente y sigue aprobando el resultado.

## Qué cambia en el flujo

Solo en misiones del Development Group con `experience` `APP` o `GAME` (con `NONE` Kael no diseña y no hay nada que revisar):

```
Aria (HU) → Kael (diseño) → REVISIÓN CONJUNTA → AWAITING_SPEC_APPROVAL (o directo a Neo con SPEC_APPROVAL_REQUIRED = 0)
```

La revisión corre cada vez que hay un diseño `DRAFT` nuevo que el fundador va a ver: tras el primer diseño, tras un ajuste a Aria (que hace rediseñar a Kael) y tras un ajuste a Kael.

### Paso 1: Neo detecta (`JointReviewWriter`, operación `JOINT_REVIEW_DETECT`)

- Neo (`engineering`) recibe su prompt de agente, más las instrucciones de la revisión fijas en Java (como `CorrectionStrategist`), el spec de Aria (`RequirementsText`), el diseño de Kael (`InteractionDesignText`, el `AppDesign`/`GameDesign` plano) y, si los hay, los ajustes del fundador de esta misión con la regla «son decisiones del fundador: nunca se proponen cambios que las deshagan».
- Responde con `format` (JSON Schema armado en Java, sin `tools`):

  ```json
  {"contradictions": [{"id": "CONTRA-01", "requirementRefs": ["HU-02"], "designRefs": ["REGLA-07"],
     "requirementSays": "…", "designSays": "…", "proposal": {"side": "DESIGN|REQUIREMENTS", "change": "…"}}]}
  ```

- Qué es una contradicción (lo dicen las instrucciones): algo que el diseño hace y las HU, sus escenarios o sus supuestos niegan (o al revés); valores incompatibles (tamaños, límites, velocidades); reglas que no pueden cumplir un escenario (p. ej. el game over por un borde donde no está la paleta); y, en `GAME`, una regla de colisión o rebote sin condición de dirección. Lo que es solo estilo o preferencia no es contradicción.
- Lista vacía = coherente.

### Paso 2: Aria decide (`JointReviewWriter`, operación `JOINT_REVIEW_DECIDE`)

Solo si Neo encontró alguna. Aria (`product-owner`) recibe su prompt, el spec, el diseño y las contradicciones de Neo y responde una decisión por contradicción:

```json
{"decisions": [{"id": "CONTRA-01", "side": "DESIGN|REQUIREMENTS", "change": "…", "reason": "…"}]}
```

Puede aceptar la propuesta de Neo tal cual o cambiar el lado o el texto: el alcance es suyo. No puede descartar una contradicción: cada una termina con un lado y un cambio concreto.

### Validación en Java (`JointReviewValidator`, sin modelo)

Igual que los demás writers: hasta 3 intentos por paso, el motivo vuelve como `CORRECCIÓN DEL INTENTO ANTERIOR`; agotados, la revisión se registra como `FAILED` con el motivo y la misión sigue a la aprobación del fundador **sin bloquearse**, con el aviso «La revisión conjunta no se pudo completar: …» (nunca `FAILED` de la misión por esto).

- Neo: ids `CONTRA-NN` únicos y en orden; cada `requirementRefs` existe en el spec (`HU-NN`, `ESC-NN`) y cada `designRefs` existe en el diseño (`PANT-NN` en `APP`; `REGLA-NN` y los ids de `entities` en `GAME`); al menos una referencia de cada lado; `requirementSays`, `designSays` y `change` no vacíos; `side` válido; tope de 10.
- Aria: exactamente una decisión por cada `CONTRA-NN` de Neo, ninguna de más; `side` válido; `change` y `reason` no vacíos.

### Paso 3: Java aplica (`JointReviewService`)

- Agrupa las decisiones: las `REQUIREMENTS` se juntan en un ajuste a Aria y las `DESIGN` en uno a Kael. El texto de cada ajuste empieza por «Decisión del líder técnico (Neo) con el PO (Aria):» y lista cada cambio con su `CONTRA-NN`.
- Si hay alguna `REQUIREMENTS`: Aria reescribe (`rewriteRequirements`) y Kael rediseña sobre las HU nuevas, con los cambios `DESIGN` como ajuste (un ajuste a Aria siempre hace rediseñar a Kael; ya es así hoy).
- Si solo hay `DESIGN`: Kael rediseña sobre las mismas HU (`redesign`).
- Después, **una segunda revisión** sobre el resultado (tope `JOINT_REVIEW_ROUNDS = 2`). Lo que siga contradiciendo tras la segunda queda **sin resolver** y se le muestra al fundador; no hay tercera vuelta.
- Las versiones intermedias quedan como hoy (`SUPERSEDED`), así que el fundador aprueba la última.

### Qué ve el fundador

- El mensaje de `AWAITING_SPEC_APPROVAL` suma: «Neo y Aria resolvieron N contradicción(es): CONTRA-01 (HU-02 ↔ REGLA-07) → diseño: …» y, si quedaron, «⚠ Sin resolver: …».
- Detalle de la misión: sección «Revisión conjunta (Neo + Aria)» con cada ronda, las contradicciones, la decisión de Aria y el lado que cambió.
- Chat (Java, sin modelo): «muéstrame la revisión de MISSION-X» y «¿qué contradicciones resolvieron en MISSION-X?».
- Su ADJUST a Aria o a Kael sigue igual; el resultado vuelve a pasar por la revisión antes de esperar su aprobación.

## Datos y durabilidad

- `(:Mission)-[:HAS_JOINT_REVIEW]->(:JointReview {round, specVersion, designVersion, status RUNNING|COHERENT|RESOLVED|UNRESOLVED|FAILED|SKIPPED, contradictionsJson, decisionsJson, detail, at})` en `JointReviewMemoryService`. El borrado de una misión lo incluye en su cascada.
- `ResumePoint.Stage.JOINT_REVIEW`: spec y diseño `DRAFT` de la misma versión, con la revisión activada y sin una `JointReview` cerrada para ese par `(specVersion, designVersion)` → al reiniciar se retoma la revisión (una ronda a medias se repite completa: son 2 llamadas cortas). Una revisión que ya aplicó cambios deja la misión en `DESIGN_ADJUSTMENT` o `ADJUSTMENT`, que ya existen.
- Eventos: `EMPRESA_JOINT_REVIEW_COMPLETED` (`agentId` `engineering`, `data: {round, status, contradictions, resolvedBy: {requirements, design}}`) en EVENTS.

## Configuración

- Policy nueva `JOINT_REVIEW_ENABLED` (1 = sí, semilla 1), editable en Financial Policies como las demás; en 0 la revisión se registra como `SKIPPED` y el flujo es el de hoy.
- Neo o Aria apagados (`Agent.enabled`): la revisión se registra como `SKIPPED` con el motivo y se le avisa al fundador en el mensaje de aprobación; no bloquea.
- Los prompts de agente de Neo y Aria siguen versionados en `AgentPromptService`; las instrucciones de la revisión van fijas en Java (mismo criterio que `CorrectionStrategist`).
- Modelos: los de cada agente con su cadena de respaldo (cadena gratis, Bedrock y luego local).

## Pruebas

- `JointReviewValidator`: referencias inexistentes, ids repetidos o desordenados, lado inválido, decisiones faltantes o de más, tope de 10, lista vacía = coherente.
- `JointReviewService`: coherente pasa directo; solo `DESIGN` → `redesign`; con `REQUIREMENTS` → Aria y luego Kael; segunda ronda; tope de 2 con «sin resolver»; validación agotada → `FAILED` sin bloquear; policy en 0 y Neo apagado → `SKIPPED`; los ajustes del fundador llegan al prompt.
- `ResumePoint`: el par `DRAFT` sin revisión cerrada → `JOINT_REVIEW`.
- Chat: las dos consultas en Java, sin modelo.
- Frontend: lint + build.
- **En vivo** (con OK del fundador): un juego **nuevo** (no el Pong) que debe llegar a la aprobación coherente, sin intervención del asistente, y terminar `VERIFIED`. Es la tercera condición para dar a Kael por ajustado.

## Fuera de alcance

- Revisar contradicciones dentro de las HU de Aria solas o dentro del diseño solo (sin cruzar ambos).
- Revisión conjunta en misiones `NONE`.
- Que la revisión cambie código ya generado: corre antes de que exista código.
