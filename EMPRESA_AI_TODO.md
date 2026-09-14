# Forjai — Idea, Estado, Logros y Roadmap

**Fecha:** 2026-09-12  
**Horizonte del reto:** máximo 60 días  
**Capital semilla real:** US$50  
**Objetivo mínimo:** conseguir al menos un negocio real que haya generado, o tenga un compromiso real y verificable de generar, **más de US$50 de beneficio neto**.

---

# 1. Idea de la empresa

## 1.1 Concepto

La idea es crear una **empresa real operada principalmente por agentes de Inteligencia Artificial**.

La IA no se comercializa como producto. La IA es la **fuerza operativa de la propia empresa**: investiga, analiza, propone, ejecuta operaciones, mide resultados, aprende y reporta.

El fundador/inversor humano mantiene la propiedad de la empresa y la autoridad final sobre las decisiones materiales, pero **no debe convertirse en el operador diario**.

La empresa debe ser capaz de funcionar como una organización real:

```text
                         FUNDADOR / INVERSOR
                                  │
                       decisiones materiales
                                  │
                                  ▼
                        ┌──────────────────┐
                        │    FORJAI    │
                        │  CEO + AI Board  │
                        └────────┬─────────┘
                                 │
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
     Investigación          Operaciones              Finanzas
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 ▼
                         Mercado / Clientes
                                 │
                                 ▼
                       Ingresos / Costos
                                 │
                                 ▼
                          Beneficio / Pérdida
                                 │
                                 ▼
                              Aprendizaje
                                 │
                                 └──────────────► nueva decisión
```

## 1.2 Filosofía

La empresa debe favorecer la autonomía, pero no confundir autonomía con ausencia de gobierno.

Los agentes pueden:

- investigar oportunidades;
- diseñar propuestas;
- crear planes;
- ejecutar tareas permitidas;
- construir herramientas;
- reorganizar trabajo;
- medir resultados;
- aprender de errores;
- proponer cambios estratégicos.

El humano debe conservar aprobación para decisiones relevantes como:

- inversiones materiales;
- gastos importantes;
- contratos;
- compromisos legales;
- acciones irreversibles;
- cambios estratégicos fundamentales.

## 1.3 Principio rector

> **La IA puede operar la empresa, pero el mercado decide si la empresa merece existir.**

La empresa no se considera exitosa porque los modelos escriban respuestas convincentes. El éxito depende de que exista **valor económico real y verificable**.

---

# 2. Reglas fundamentales del reto

## 2.1 Capital

Capital inicial real:

```text
US$50
```

Estos US$50 constituyen el capital semilla disponible para experimentar y operar legalmente.

## 2.2 Duración

```text
Máximo: 60 días / 2 meses
```

## 2.3 Criterio mínimo de éxito

Debe existir al menos un negocio real que:

- haya vendido o tenga un compromiso real/verificable de venta;
- tenga costos identificados;
- permita calcular el beneficio neto;
- genere o vaya a generar, con evidencia suficiente, **más de US$50 de beneficio neto**.

Una hipótesis, una predicción o una proyección del modelo **no cuentan como resultado**.

## 2.4 Niveles de éxito

| Nivel | Beneficio neto |
|---|---:|
| Bueno | > US$50 y ≤ US$100 |
| Muy bueno | > US$100 y < US$1.000 |
| Excelente | ≥ US$1.000 y < US$5.000 |
| Extraordinario | ≥ US$5.000 |

---

# 3. Qué se ha construido hasta ahora

Esta sección documenta lo realizado, no solo lo pendiente.

---

# 4. Infraestructura técnica ✅

## 4.1 Entorno

La plataforma está operando sobre:

- Fedora Linux.
- Docker / Docker Compose.
- Java 21.
- Spring Boot 4.1.1.
- Kafka.
- Neo4j.
- Ollama.
- GPU NVIDIA RTX 5060 Laptop de 8 GB.

## 4.2 Red de la empresa

Se creó la red Docker:

```text
ai-company-net
```

Los principales componentes están conectados mediante la misma red.

## 4.3 Company Core ✅

