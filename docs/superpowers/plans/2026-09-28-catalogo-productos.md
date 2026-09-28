# Catálogo de productos y servicios — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un catálogo de productos/servicios con ciclo de vida controlado por Java (requisitos para "listo para vender"), editable en el Command Center, con automatizaciones deterministas (discovery → idea; construcción verificada → intento de "listo"), comandos del fundador en el chat y ventas por producto en Finanzas.

**Architecture:** `ProductReadiness` (puro) decide los requisitos; `ProductService` aplica transiciones, reglas de actor e historial sobre `ProductMemoryService` (Cypher); `ProductAutomation` engancha `MissionExecutor` (setter opcional, sin tocar su constructor). Finanzas gana `productId` en ventas. Chat y pantalla leen de `ProductService`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j driver plano, Kafka, JUnit 5 + Mockito; React + TS + react-query.

**Spec:** `docs/superpowers/specs/2026-09-28-catalogo-productos-design.md`

## Global Constraints

- Nombres Java: `CatalogProduct`, `CatalogStatus` (ya existe `ProductStatus` para el estado del producto de una misión — no tocarlo).
- `CatalogStatus`: `IDEA`, `IN_CONSTRUCTION`, `READY_TO_SELL`, `PAUSED`, `RETIRED`. `kind`: `SOFTWARE` | `SERVICE`.
- Actor: `"human"` (fundador) o un `agentId`/`"system"`. Solo `"human"` pausa, reanuda, retira y reactiva.
- `READY_TO_SELL` exige: cliente objetivo + (precio > 0 o a cotizar); misión `VALIDATED_BY` sin `teamId` con `Evidence {sourceType:'WEB'}`; `SOFTWARE`: misión `BUILT_BY` con `VALIDATION` `VERIFIED` / `SERVICE`: `delivery` no vacío; margen: a cotizar o precio > costo estimado.
- `markets` default `["WORLDWIDE"]`, `languages` default `["en","es"]`; nunca un país por defecto.
- Historial `ProductChange` inmutable; eventos `EMPRESA_PRODUCT_CREATED|UPDATED|STATUS_CHANGED` con `agentId` = actor.
- Errores `IllegalArgumentException` (la pantalla muestra `message`); chat 100% Java; `types.ts` sincronizado; rutas en `SpaController`.

## Review Focus

- Dos productos con nombres parecidos ("Landing" y "Landing Pro") en "pausa el producto landing" → ambiguo: lista candidatos, no cambia nada (test Task 5).
- Una ronda de evidencia re-consolida la misma misión de discovery → la idea no se duplica (test Task 4).
- Editar el precio de un producto `READY_TO_SELL` por debajo del costo → vuelve a `IN_CONSTRUCTION` con el motivo en el historial (test Task 2).
- Producto `PAUSED` cuya misión de construcción queda `VERIFIED` → no se mueve (test Task 4).
- Venta con `productId` inexistente → rechazo claro, no se registra (test Task 3).

---

### Task 1: Modelo y requisitos (puro)

**Files:** Create `model/CatalogStatus.java`, `model/CatalogProduct.java`, `model/ProductEvidence.java`, `service/ProductReadiness.java`; Test `service/ProductReadinessTest.java`.

**Interfaces (Produces):**
- `enum CatalogStatus { IDEA, IN_CONSTRUCTION, READY_TO_SELL, PAUSED, RETIRED }`
- `record CatalogProduct(String id, String name, String description, String kind, String targetCustomer, double priceUsd, boolean priceOnRequest, double estimatedCostUsd, String delivery, List<String> markets, List<String> languages, CatalogStatus status, CatalogStatus statusBeforePause, String createdBy, Instant createdAt, Instant updatedAt, List<String> validatedBy, List<String> builtBy)`
- `record ProductEvidence(boolean demandWithWebEvidence, boolean buildVerified)` (lo arma la memoria desde las misiones).
- `static List<String> ProductReadiness.missing(CatalogProduct p, ProductEvidence e)` → lista vacía = listo; cada ítem es una frase en español.

