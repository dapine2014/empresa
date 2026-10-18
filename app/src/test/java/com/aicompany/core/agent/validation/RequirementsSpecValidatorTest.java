package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.RequirementsSpec.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 2026-10-03 Aria §2: Java valida el spec de Aria; ella corrige con el motivo exacto. */
class RequirementsSpecValidatorTest {

    private static Scenario esc(String id) {
        return new Scenario(id, "t", List.of("dado"), List.of("cuando"), List.of("entonces"));
    }

    private static UserStory hu(String id, String role, List<String> deps, Scenario... scenarios) {
        return new UserStory(id, role, "quiero algo", "para algo", List.of("criterio"), deps, List.of(scenarios));
    }

    private static RequirementsSpec spec(List<Role> roles, List<UserStory> stories, List<Increment> increments) {
        return new RequirementsSpec("CRM", List.of("supuesto"), List.of("fuera"), roles, stories, increments, "APP");
    }

    private static final List<Role> ROLES = List.of(new Role("vendedor", "registra clientes"),
            new Role("gerente", "mira el pipeline"));

    @Test
    void experienceIsRequiredAndMustBeKnown() {
        var missing = withExperience(null);
        assertTrue(RequirementsSpecValidator.validate(missing).stream().anyMatch(e -> e.contains("experience")));
        assertTrue(RequirementsSpecValidator.validate(withExperience("WEB")).stream().anyMatch(e -> e.contains("experience")));
        assertEquals(List.of(), RequirementsSpecValidator.validate(withExperience("GAME")));
    }