Existe un servicio principal:

```text
company-core
```

Container:

```text
ai-company-core
```

Puerto:

```text
8081
```

Health check operativo:

```text
UP
```

## 4.4 Kafka ✅

La empresa utiliza Kafka como backbone de eventos.

Infraestructura actual:

```text
storm-kafka
storm-zookeeper
```

Se agregó un listener interno para Company Core:

```text
storm-kafka:19092
```

Se preservó la configuración SSL existente de Storm.

Topic principal de empresa:

```text
EMPRESA_EVENTS
```

### Regla de eventos

Todo evento generado por la empresa debe comenzar con:

```text
EMPRESA_
```

Esto crea una convención clara y permite diferenciar los eventos del negocio de otros eventos técnicos.

## 4.5 Neo4j ✅

Neo4j está operativo como infraestructura y está destinado a convertirse en la memoria estructurada de la empresa.

El objetivo es que Neo4j sea el origen estructurado de información sobre:

- empresa;
- agentes;
- misiones;
- tareas;
- oportunidades;
- clientes;
- decisiones;
- evidencia;
- transacciones;
- aprendizajes;
- estrategia;
- gobernanza.

La memoria estructurada completa todavía está pendiente de implementar.

## 4.6 Ollama ✅

Ollama está desplegado y funcionando con acceso GPU.

Modelos disponibles y utilizados durante las pruebas:

- `llava:7b`
- `qwen2.5-coder:14b`
- `qwen2.5-coder:7b`

La infraestructura fue probada desde Company Core y se confirmó conectividad con Ollama.

---

# 5. Arquitectura de modelos ✅

Se implementó separación de modelos por responsabilidad.

## CEO

```text
qwen2.5-coder:14b
```

Uso:

- razonamiento consolidado;
- evaluación de resultados;
- síntesis de misión;
- recomendación para el inversor.

## Agentes

```text
qwen2.5-coder:7b
```

Uso:

- ejecución de tareas especializadas;
- investigación;
- análisis;
- generación de resultados estructurados.

## Resultado de benchmark

Las pruebas mostraron una diferencia importante de velocidad:

### 7B

- inferencia considerablemente más rápida;
- adecuado para tareas paralelas de agentes;
- alrededor de 67–71 tokens/s en las pruebas observadas después de cargar el modelo.

### 14B

- mejor capacidad de razonamiento;
- mucho más lento en el entorno actual;
- alrededor de 4–6 tokens/s en las pruebas observadas.

Por esta razón se adoptó:

```text
CEO    → 14B
AGENTS → 7B
```

---

# 6. Paralelismo de agentes ✅

Se configuró `agentTaskExecutor` utilizando `CompletableFuture` y `ThreadPoolTaskExecutor`.

Configuración actual aproximada:

```text
Core threads: 4
Max threads: 8
Queue capacity: 0
```

También existe un executor específico para la orquestación de misiones.

Durante las pruebas se confirmó que varios agentes pueden ejecutar tareas concurrentemente.

### Importante

El paralelismo de Ollama sigue limitado actualmente por:

```text
OLLAMA_NUM_PARALLEL=1
```

No se recomienda incrementar esta capacidad todavía porque primero debe estabilizarse el contrato de salida y entenderse completamente el comportamiento de inferencia.

---

# 7. Orquestación de misiones ✅

Se construyó el flujo principal de una misión.

## Estados

```text
PLANNING
DELEGATING
WAITING_AGENT_RESULTS
EVALUATING
CONSOLIDATING
AWAITING_INVESTOR
FAILED
```

El flujo permite:

1. recibir la misión;
2. crear tareas;
3. ejecutar agentes;
4. esperar resultados;
5. consolidarlos;
6. entregarlos al CEO;
7. llegar a una decisión que puede requerir aprobación del inversor.

## Progreso

Se definió progreso de misión con etapas como:

```text
PLANNING              5%
DELEGATING           10%
WAITING_AGENT_RESULTS 30%
EVALUATING           70%
CONSOLIDATING        85%
AWAITING_INVESTOR    95%
FAILED              100%
```