- [ ] **Step 1: Test que falla**

```java
class ProductReadinessTest {
    private static CatalogProduct product(String kind, String target, double price, boolean onRequest, double cost, String delivery) {
        return new CatalogProduct("P1", "Landing", "d", kind, target, price, onRequest, cost, delivery, List.of("WORLDWIDE"),
                List.of("en", "es"), CatalogStatus.IN_CONSTRUCTION, null, "human", Instant.now(), Instant.now(), List.of(), List.of());
    }

    @Test void aCompleteSoftwareProductIsReady() {
        assertEquals(List.of(), ProductReadiness.missing(product("SOFTWARE", "Pymes", 120, false, 10, null),
                new ProductEvidence(true, true)));
    }

    @Test void eachMissingRequirementIsListed() {
        var missing = ProductReadiness.missing(product("SOFTWARE", " ", 0, false, 0, null), new ProductEvidence(false, false));
        assertEquals(4, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("cliente objetivo") && missing.get(0).contains("precio"), missing.get(0));
        assertTrue(missing.stream().anyMatch(m -> m.contains("evidencia web")));
        assertTrue(missing.stream().anyMatch(m -> m.contains("VERIFIED")));
        assertTrue(missing.stream().anyMatch(m -> m.contains("margen")));
    }

    @Test void aServiceNeedsDeliveryInsteadOfAVerifiedBuild() {
        assertEquals(List.of(), ProductReadiness.missing(product("SERVICE", "Pymes", 50, false, 5, "Llamada + informe"),
                new ProductEvidence(true, false)));
        assertTrue(ProductReadiness.missing(product("SERVICE", "Pymes", 50, false, 5, ""), new ProductEvidence(true, true))
                .stream().anyMatch(m -> m.contains("cómo se entrega")));
    }

    @Test void priceOnRequestSatisfiesPriceAndMargin() {
        assertEquals(List.of(), ProductReadiness.missing(product("SOFTWARE", "Pymes", 0, true, 30, null), new ProductEvidence(true, true)));
    }

    @Test void aZeroOrNegativeMarginIsNotReady() {
        assertTrue(ProductReadiness.missing(product("SOFTWARE", "Pymes", 10, false, 10, null), new ProductEvidence(true, true))
                .stream().anyMatch(m -> m.contains("margen")));
    }
}
```
- [ ] **Step 2:** `mvn -q test -Dtest=ProductReadinessTest` → FAIL (no compila).
- [ ] **Step 3: Implementación**

```java
public final class ProductReadiness {
    private ProductReadiness() {}

    public static List<String> missing(CatalogProduct p, ProductEvidence e) {
        var out = new ArrayList<String>();
        var hasTarget = p.targetCustomer() != null && !p.targetCustomer().isBlank();
        var hasPrice = p.priceOnRequest() || p.priceUsd() > 0;
        if (!hasTarget || !hasPrice) {
            out.add("Falta " + (!hasTarget ? "el cliente objetivo" : "") + (!hasTarget && !hasPrice ? " y " : "")
                    + (!hasPrice ? "el precio (o marcarlo a cotizar)" : "") + ".");
        }
        if (!e.demandWithWebEvidence()) {
            out.add("Falta una misión de discovery asociada con evidencia web de demanda.");
        }
        if ("SERVICE".equals(p.kind())) {
            if (p.delivery() == null || p.delivery().isBlank()) {
                out.add("Falta describir cómo se entrega el servicio.");
            }
        } else if (!e.buildVerified()) {
            out.add("Falta una misión de construcción asociada con su validación en VERIFIED.");
        }
        if (!p.priceOnRequest() && p.priceUsd() <= p.estimatedCostUsd()) {
            out.add("El margen estimado no es positivo: el precio debe ser mayor que el costo estimado por venta.");
        }
        return out;
    }
}
```
(Ajustar el primer mensaje para que siempre contenga "cliente objetivo" y "precio" cuando faltan ambos, como exige el test.)
- [ ] **Step 4:** test → PASS; suite verde.
- [ ] **Step 5: Commit** `Catálogo: modelo y requisitos para listo para vender (puro)`