    private static RequirementsSpec withExperience(String experience) {
        return new RequirementsSpec("Pong", List.of(), List.of(), List.of(new RequirementsSpec.Role("jugador", "x")),
                List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "mover", "jugar", List.of("c"), List.of(),
                        List.of(new RequirementsSpec.Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))))),
                List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), experience);
    }

    @Test
    void aWellFormedSpecPasses() {
        var spec = spec(ROLES, List.of(hu("HU-01", "vendedor", List.of(), esc("ESC-01")),
                        hu("HU-02", "gerente", List.of("HU-01"), esc("ESC-02"))),
                List.of(new Increment(1, "MVP", List.of("HU-01")), new Increment(2, "Pipeline", List.of("HU-02"))));
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
    }

    @Test
    void rolesMustExistBeUniqueAndBeUsedByEveryStory() {
        var errors = RequirementsSpecValidator.validate(spec(
                List.of(new Role("vendedor", "x"), new Role("vendedor", "y")),
                List.of(hu("HU-01", "cliente", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-01")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"vendedor\" está repetido")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-01") && e.contains("\"cliente\"")), errors.toString());
        assertFalse(RequirementsSpecValidator.validate(spec(List.of(), List.of(), List.of())).isEmpty());
    }

    @Test
    void idsScenariosAndStepsAreChecked() {
        var errors = RequirementsSpecValidator.validate(spec(ROLES, List.of(
                        hu("HU-1", "vendedor", List.of()),
                        new UserStory("HU-02", "vendedor", "q", "p", List.of(), List.of(),
                                List.of(new Scenario("ESC-01", "t", List.of(), List.of("c"), List.of("e")))),
                        hu("HU-03", "vendedor", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-1", "HU-02", "HU-03")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"HU-1\"") && e.contains("HU-01")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-1") && e.contains("escenario")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-02") && e.contains("criterio")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-01") && e.contains("given")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-01") && e.contains("repetido")), errors.toString());
    }

    @Test
    void incrementsAreNumberedWithoutGapsEachStoryOnceAndAtMostTwelveScenarios() {
        var many = new ArrayList<Scenario>();
        for (int i = 1; i <= 13; i++) {
            many.add(esc(String.format("ESC-%02d", i)));
        }
        var errors = RequirementsSpecValidator.validate(spec(ROLES, List.of(
                        new UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(), many),
                        hu("HU-02", "vendedor", List.of(), esc("ESC-20"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-01")), new Increment(3, "x", List.of()))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("13 escenarios")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-01") && e.contains("exactamente un incremento")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-02") && e.contains("exactamente un incremento")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("1, 2")), errors.toString());
    }

    @Test
    void dependenciesMustExistHaveNoCyclesAndNeverPointForward() {
        var errors = RequirementsSpecValidator.validate(spec(ROLES, List.of(
                        hu("HU-01", "vendedor", List.of("HU-02"), esc("ESC-01")),
                        hu("HU-02", "vendedor", List.of("HU-01", "HU-09"), esc("ESC-02"))),
                List.of(new Increment(1, "MVP", List.of("HU-01")), new Increment(2, "x", List.of("HU-02")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-09") && e.contains("no existe")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-01") && e.contains("posterior")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ciclo")), errors.toString());
    }

    // Revisión final (11): un incremento sin historias o con ids que no existen no se construye.
    @Test
    void incrementsMustListExistingStories() {
        var errors = RequirementsSpecValidator.validate(spec(ROLES,
                List.of(hu("HU-01", "vendedor", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-07")), new Increment(2, "vacío", List.of()))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("HU-07") && e.contains("no existe")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("incremento 2") && e.contains("sin historias")), errors.toString());
    }

    // Revisión final (9): listas ausentes del modelo quedan vacías (la pantalla de Missions no se cae con null).
    @Test
    void missingListsBecomeEmpty() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var spec = json.readValue("{\"summary\":\"x\",\"userStories\":[{\"id\":\"HU-01\",\"scenarios\":[{\"id\":\"ESC-01\"}]}],"
                + "\"increments\":[{\"number\":1}]}", RequirementsSpec.class);
        var out = json.writeValueAsString(spec);
        assertTrue(out.contains("\"assumptions\":[]") && out.contains("\"outOfScope\":[]") && out.contains("\"roles\":[]")
                && out.contains("\"given\":[]") && out.contains("\"acceptanceCriteria\":[]")
                && out.contains("\"userStoryIds\":[]"), out);
    }

    // Verificado en vivo (MISSION-ARIA-CRM-1791092878): nemotron escribió "given": "un vendedor" (texto, no lista) en los
    // 3 intentos; un texto suelto vale como lista de un elemento.
    @Test
    void aSingleTextIsAcceptedAsAOneItemList() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var spec = json.readValue("{\"summary\":\"x\",\"assumptions\":\"a\",\"userStories\":[{\"id\":\"HU-01\","
                + "\"acceptanceCriteria\":\"c\",\"dependsOn\":\"HU-02\",\"scenarios\":[{\"id\":\"ESC-01\",\"given\":\"un vendedor\","
                + "\"when\":\"registra\",\"then\":\"queda\"}]}],\"increments\":[{\"number\":1,\"userStoryIds\":\"HU-01\"}],"
                + "\"outOfScope\":\"reportes\"}", RequirementsSpec.class);
        var scenario = spec.userStories().get(0).scenarios().get(0);
        assertEquals(List.of("un vendedor"), scenario.given());
        assertEquals(List.of("registra"), scenario.when());
        assertEquals(List.of("queda"), scenario.then());
        assertEquals(List.of("c"), spec.userStories().get(0).acceptanceCriteria());
        assertEquals(List.of("HU-02"), spec.userStories().get(0).dependsOn());
        assertEquals(List.of("HU-01"), spec.increments().get(0).userStoryIds());
        assertEquals(List.of("a"), spec.assumptions());
        assertEquals(List.of("reportes"), spec.outOfScope());
    }

    // Contrato (spec 2026-10-16 contrato §2): las HU salen del contrato aprobado; Java valida la cobertura.
    private static final ClientContract PONG_CONTRACT = new ClientContract("Un Pong de 2 niveles.", "GAME",
            List.of(new ClientContract.Item("MOD-01", "SYSTEM", "Controles", "paletas"),
                    new ClientContract.Item("MOD-02", "LEVEL", "Nivel 1", "normal"),
                    new ClientContract.Item("MOD-03", "LEVEL", "Nivel 2", "rápida")),
            List.of(), true, "un nivel por épica");

    private static UserStory hu(String id, String item, Scenario... scenarios) {
        return new UserStory(id, "vendedor", "quiero algo", "para algo", List.of("criterio"), List.of(),
                List.of(scenarios), item);
    }

    private static RequirementsSpec fromContract(List<UserStory> stories, List<Increment> increments) {
        return new RequirementsSpec("Pong", List.of(), List.of(), ROLES, stories, increments, "APP")
                .withContract(PONG_CONTRACT);
    }

    @Test
    void storiesFromTheContractPassAndJavaGroupsThemByItem() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", "MOD-02", esc("ESC-02")),
                        hu("HU-03", "MOD-03", esc("ESC-03"))),
                List.of(new Increment(1, "Nivel 1 jugable", List.of("HU-01", "HU-02")),
                        new Increment(2, "Más velocidad", List.of("HU-03"))));
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
        assertEquals("GAME", spec.experience());          // la experiencia la manda el contrato (ruling 1)
        assertTrue(spec.epics());
        assertEquals(List.of("MOD-01", "MOD-02"), spec.itemsOf(1).stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("MOD-02"), spec.gameLevelsOf(1).stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("MOD-02", "MOD-03"), spec.gameLevels().stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("HU-03"), spec.storiesOfItem("MOD-03").stream().map(UserStory::id).toList());
    }

    // Review Focus 4.
    @Test
    void everyStoryNeedsAnExistingItemAndEveryItemNeedsAStory() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", null, esc("ESC-02")),
                        hu("HU-03", "MOD-09", esc("ESC-03"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-02", "HU-03"))));
        var all = String.join("\n", RequirementsSpecValidator.validate(spec));
        assertTrue(all.contains("HU-02: contractItem debe decir a qué ítem del contrato pertenece"), all);
        assertTrue(all.contains("HU-03: contractItem MOD-09 no está en el contrato"), all);
        assertTrue(all.contains("MOD-02 (Nivel 1) no tiene historias"), all);
        assertTrue(all.contains("MOD-03 (Nivel 2) no tiene historias"), all);
    }

    @Test
    void inAGameALevelIsNeverSplitBetweenIncrements() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", "MOD-02", esc("ESC-02")),
                        hu("HU-03", "MOD-02", esc("ESC-03")), hu("HU-04", "MOD-03", esc("ESC-04")),
                        hu("HU-05", "MOD-01", esc("ESC-05"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-02")),
                        new Increment(2, "Resto", List.of("HU-03", "HU-04", "HU-05"))));
        var all = String.join("\n", RequirementsSpecValidator.validate(spec));
        assertTrue(all.contains("MOD-02 (Nivel 1) está partido en los incrementos [1, 2]"), all);
        // un SYSTEM sí puede crecer en varios incrementos
        assertFalse(all.contains("MOD-01 (Controles) está partido"), all);
    }

    // Review Focus 3: sin contrato (misión vieja) no hay reglas nuevas; withContract(null) limpia lo que invente el modelo.
    @Test
    void withoutAContractNothingChanges() {
        var old = spec(ROLES, List.of(hu("HU-01", "vendedor", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-01"))));
        assertEquals(List.of(), RequirementsSpecValidator.validate(old));
        var invented = new RequirementsSpec("CRM", List.of(), List.of(), ROLES,
                List.of(hu("HU-01", "MOD-07", esc("ESC-01"))), List.of(new Increment(1, "MVP", List.of("HU-01"))),
                "APP", List.of(new ClientContract.Item("MOD-07", "MODULE", "x", "x")), true).withContract(null);
        assertEquals(List.of(), invented.contractItemsOrEmpty());
        assertFalse(invented.epics());
        assertEquals("APP", invented.experience());
        assertEquals(List.of(), RequirementsSpecValidator.validate(invented));
    }
}