Además se corrigió el inicio semántico de la misión para usar:

```text
PLANNING / Iniciando
```

en lugar de marcar inicialmente la misión como una operación completamente creada.

---

# 8. Sistema de eventos ✅

Se implementó `CompanyEventPublisher`.

Las publicaciones utilizan eventos estructurados y validan que el `eventType` empiece por:

```text
EMPRESA_
```

Ejemplos de eventos ya contemplados:

```text
EMPRESA_MISSION_STARTED
EMPRESA_MISSION_UPDATED
EMPRESA_TASK_STARTED
EMPRESA_TASK_COMPLETED
EMPRESA_TASK_FAILED
EMPRESA_MISSION_FAILED
```

La base está lista para crecer hacia una arquitectura orientada a eventos empresariales.

---

# 9. Contrato estructurado `AgentResult` ✅ / 🔧

Se diseñó un contrato para evitar respuestas libres del modelo.

Actualmente contiene:

```text
agent
action
verificationStatus
facts
hypotheses
estimates
evidence
evidenceRequired
calculations
risks
recommendation
confidence
```

Además:

```text
Evidence
- description
- source
- sourceType
- verified
```

```text
Calculation
- name
- inputA
- inputB
- operation
- result
```

La empresa ya no pretende depender únicamente de texto narrativo generado por el modelo.

---

# 10. AgentResultValidator ✅ / 🔧

Se construyó un validator para comprobar:

- campos obligatorios;
- listas no nulas;
- confianza entre 0 y 1;
- operaciones matemáticas básicas;
- evidencia verificable;
- estados permitidos;
- requisitos mínimos para declarar algo `VALIDATED`.

Los estados soportados son:

```text
NOT_VALIDATED
PARTIALLY_VALIDATED
VALIDATED
```

La regla fundamental es que `VALIDATED` requiere evidencia verificada con fuente.

---

# 11. JSON estructurado con Ollama ✅ / 🔧

Se implementó el modo:

```json
"format": "json"
```

Las pruebas directas demostraron que Ollama puede producir JSON válido con la estructura conceptual requerida.

Sin embargo, se identificó que **JSON válido no significa JSON compatible con `AgentResult`**.

El último error real encontrado fue:

```text
Cannot deserialize value of type `java.lang.String` from Object value
```

con la referencia:

```text
AgentResult["evidenceRequired"]
→ ArrayList[0]
```

Conclusión:

```java
List<String> evidenceRequired
```

está recibiendo al menos un objeto JSON en lugar de un string.

Este problema está actualmente en resolución.

---

# 12. Pruebas realizadas

## 12.1 Prueba de modelos

Se realizaron benchmarks aislados y concurrentes.

Resultados relevantes:

- 14B aislado: alrededor de 66 s para una respuesta corta en la prueba observada.
- 14B con 4 solicitudes: tiempos acumulativos aproximadamente seriales.
- 7B aislado: alrededor de 8 s en una prueba completa.
- 7B con 4 solicitudes: ejecución concurrente con resultados aproximadamente escalonados por inferencia.

Conclusión:

```text
7B → ejecución de agentes
14B → CEO
```

## 12.2 MISSION-MODEL-001 ✅

La misión confirmó el routing:

```text
Agentes → 7B
CEO     → 14B
```

La misión logró avanzar hasta:

```text
AWAITING_INVESTOR
```

También reveló un problema importante: el CEO recibió demasiada información y presentó alto tiempo de inferencia.

En la ejecución observada:

- prompt del CEO: aproximadamente 4.075 tokens;
- output: aproximadamente 962 tokens;
- tiempo total: aproximadamente 245 s.

Se concluyó que el prompt del CEO debe compactarse.

## 12.3 MISSION-STRUCTURED-001 / 002 / 003 / 004 / 005 ✅

Estas misiones se utilizaron como pruebas del nuevo contrato estructurado.

### Resultados descubiertos