---

### Task 2: Persistencia y servicio (transiciones, actor, historial)

**Files:** Create `service/ProductMemoryService.java` (Neo4j), `service/ProductService.java`, `model/ProductCommand.java`, `model/ProductChange.java`, `model/ProductView.java`; Modify `CompanyMemoryService` (constraint `product_id`); Test `service/ProductServiceTest.java`.

**Interfaces (Produces):**
- `record ProductCommand(String name, String description, String kind, String targetCustomer, Double priceUsd, Boolean priceOnRequest, Double estimatedCostUsd, String delivery, List<String> markets, List<String> languages, String reason)` (campos null = no cambiar en una edición).
- `record ProductChange(String actor, String field, String from, String to, String reason, Instant at)`.
- `record ProductView(CatalogProduct product, List<String> missing, List<ProductChange> history)`.
- `ProductMemoryService`: `void create(CatalogProduct)`, `Optional<CatalogProduct> find(String id)`, `List<CatalogProduct> all()`, `void save(CatalogProduct)`, `void addChange(String productId, ProductChange)`, `List<ProductChange> history(String id)`, `void link(String productId, List<String> validatedBy, List<String> builtBy)`, `ProductEvidence evidence(CatalogProduct)`, `boolean missionExists(String)`, `List<String> productsBuiltBy(String missionId)`, `Optional<String> ideaFromMission(String missionId)` (id del producto `VALIDATED_BY` esa misión creado por automatización).
- `ProductService`: `ProductView create(ProductCommand, String actor)`, `ProductView update(String id, ProductCommand, String actor)`, `ProductView changeStatus(String id, CatalogStatus to, String reason, String actor)`, `ProductView linkMissions(String id, List<String> validatedBy, List<String> builtBy, String actor)`, `List<ProductView> list()`, `Optional<ProductView> view(String id)`, `List<CatalogProduct> findByName(String text)` (sin mayúsculas ni tildes, contiene).

- [ ] **Step 1: Tests que fallan** (memoria y eventos mockeados; `service = new ProductService(memory, events)`):

