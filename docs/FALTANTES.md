# Faltantes del Development Group

**Actualizado**: 2026-10-02 (después de los bloques 1 y 3, "agentes encendidos y apagados" y el diseño de Diego como DBA). El equipo es híbrido: los roles bloqueados por "fases" se corrigen uno por uno, empezando por Diego.
**Objetivo de la etapa**: que el Development Group entregue software real y verificado (ver `docs/superpowers/specs/2026-10-01-development-group-fase1-design.md` y la hoja de ruta `docs/superpowers/plans/2026-10-01-development-group-fase1-roadmap.md`).

Lo que hoy impide que un pedido grande (p. ej. una plataforma de psicología online con Flutter, .NET y PostgreSQL) llegue a `VERIFIED`, y lo que quedó pendiente en los bloques ya hechos. Cuando se cierre un faltante, se marca `[x]` con el PR que lo resolvió.

## 1. Bloquean pedidos grandes (por resolver)

| | Faltante | Por qué frena una misión real | Dónde se resuelve |
|---|---|---|---|
| [ ] | **Aria (HUs, BDD, MVP por incrementos)** | Nadie recorta el pedido a un MVP: Neo intenta construir todo de una vez | Bloque 5 |
| [ ] | **Misiones durables** | Las misiones viven en memoria: un reinicio de `company-core` las corta (pasó con `MISSION-1790905978528`) | Bloque 4 |
| [ ] | **Corrección hasta cero errores** | Hoy solo se corrigen errores de compilación, en 2 rondas; un test que falla deja la misión en `FAILED` | Bloque 7 |
| [ ] | **Escenarios BDD trazables** | `VERIFIED` solo exige que compile y pase al menos 1 test, no que se cumplan los criterios de aceptación | Bloques 5 y 6 |
| [ ] | **Sandbox con 6 pasos y tests con nombre** | El arranque está mezclado con el smoke y los tests solo se cuentan, no se identifican | Bloque 2 |
| [ ] | **Varios stacks en una misión** | Una misión usa un solo perfil: Flutter + .NET + PostgreSQL juntos no existe (Neo eligió solo Flutter en el intento de psicología) | Fase 2 |
| [ ] | **PostgreSQL en el sandbox** | Un producto con base de datos no tiene dónde probarse | Fase 2 |
| [ ] | **Entrega y DevOps (Andrea)** | No hay Dockerfile ni CI del producto generado | Fase 2 |
| [ ] | **Diego como DBA: conexiones y PostgreSQL** (incluido AWS RDS) | Diego no puede crear bases reales ni hay dónde cargar credenciales | Spec `2026-10-02-diego-dba-postgresql-design.md` |
| [ ] | **Diego con MongoDB** (incluido AWS DocumentDB) | Colecciones, validación e índices aplicados con las credenciales del fundador | Spec propio, después de PostgreSQL |
| [ ] | **Diego con Firebase/Firestore** | Colecciones, índices y reglas con la cuenta de servicio del fundador | Spec propio |
| [ ] | **Habilitar a Kael (juegos 2D con Godot)** | El equipo es híbrido (decisión del fundador 2026-10-02): Kael está encendido pero su rol está bloqueado por "fase 3" | Siguiente rol a corregir |
| [ ] | **Juegos (Kael, Orion, Luna)** | Godot, Blender y proveedores de arte y audio no están habilitados | Fase 3 |
| [ ] | **Entrega, chat y pantalla del Development Group** | `DeliveryResult`, consultas de rondas y backlog en el chat, detalle en Missions | Bloque 8 |
| [ ] | **Prueba de aceptación en vivo** | Hello World Flutter en `VERIFIED` por chat, incluido un reinicio a mitad de misión | Bloque 9 |

## 2. Pendientes de bloques ya hechos

| | Pendiente | Origen | Notas |
|---|---|---|---|
| [ ] | **Tope global de llamadas al modelo por tarea** | Bloque 3 (revisión I-4) | Hoy un intento puede hacer hasta 8 lotes × 3 intentos, y más en las correcciones; sin presupuesto por misión. Se decide con el bloque 7 |
| [ ] | **Nuevo dueño de `OFFER_DESIGN` en Discovery** | Bloque 1 | **Obligatorio antes de descongelar Forjai**: `MissionExecutor` y `ProductAutomation.PRODUCT_AGENT` siguen apuntando a Luna (`product`), que pasó al Development Group |
| [ ] | Probar la migración `TEAM-ENGINEERING` → `TEAM-DEVELOPMENT` con test, incluido el caso con ambos equipos a la vez | Bloque 1 (m-1) | En vivo ya se verificó el caso normal (2026-10-01) |
| [ ] | Rondas de evidencia de misiones viejas reusan el plan viejo sin revalidarlo | Bloque 1 (m-2) | Neo volvería a escribir código en esas rondas |
| [ ] | "Stacks disponibles" escrito a mano en el error del validador | Bloque 1 (m-3) | Debería salir de `StackProfile.enabledNow()` |
| [ ] | Rama sin uso en `TeamPlanValidator.capabilityError` (lista concatenada) | Bloque 1 (m-4) | Limpieza |
| [ ] | Javadoc de `StackProfile.layers()` desplazado | Bloque 1 (m-5) | Limpieza |
| [ ] | `CeoService` describe `TEAM-DEVELOPMENT` con "juegos" (son fase 3) | Bloque 1 (m-6) | Texto para el modelo |
| [ ] | El chat no reconoce "equipo de desarrollo" como nombre del equipo | Bloque 1 (m-7) | Agregar frases exactas, no la palabra suelta "desarrollo" |
| [ ] | Etiquetas "Engineering" en `DependenciesPage` y `ProductsPage` | Bloque 1 | Solo texto |
| [ ] | El resultado guardado de cada tarea incluye `"complete":true,"remainingPaths":[]` | Bloque 3 (m-12) | Ruido en Activity, sin efecto funcional |

## 3. Fuera del código

| | Pendiente | Notas |
|---|---|---|
| [ ] | Cambiar la contraseña de Neo4j y actualizar `NEO4J_PASSWORD` en `.env` | Quedó expuesta en una salida de comando el 2026-10-01. Los otros proyectos que usan la misma instancia también necesitan la nueva |
| [ ] | Aviso de lint preexistente en `SettingsPage.tsx:201` (`set-state-in-effect`) | No es del Development Group |