- Algunos agentes devolvían JSON encerrado en fences Markdown.
- Algunos agentes llegaban a validación pero incumplían campos obligatorios.
- Se produjo una interrupción de infraestructura porque Ollama estuvo temporalmente detenido.
- La conectividad Company Core → Ollama fue restaurada.
- Después de estabilizar Ollama, las respuestas seguían fallando principalmente en deserialización.

En `MISSION-STRUCTURED-002`, por ejemplo, Finance alcanzó el validator y fue rechazado porque:

```text
recommendation es obligatorio
```

Esto confirmó que el validator sí puede recibir y rechazar resultados del modelo.

## 12.4 MISSION-STRUCTURED-005 ✅ / 🔧

Con `format: json` se comprobó que Ollama respondía, pero los cuatro agentes todavía podían generar estructuras incompatibles.

Ejemplo de tamaños observados:

```text
Sales       ~1265 caracteres
Finance     ~1431
Engineering ~1710
Product      ~798
```

Todos llegaron hasta el problema de parsing en diferentes momentos.

---

# 13. Experimentos de autonomía diseñados / realizados

Además de la infraestructura principal, se exploraron mecanismos para hacer que la empresa pueda evolucionar por sí misma.

## 13.1 Self-Redesigning Organization

La organización puede plantear la creación, eliminación o modificación de roles en función de resultados.

Conceptos definidos:

```text
Role
RoleVersion
AgentVersion
```

La idea es que la estructura organizacional pueda evolucionar, pero mediante reglas controladas.

## 13.2 Internal Prediction Market

Se diseñó un mecanismo para que los agentes realicen predicciones concretas y arriesguen puntos de credibilidad.

Ejemplo:

```text
"La oportunidad X conseguirá un cliente en 7 días"

Resultado:
TRUE / FALSE
```

Esto permitiría construir reputación basada en resultados.

Entidades previstas:

```text
Prediction
PredictionOutcome
CredibilityScore
```

## 13.3 Portfolio de micro-apuestas

Se diseñó la idea de repartir los US$50 en varios experimentos y reasignar capital según evidencia.

Ejemplo conceptual:

```text
US$50
 ├─ A → US$10
 ├─ B → US$10
 ├─ C → US$10
 ├─ D → US$10
 └─ E → US$10
```

El CEO podría mover capital hacia ganadores y eliminar perdedores, sujeto a gobernanza.

Entidades:

```text
Portfolio
Investment
CapitalAllocation
```

## 13.4 Constitución enmendable

Se diseñó una constitución con niveles de autonomía:

```text
GREEN
YELLOW
RED
```

La empresa puede proponer modificaciones a sus reglas, pero las modificaciones importantes deben ser ratificadas por el humano.

Entidad:

```text
GovernanceAmendment
```

## 13.5 Multi-model cabinet

Se planteó un “gabinete” con distintos modelos o perfiles:

```text
CEO / Model A
Finance / Model B
Skeptic / Model C
```

La empresa podría aprender qué modelo funciona mejor para cada tipo de decisión según resultados históricos.

Entidades:

```text
Model
ModelAssignment
```

## 13.6 Tool self-improvement

La empresa debe poder construir y persistir herramientas de software cuando una necesidad operacional se repite.

Ejemplos:

- scrapers;
- conectores;
- formateadores;
- extractores;
- validadores;
- utilidades de cálculo.

No se busca modificar los pesos del modelo. La mejora ocurre mediante software y herramientas.

Entidades:

```text
Tool
ToolVersion
```

## 13.7 Public transparency feed

Se propuso que las decisiones importantes puedan quedar expuestas en un feed transparente:

- decisiones;
- dinero gastado;
- ingresos;
- resultados;
- aprendizajes.

Entidad:

```text
PublicDecision
```

---

# 14. Hallazgos importantes hasta ahora

La experimentación ya permitió identificar problemas que una simple demo no habría revelado.

## 14.1 El modelo puede alucinar

Se observaron afirmaciones sin evidencia suficiente.

Por esto la empresa no puede confiar únicamente en la salida narrativa del modelo.

## 14.2 El modelo puede contradecirse

Se observaron escenarios donde los cálculos y conclusiones no eran consistentes.

Ejemplo observado en una prueba previa:

```text
Costos: US$52.000
Ingresos: US$600
```

La narrativa no puede ser aceptada sin validación matemática y financiera.

## 14.3 Una etiqueta no convierte una afirmación en hecho

Un agente puede escribir “resultado verificado”, pero eso no significa que exista evidencia real.

Por esto debe existir un `Validation Gate` independiente del agente.

## 14.4 JSON libre sigue siendo insuficiente

El modo `format=json` evita texto completamente libre, pero el modelo todavía puede generar:

```json
"evidenceRequired": [{"description":"..."}]
```

en lugar de:

```json
"evidenceRequired": ["clientes reales"]
```

Por ello hace falta un esquema estructural más estricto.

---

# 15. Estado actual real

## Terminado ✅

- [x] Concepto de empresa AI-first definido.
- [x] Regla de capital de US$50.
- [x] Reto máximo de 60 días.
- [x] Criterio de beneficio neto > US$50.
- [x] Company Core.
- [x] Docker Compose.
- [x] Kafka.
- [x] Topic `EMPRESA_EVENTS`.
- [x] Convención de eventos `EMPRESA_`.
- [x] Neo4j operativo.
- [x] Ollama operativo.
- [x] GPU configurada.
- [x] Routing CEO 14B / agentes 7B.
- [x] Ejecución concurrente de agentes.
- [x] Orquestación de misiones.
- [x] Estados de misión.
- [x] CompanyEventPublisher.
- [x] `AgentResult` inicial.
- [x] `Evidence`.
- [x] `Calculation`.
- [x] `AgentResultValidator` inicial.
- [x] JSON mode en Ollama.
- [x] Logging de parsing.
- [x] Benchmarks de modelos.
- [x] Varias misiones de integración ejecutadas.
- [x] Confirmación de conectividad Company Core → Ollama.
- [x] Detección de los primeros fallos reales del contrato estructurado.

---

# 16. Pendientes inmediatos 🔴

## 16.1 Cerrar `AgentResult`

- [ ] Corregir el contrato `evidenceRequired`/salidas incompatibles.
- [ ] Revisar todos los arrays de strings.
- [ ] Confirmar `evidence` y `calculations`.
- [ ] Confirmar `recommendation`.
- [ ] Confirmar `confidence`.
- [ ] Ejecutar prueba estable con los 4 agentes.

## 16.2 JSON Schema

- [ ] Definir JSON Schema completo para `AgentResult`.
- [ ] Pasar de `format=json` a esquema estructurado cuando la implementación lo soporte de forma estable.
- [ ] Probar nuevamente los 4 agentes.

## 16.3 Validation Gate

- [ ] Separar validación sintáctica de validación semántica.
- [ ] Impedir que hipótesis se conviertan automáticamente en hechos.
- [ ] Exigir evidencia para `VALIDATED`.
- [ ] Detectar contradicciones.

## 16.4 CEO compacto

- [ ] Reducir tamaño del prompt.
- [ ] Evitar duplicación.
- [ ] Entregar al CEO exclusivamente información consolidada.
- [ ] Mantener el contexto por debajo de límites razonables.

---

# 17. Evidence Engine 🔥

Pendiente implementar un sistema de evidencia que permita registrar:

```text
Evidence
 ├─ source
 ├─ sourceType
 ├─ timestamp
 ├─ description
 ├─ verified
 ├─ confidence
 └─ supportingClaim
```

La diferencia fundamental debe ser:

```text
El agente afirma X
        ≠
La empresa puede demostrar X
```

---

# 18. Customer Validation 🔥

La empresa debe salir del entorno puramente interno y validar clientes reales.

Flujo objetivo:

```text
Idea
 ↓
Hipótesis
 ↓
Oportunidad
 ↓
Prospecto real
 ↓
Interacción real
 ↓
Oferta real
 ↓
Cliente
 ↓
Venta
```

Evidencia fuerte puede incluir:

- respuesta de cliente;
- pedido;
- reserva;
- pago;
- contrato;
- orden de compra;
- compromiso real verificable.

---