```java
@Test void createDefaultsToIdeaWorldwideAndRecordsHistory() {
    var view = service.create(new ProductCommand("Landing", "Landing para pymes", "SOFTWARE", null, null, null, null, null, null, null, null), "human");
    assertEquals(CatalogStatus.IDEA, view.product().status());
    assertEquals(List.of("WORLDWIDE"), view.product().markets());
    assertEquals(List.of("en", "es"), view.product().languages());
    verify(memory).create(any());
    verify(events).publish(eq("EMPRESA_PRODUCT_CREATED"), isNull(), isNull(), eq("human"), anyMap());
}

@Test void readyIsRejectedWithTheMissingRequirements() {
    when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.IN_CONSTRUCTION, 0, 0)));
    when(memory.evidence(any())).thenReturn(new ProductEvidence(false, false));
    var ex = assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "listo", "engineering"));
    assertTrue(ex.getMessage().contains("evidencia web"), ex.getMessage());
    verify(memory, never()).save(any());
}

@Test void onlyTheFounderPausesOrRetires() {
    when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.READY_TO_SELL, 120, 10)));
    assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.PAUSED, "x", "sales"));
    var paused = service.changeStatus("P1", CatalogStatus.PAUSED, "Sin stock", "human");
    assertEquals(CatalogStatus.PAUSED, paused.product().status());
    assertEquals(CatalogStatus.READY_TO_SELL, paused.product().statusBeforePause());
}

@Test void resumingGoesBackToTheStateBeforeThePause() {
    when(memory.find("P1")).thenReturn(Optional.of(withPause(CatalogStatus.READY_TO_SELL)));
    when(memory.evidence(any())).thenReturn(new ProductEvidence(true, true));
    assertEquals(CatalogStatus.READY_TO_SELL, service.changeStatus("P1", null, "Reanudar", "human").product().status());
}

@Test void editingAReadyProductBelowCostSendsItBackToConstruction() {
    when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.READY_TO_SELL, 120, 10)));
    when(memory.evidence(any())).thenReturn(new ProductEvidence(true, true));
    var view = service.update("P1", new ProductCommand(null, null, null, null, 5.0, null, null, null, null, null, "rebaja"), "human");
    assertEquals(CatalogStatus.IN_CONSTRUCTION, view.product().status());
    verify(memory).addChange(eq("P1"), argThat(c -> "status".equals(c.field()) && c.reason().contains("margen")));
}

@Test void aRetiredProductCanOnlyBeReactivatedToIdeaByTheFounder() {
    when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.RETIRED, 120, 10)));
    assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "x", "human"));
    assertEquals(CatalogStatus.IDEA, service.changeStatus("P1", CatalogStatus.IDEA, "Reactivar", "human").product().status());
}

@Test void findByNameIgnoresCaseAndAccents() {
    when(memory.all()).thenReturn(List.of(named("Asesoría Contable"), named("Landing")));
    assertEquals("Asesoría Contable", service.findByName("asesoria").get(0).name());
}
```
(`product(status, price, cost)`, `withPause(before)`, `named(name)` son helpers del test que arman un `CatalogProduct` SOFTWARE con cliente objetivo.)
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3: Implementación.** Reglas de `changeStatus(id, to, reason, actor)`:
  - `to == null` significa "reanudar": solo desde `PAUSED`, vuelve a `statusBeforePause`.
  - `PAUSED`, `RETIRED`, reanudar y `RETIRED → IDEA` solo con `actor == "human"`; si no: `"Solo el fundador puede pausar, reanudar, retirar o reactivar un producto."`.
  - Desde `RETIRED` solo a `IDEA`. Desde `PAUSED` solo reanudar o `RETIRED`.
  - A `READY_TO_SELL` (también al reanudar hacia él): `missing = ProductReadiness.missing(p, memory.evidence(p))`; si no está vacío → `IllegalArgumentException("No puede pasar a listo para vender: " + String.join(" ", missing))`.
  - Guarda, agrega `ProductChange(actor, "status", from, to, reason, now)` y publica `EMPRESA_PRODUCT_STATUS_CHANGED`.
  - `update`: aplica los campos no null, un `ProductChange` por campo cambiado, publica `EMPRESA_PRODUCT_UPDATED`; si el producto estaba `READY_TO_SELL` y ahora `missing` no está vacío → `IN_CONSTRUCTION` con `ProductChange(actor, "status", "READY_TO_SELL", "IN_CONSTRUCTION", "Deja de cumplir: " + missing, now)`.
  - Validación de campos: `name` obligatorio en `create`; `kind` ∈ {SOFTWARE, SERVICE} (default SOFTWARE); montos ≥ 0.
  - `ProductMemoryService.evidence(p)`: `demandWithWebEvidence` = existe `(m:Mission)` con id en `p.validatedBy()`, `m.teamId IS NULL`, y `(m)-[:HAS_TASK]->(:AgentTask)-[:HAS_EVIDENCE]->(:Evidence {sourceType:'WEB'})`; `buildVerified` = existe `(m:Mission)` en `p.builtBy()` con `(m)-[:HAS_TASK]->(:AgentTask {kind:'VALIDATION', validationStatus:'VERIFIED'})`.
  - Persistencia: `(:Product {…})` con `markets`/`languages` como listas; `VALIDATED_BY`/`BUILT_BY` a `Mission`; `(:Product)-[:HAS_CHANGE]->(:ProductChange)`. Constraint `CREATE CONSTRAINT product_id IF NOT EXISTS FOR (p:Product) REQUIRE p.id IS UNIQUE`.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Catálogo: persistencia y servicio (transiciones, actor, historial, requisitos)`

---

### Task 3: API y ventas por producto en Finanzas

**Files:** Create `controller/ProductController.java`; Modify `model/FinanceSaleCommand.java` (+`productId`), `model/FinanceMovement.java` (+`productId`), `service/FinanceCalculator.java` (filtro opcional por producto), `service/FinanceService.java`, `service/FinanceMemoryService.java`; Tests `controller/ProductControllerTest.java`, `FinanceCalculatorTest`, `FinanceServiceTest`.

**Interfaces:** `FinanceCalculator.summarize(List<FinanceMovement>, double, String missionIdOrNull, String productIdOrNull)` (el de 3 args delega con `null`); `FinanceService.summaryForProduct(String productId)`; `FinanceService` recibe `ProductMemoryService` para validar `productId`. Rutas: `GET /api/company/products`, `GET /{id}`, `POST`, `PUT /{id}`, `PUT /{id}/status` `{status, reason}` (`status` vacío = reanudar), `PUT /{id}/missions` `{validatedBy, builtBy}`; el Command Center actúa como `"human"`.

- [ ] **Step 1: Tests que fallan:** calculador filtra por producto (dos ventas de productos distintos → solo cuenta la del filtro, y las correcciones de esa venta); `registerSale` con `productId` inexistente → `IllegalArgumentException("No existe el producto …")` sin `createSale`; controller delega (`list`, `changeStatus` con `"human"`).
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** implementar; `FinanceMemoryService.movements()` lee `t.productId` (ventas) y el de la venta corregida (correcciones); `createSale` guarda `productId` y `(:Transaction)-[:OF_PRODUCT]->(:Product)` si viene.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Catálogo: API /api/company/products y ventas por producto en Finanzas`

