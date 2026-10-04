package com.aicompany.core.agent.validation;

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
        return new RequirementsSpec("CRM", List.of("supuesto"), List.of("fuera"), roles, stories, increments);
    }

    private static final List<Role> ROLES = List.of(new Role("vendedor", "registra clientes"),
            new Role("gerente", "mira el pipeline"));

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
}