# 19. Primera venta 🔥🔥

Objetivo crítico del reto.

La empresa debe demostrar que puede convertir el capital inicial en dinero real.

Debe registrarse:

```text
Customer
Offer
Price
Cost
Revenue
Net Profit
Evidence
Transaction
```

---

# 20. Revenue / Profit Engine 🔥

Debe existir una contabilidad mínima:

```text
Revenue
- Costos directos
- Gastos atribuibles
= Beneficio neto
```

No se debe confundir:

```text
Ingreso ≠ Beneficio
```

---

# 21. Neo4j Company Memory 🔥

El modelo de memoria propuesto incluye:

```text
Company
Agent
AgentVersion
Role
RoleVersion
Mission
Task
Opportunity
Customer
Prediction
PredictionOutcome
CredibilityScore
Evidence
Decision
GovernanceRule
GovernanceAmendment
Model
ModelAssignment
Tool
ToolVersion
Portfolio
Investment
CapitalAllocation
Transaction
Lesson
Strategy
PublicDecision
```

Neo4j debe convertirse en la memoria relacional/semántica de la empresa, mientras Kafka mantiene el flujo de eventos.

---

# 22. Gobernanza 🔥

## Verde

Autonomía operativa.

Ejemplos:

- investigar;
- analizar;
- redactar;
- crear tareas;
- ejecutar acciones reversibles de bajo riesgo.

## Amarillo

Acciones con límites o autorización según reglas.

Ejemplos:

- pequeños gastos;
- campañas de prueba;
- experimentos comerciales.

## Rojo

Requiere autorización humana.

Ejemplos:

- inversiones importantes;
- contratos;
- compromisos legales;
- gastos materiales;
- acciones irreversibles;
- cambios estratégicos fundamentales.

---

# 23. Sistema de aprendizaje interno 🔥

## Prediction Market

Registrar predicciones y resolverlas contra la realidad.

## Credibility Score

Medir desempeño histórico de cada agente.

## Model Assignment

Aprender qué modelo funciona mejor para qué tarea.

## Lessons

Registrar aprendizajes derivados de éxitos y fracasos.

---

# 24. Reasignación de capital 🔥

El capital debe tratarse como recurso limitado.

Ejemplo:

```text
US$50
 ↓
experimentos
 ↓
medición de resultados
 ↓
reasignación
 ↓
financiación de ganadores
 ↓
eliminación de perdedores
```

Las reasignaciones materiales deberán respetar la gobernanza humana.

---

# 25. Organización autoevolutiva 🔥

La empresa puede llegar a modificar:

- roles;
- responsabilidades;
- agentes;
- prompts;
- herramientas;
- estructura.

La evolución debe estar respaldada por métricas y gobernanza, no por cambios arbitrarios del modelo.

---

# 26. Documentación obligatoria de cada negocio

Cada experimento real deberá generar un archivo Markdown con:

```text
1. Opportunity
2. Research
3. Hypothesis
4. Market
5. Customer
6. Offer
7. Strategy
8. Execution
9. Sales
10. Revenue
11. Costs
12. Profit / Loss
13. Agents
14. Decisions
15. Evidence
16. Result
17. Lessons
18. Final Decision
```

El documento será parte de la memoria histórica de la empresa.

---

# 27. Roadmap de implementación

## FASE 1 — Contracto de agentes 🔴

1. [ ] Resolver `AgentResult`.
2. [ ] Probar 4 agentes.
3. [ ] Implementar JSON Schema.
4. [ ] Confirmar parsing estable.
5. [ ] Confirmar validator estable.
6. [ ] Ejecutar misión completa.

## FASE 2 — CEO 🔴

7. [ ] Compactar prompt.
8. [ ] Mejorar consolidación.
9. [ ] Detectar contradicciones.
10. [ ] Evitar mezclar hechos e hipótesis.

## FASE 3 — Evidencia 🔥

11. [ ] Evidence Engine.
12. [ ] Validation Gate.
13. [ ] Evidencia verificable.
14. [ ] Registro de fuentes.

## FASE 4 — Mercado 🔥🔥