---

### Task 4: Automatizaciones deterministas (A)

**Files:** Create `service/ProductAutomation.java`; Modify `service/MissionExecutor.java` (setter `@Autowired(required = false) setProductAutomation(ProductAutomation)` y dos llamadas protegidas con try/catch que solo loguean); Test `service/ProductAutomationTest.java`, `MissionExecutorTest`.

**Interfaces:** `ProductAutomation.ideaFromDiscovery(String missionId, AgentResult productResult)`; `ProductAutomation.buildFinished(String missionId)`.

- [ ] **Step 1: Tests que fallan:**
  - Discovery con resultado de `product` y `recommendation` no vacía → `ProductService.create` con actor `"product"`, nombre = primera oración de la recomendación (máx. 80 caracteres), descripción = recomendación, y `linkMissions(id, [missionId], [])`.
  - Misma misión otra vez (ronda) → `memory.ideaFromMission(missionId)` presente → `update` del mismo producto, sin `create`.
  - Recomendación vacía o sin resultado de `product` → no crea nada.
  - `buildFinished`: producto `IDEA` asociado → `IN_CONSTRUCTION` y, si `missing` vacío, `READY_TO_SELL` (actor `"system"`); con requisitos faltantes queda `IN_CONSTRUCTION` y agrega un `ProductChange` con lo que falta; `PAUSED`/`RETIRED` no se tocan.
  - `MissionExecutorTest`: al consolidar discovery con la automatización seteada se llama `ideaFromDiscovery` con el resultado de `product`; si la automatización lanza, la misión igual llega a `AWAITING_INVESTOR`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** implementar. Enganches en `MissionExecutor`: en `consolidateAgentOutcomes`, después de `AWAITING_INVESTOR` y solo sin `teamId`, buscar el outcome exitoso de `"product"`; en `consolidateDevelopment` y en el `AWAITING_INVESTOR` de equipos de análisis, `buildFinished(missionId)`.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Catálogo: discovery crea ideas y una construcción verificada intenta marcar listo`

---

### Task 5: Chat (consultas, comandos del fundador y status)

**Files:** Modify `ChatIntentRouter.java` (+`ProductService`, `FinanceService` ya está), `CeoService.java` (topic `PRODUCTS`); Test `ChatIntentRouterTest.java`.

- [ ] **Step 1: Tests que fallan:**
  - "¿qué productos tenemos?" / "catálogo" → lista por estado con nombre y precio.
  - "¿qué le falta a asesoria contable para venderse?" → requisitos ✅/❌ del producto (por nombre sin tildes).
  - "pausa el producto landing" → `changeStatus(id, PAUSED, mensaje, "human")` y confirma; con "Landing" y "Landing Pro" → lista candidatos y no llama a `changeStatus`.
  - "reanuda landing", "retira landing", "reactiva landing" → `null`, `RETIRED`, `IDEA`.
  - Los comandos de producto van con la gobernanza (antes que las menciones): "pausa el producto landing @Kira" pausa y Kira no responde.
  - "dame un status" suma "Productos: 1 listo(s) para vender, 2 en construcción, 1 idea(s)".
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** implementar: patrón `(?i)\b(pausa|reanuda|retira|reactiva)\b.*` con resolución de nombre por `ProductService.findByName` (si hay coincidencia exacta de nombre normalizado, gana; si hay >1 candidato, listar); `isGovernance` incluye estos comandos; `QueryIntent.PRODUCTS` por "producto", "catalogo", "servicio que ofrecemos"; "le falta" + nombre → detalle; `answerMemoryTopic("PRODUCTS")`.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Chat: catálogo, requisitos por producto y comandos del fundador (pausar, reanudar, retirar, reactivar)`

