# Calidad de prospectos: empresas reales, no segmentos de mercado (Sub-proyecto B) — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reescribir la instrucción de `customerCandidates` en el prompt del agente para que exija empresas concretas y nombradas (nunca un segmento de mercado con nombre inventado) y para que `confidence` tenga un criterio honesto y accionable.

**Architecture:** Cambio de una sola pieza de texto en `AgentRuntime.buildPrompt` — sin gate nuevo, sin búsqueda web adicional. Es una mejora probabilística sobre el prompt, no una garantía dura (no existe forma determinística de validar en código "esto es una empresa real y específica").

**Tech Stack:** Java 21 (texto de prompt, sin lógica nueva).

**Spec:** `docs/superpowers/specs/2026-09-18-real-prospect-quality-design.md`

**Depende de:** `docs/superpowers/plans/2026-09-18-prospect-chat-grounding.md` (Sub-proyecto A) — su Task 2 ya agrega el campo `confidence` a la plantilla `CUSTOMER_CANDIDATE` y una primera versión del bullet de `confidence` en `REGLAS`. Este plan asume que la Task 2 de A ya está implementada y reemplaza ese texto por la versión final de este spec. Ejecutar el plan de A completo antes que este.

## Global Constraints

- Sin gate nuevo en código, sin segunda búsqueda forzada por candidato — deliberadamente descartado en el spec.
- El contenido de un prompt no es testeable de forma determinista — sin test unitario nuevo más allá de lo que ya cubre el Sub-proyecto A para el campo `confidence` en sí.
- `mvn test` debe quedar en verde después de la tarea.

---

### Task 1: Reescribir la instrucción de `customerCandidates` con el requisito de especificidad

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java` (bloque `REGLAS`, el bullet de `customerCandidates` + el bullet de `confidence` agregados por la Task 2 del plan del Sub-proyecto A)

**Interfaces:**
- Consumes: nada de código (es solo texto de prompt). Asume que la Task 2 del plan de A ya dejó el archivo con el bloque intermedio mostrado en el Step 1.
- Produces: nada que otra tarea consuma en código.

Sin test automatizado — mismo criterio que la Task 2 del Sub-proyecto A: el contenido de un prompt no es testeable de forma determinista. La verificación es de compilación + suite existente en verde, y verificación en vivo después.

- [ ] **Step 1: Localizar el texto intermedio dejado por el Sub-proyecto A**

En `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`, dentro del bloque `REGLAS` de `buildPrompt`, debe existir este texto (resultado de la Task 2 del plan `2026-09-18-prospect-chat-grounding.md`):

```java
                - customerCandidates es para perfiles de clientes
                  concretos y reales que hayas identificado en tu
                  investigación (no genéricos como "microempresarios en
                  general") — nunca un cliente real ni contactado, eso
                  solo lo registra un humano. Si no identificaste ninguno,
                  déjalo como lista vacía; no inventes uno para llenarlo.
                - confidence de cada customerCandidate: alto (mayor
                  a 0.7) solo si es una empresa nombrada, real y
                  verificable con una fuente específica de esa
                  empresa; bajo (menor a 0.4) si en realidad es un
                  segmento de mercado genérico. Nunca reportes
                  confidence alto solo porque el nombre suena a
                  empresa real.
```

Si este texto no aparece tal cual (por ejemplo, porque el plan de A no se ejecutó todavía), detenerse y ejecutar primero `docs/superpowers/plans/2026-09-18-prospect-chat-grounding.md` completo.

- [ ] **Step 2: Reemplazar por el texto final del Sub-proyecto B**

Reemplazar ese bloque completo (los 2 bullets de arriba) por:

```java
                - customerCandidates es para EMPRESAS CONCRETAS Y
                  NOMBRADAS que hayas identificado en tu
                  investigación — no un segmento de mercado con
                  nombre inventado ("Studio PixelCraft" citando un
                  artículo genérico de tendencias del sector NO es
                  un candidato válido). La fuente (source) debe ser
                  la página, perfil o mención específica de ESA
                  empresa puntual, nunca un artículo general de la
                  industria. Nunca un cliente real ni contactado,
                  eso solo lo registra un humano. Si no
                  identificaste ninguna empresa real y específica,
                  dejá la lista vacía — no inventes una ni la
                  fuerces a partir de un segmento genérico.
                - confidence de cada candidato: alto (mayor a 0.7)
                  solo si es una empresa real, nombrada, con fuente
                  específica de ESA empresa; bajo (menor a 0.4) si
                  en realidad es más un segmento de mercado que una
                  empresa puntual verificable. Nunca reportes alto
                  solo porque el nombre suena a empresa real.
```

- [ ] **Step 3: Correr la suite y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: PASS (nada de esto tiene test directo, pero no debe romper nada existente — en particular, ningún test debería depender del texto exacto del prompt anterior).

- [ ] **Step 4: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/agent/AgentRuntime.java
git commit -m "Prompt: customerCandidates exige empresas reales y nombradas, no segmentos de mercado"
```

---

## Verificación en vivo (fuera del ciclo TDD, mismo criterio que el resto de docs/HISTORY.md)

1. Rebuild y redeploy del contenedor `company-core` con esta rama (Sub-proyecto A + B juntos).
2. Correr una misión comercial real (mismo tipo de instrucción que generó "Studio PixelCraft"/"GameForge Studios" citando un artículo genérico de tendencias).
3. Revisar a mano en Neo4j los `Customer{status:'LEAD'}` nuevos: confirmar que la fuente (`Evidence.source`) de cada uno apunta a la página/perfil específico de una empresa puntual, no a un artículo general de la industria — o que la lista de candidatos viene vacía si el agente no encontró ninguna empresa así de específica.
4. Confirmar que `confidence` refleja ese criterio (alto solo para candidatos con fuente específica, bajo para los que parezcan más un segmento genérico).
5. Documentar el resultado real (mejora / sin cambio / empeora) en `docs/HISTORY.md`, igual que el resto de rondas de este proyecto — esta es una mejora probabilística de prompt, no una garantía, así que el resultado real importa más que la intención del texto.
