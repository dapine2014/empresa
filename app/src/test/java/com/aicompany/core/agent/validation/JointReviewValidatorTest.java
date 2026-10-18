package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Revisión conjunta (spec 2026-10-15): Java valida lo que Neo detecta y lo que Aria decide. */
public class JointReviewValidatorTest {

    // Públicos: los reusan JointReviewWriterTest y JointReviewServiceTest (otro paquete).
    public static final RequirementsSpec SPEC = new RequirementsSpec("Pong", List.of("800x600"), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "GAME");

    public static final InteractionDesign GAME = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING"),
            List.of(), List.of(new Entity("pelota", "Pelota", List.of(new Property("x", 400.0)))),
            List.of(new Control("move_up", "Up", "sube")), List.of(new Rule("REGLA-01", "move_up", "sube")),
            List.of(), List.of("REGLA-01"), List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-01")), List.of()));

    public static final InteractionDesign APP = new InteractionDesign("APP", new AppDesign(
            List.of(new Screen("PANT-01", "Lista", "ver", "jugador", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())), List.of(new ScenarioScreen("ESC-01", "PANT-01")),
            List.of()), null);

    static Contradiction contra(String id, List<String> req, List<String> design) {
        return new Contradiction(id, req, design, "las HU dicen 800", "el diseño usa 640",
                new Proposal(JointReview.DESIGN, "usar 800"));
    }

    @Test void aValidDetectionHasNoErrors() {
        var d = new Detection(List.of(contra("CONTRA-01", List.of("HU-01", "ESC-01"), List.of("REGLA-01", "pelota"))));
        assertEquals(List.of(), JointReviewValidator.validateDetection(d, SPEC, GAME));
    }

    @Test void anEmptyDetectionIsCoherent() {
        assertEquals(List.of(), JointReviewValidator.validateDetection(new Detection(List.of()), SPEC, GAME));
        assertEquals(List.of(), JointReviewValidator.validateDetection(new Detection(null), SPEC, GAME));
    }

    @Test void referencesMustExistOnEachSide() {
        var d = new Detection(List.of(contra("CONTRA-01", List.of("HU-09"), List.of("REGLA-07"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("HU-09"), errors);
        assertTrue(errors.contains("REGLA-07"), errors);
    }

    @Test void inAnAppTheDesignRefsAreScreens() {
        var ok = new Detection(List.of(contra("CONTRA-01", List.of("HU-01"), List.of("PANT-01"))));
        assertEquals(List.of(), JointReviewValidator.validateDetection(ok, SPEC, APP));
        var bad = new Detection(List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"))));
        assertFalse(JointReviewValidator.validateDetection(bad, SPEC, APP).isEmpty());
    }

    @Test void eachSideNeedsAtLeastOneReferenceAndTheTextsAreRequired() {
        var d = new Detection(List.of(new Contradiction("CONTRA-01", List.of(), List.of(), " ", "", new Proposal("OTRO", ""))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("requirementRefs"), errors);
        assertTrue(errors.contains("designRefs"), errors);
        assertTrue(errors.contains("requirementSays"), errors);
        assertTrue(errors.contains("side"), errors);
        assertTrue(errors.contains("change"), errors);
    }

    @Test void idsAreSequentialAndUnique() {
        var d = new Detection(List.of(contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01")),
                contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("CONTRA-01"), errors);
    }

    @Test void atMostTenContradictions() {
        var list = new ArrayList<Contradiction>();
        for (int i = 1; i <= 11; i++) {
            list.add(contra("CONTRA-%02d".formatted(i), List.of("HU-01"), List.of("REGLA-01")));
        }
        var errors = String.join("\n", JointReviewValidator.validateDetection(new Detection(list), SPEC, GAME));
        assertTrue(errors.contains("10"), errors);
    }

    @Test void ariaDecidesEachContradictionExactlyOnce() {
        var contradictions = List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01")),
                contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01")));
        var ok = new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"),
                new Decision("CONTRA-02", "REQUIREMENTS", "aceptar 640", "es mejor")));
        assertEquals(List.of(), JointReviewValidator.validateResolution(ok, contradictions));

        var missingAndExtra = new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "x", "y"),
                new Decision("CONTRA-01", "DESIGN", "x", "y"), new Decision("CONTRA-09", "DESIGN", "x", "y")));
        var errors = String.join("\n", JointReviewValidator.validateResolution(missingAndExtra, contradictions));
        assertTrue(errors.contains("CONTRA-02"), errors);
        assertTrue(errors.contains("CONTRA-09"), errors);
        assertTrue(errors.contains("dos veces"), errors);
    }

    @Test void aDecisionNeedsAValidSideAChangeAndAReason() {
        var contradictions = List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01")));
        var bad = new Resolution(List.of(new Decision("CONTRA-01", "NINGUNO", " ", null)));
        var errors = String.join("\n", JointReviewValidator.validateResolution(bad, contradictions));
        assertTrue(errors.contains("side"), errors);
        assertTrue(errors.contains("change"), errors);
        assertTrue(errors.contains("reason"), errors);
    }

    @Test void nullElementInRequirementRefsProducesErrorNotException() {
        var reqRefs = new ArrayList<String>();
        reqRefs.add("HU-01");
        reqRefs.add(null);
        var d = new Detection(List.of(new Contradiction("CONTRA-01", reqRefs, List.of("REGLA-01"),
                "las HU dicen 800", "el diseño usa 640", new Proposal(JointReview.DESIGN, "usar 800"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("requirementRefs tiene un elemento vacío"), errors);
    }

    @Test void nullElementInDesignRefsProducesErrorNotException() {
        var designRefs = new ArrayList<String>();
        designRefs.add("REGLA-01");
        designRefs.add(null);
        var d = new Detection(List.of(new Contradiction("CONTRA-01", List.of("HU-01"), designRefs,
                "las HU dicen 800", "el diseño usa 640", new Proposal(JointReview.DESIGN, "usar 800"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("designRefs tiene un elemento vacío"), errors);
    }

    // Juegos por niveles: MOD-NN es referencia de requisitos; ESCENA-NN y MOD-NN del diseño, de diseño.
    @Test void levelsAndScenesAreValidReferences() {
        var design = new InteractionDesign("GAME", null, GameScenesTest.GAME);
        assertTrue(JointReviewValidator.requirementIds(GameScenesTest.SPEC).contains("MOD-02"));
        assertTrue(JointReviewValidator.designIds(design).containsAll(List.of("ESCENA-01", "MOD-01")));
    }
}