---

### Task 6: Pantalla "Productos" y producto en ventas

**Files:** Create `frontend/src/pages/ProductsPage.tsx`; Modify `types.ts`, `client.ts`, `App.tsx`, `Layout.tsx`, `FinancePage.tsx` (selector de producto en la venta), `SpaController` + `SpaControllerTest` (`/productos`).

- [ ] **Step 1:** `SpaControllerTest` exige `/productos` → FAIL → agregar → PASS.
- [ ] **Step 2:** Pantalla: catálogo agrupado por estado con requisitos ✅/❌ (`missing`); detalle con formulario de edición (motivo opcional), selector de misiones para `validatedBy`/`builtBy`, botones según estado (Idea/En construcción/Listo para vender; Pausar, Reanudar, Retirar, Reactivar), historial y finanzas del producto (`api.finance` con `productId`); formulario "Nuevo producto" (nombre, descripción, tipo, cliente objetivo, precio o a cotizar, costo estimado, cómo se entrega, mercados, idiomas). Errores del servidor en `<p className="error">`.
- [ ] **Step 3:** `mvn -q test` verde; `npm run lint && npm run build` sin errores nuevos.
- [ ] **Step 4: Commit** `Command Center: pantalla Productos y producto en las ventas`

---

### Task 7: Documentación y verificación en vivo

- [ ] `CLAUDE.md` (catálogo, automatizaciones, comandos, pantalla), `docs/EVENTS.md` (3 eventos), `docs/HISTORY.md`.
- [ ] En vivo (redeploy seguro), con datos de prueba: crear producto de prueba → "listo" rechazado con lo que falta → asociar `MISSION-RONDAS-DISC` (discovery con evidencia web) y `MISSION-E2E-ENG` (`VERIFIED`) → precio/costo → "listo" → en el chat "¿qué productos tenemos?", "pausa el producto <nombre>", "reanuda …"; lanzar una discovery `TEST` corta y ver que crea una idea; borrar los datos de prueba (mostrando antes qué se borra).
- [ ] Commit `Documentar el catálogo de productos y su verificación en vivo`.

## Self-review

- Cobertura: §1 datos → T1/T2; §2 reglas → T1/T2; §3 API → T3; §4 pantalla → T6; §5 chat → T5; §6 A → T4, B → T5; Finanzas por producto → T3; testing y en vivo → T1–T7.
- Tipos: `CatalogProduct`/`CatalogStatus`/`ProductEvidence` (T1) en T2–T6; `ProductService` (T2) en T3–T5; `summarize(…, productId)` (T3) en T6.