15. [ ] Customer Validation.
16. [ ] Primer prospecto.
17. [ ] Primera oferta.
18. [ ] Primera venta.
19. [ ] Registro de transacción.
20. [ ] Cálculo de beneficio neto.

## FASE 5 — Empresa autónoma 🔥🔥🔥

21. [ ] Neo4j Company Memory.
22. [ ] Gobernanza financiera.
23. [ ] Prediction Market.
24. [ ] Model Assignment.
25. [ ] Tool self-improvement.
26. [ ] Capital allocation.
27. [ ] Constitución evolutiva.
28. [ ] Auditoría y transparencia.

---

# 28. Milestones

## MILESTONE 1 — `AGENT-CONTRACT-001`

```text
4 agentes
  ↓
4 AgentResult válidos
  ↓
Validator OK
  ↓
CEO recibe estructura
  ↓
CEO consolida
  ↓
AWAITING_INVESTOR
```

## MILESTONE 2 — `EVIDENCE-001`

```text
Oportunidad
  ↓
Evidencia
  ↓
Validación
  ↓
Opportunity VALIDATED
```

## MILESTONE 3 — `CUSTOMER-001`

```text
Oportunidad
  ↓
Cliente real
  ↓
Oferta
  ↓
Interés real
```

## MILESTONE 4 — `SALE-001`

```text
Cliente
  ↓
Venta
  ↓
Revenue
  ↓
Costos
  ↓
Beneficio neto
```

## MILESTONE 5 — `COMPANY-001`

```text
Empresa AI
  ↓
Opera
  ↓
Vende
  ↓
Aprende
  ↓
Reasigna recursos
  ↓
Mejora
```

---

# 29. Cadena objetivo completa

La visión técnica y de negocio termina convergiendo en esta cadena:

```text
                  ┌──────────────┐
                  │   MISIÓN     │
                  └──────┬───────┘
                         ▼
                  ┌──────────────┐
                  │    AGENTES   │
                  └──────┬───────┘
                         ▼
                 JSON ESTRUCTURADO
                         ▼
                    VALIDACIÓN
                         ▼
                      EVIDENCIA
                         ▼
                        CEO
                         ▼
                      DECISIÓN
                         ▼
                  APROBACIÓN HUMAN
                         ▼
                     ACCIÓN REAL
                         ▼
                      CLIENTE
                         ▼
                       VENTA
                         ▼
                      INGRESO
                         ▼
                       COSTO
                         ▼
                  BENEFICIO NETO
                         ▼
                     APRENDIZAJE
                         ▼
                 MEMORIA / NEO4J
                         ▼
                  NUEVA DECISIÓN
```

---

# 30. Qué NO debemos hacer todavía

Hasta estabilizar el contrato estructurado:

- no aumentar agresivamente el paralelismo;
- no cambiar de modelo por cada fallo;
- no optimizar GPU prematuramente;
- no construir decenas de herramientas autónomas;
- no considerar una oportunidad válida solo porque el CEO la recomiende;
- no considerar revenue como profit;
- no considerar una afirmación del agente como evidencia.

Primero se debe cerrar la cadena de confiabilidad.

---

# 31. Estado del proyecto en una frase

La empresa ya pasó la etapa de **“¿podemos levantar una infraestructura de agentes?”**.

Ahora está en la etapa de:

> **“¿podemos hacer que los agentes produzcan información confiable, que el CEO pueda evaluarla y que esa organización llegue a ejecutar una transacción económica real?”**

Ese es el siguiente salto importante.

---

# 32. Objetivo final

No estamos construyendo simplemente un chatbot, un framework de agentes ni una demo.

Estamos intentando construir una **empresa real con trabajadores de IA**, capaz de:

```text
DESCUBRIR
   ↓
PENSAR
   ↓
PROPONER
   ↓
EJECUTAR
   ↓
VENDER
   ↓
COBRAR
   ↓
MEDIR
   ↓
APRENDER
   ↓
MEJORAR
```

con un principio central:

> **La IA puede operar la empresa, pero el mercado decide si la empresa merece existir.**
