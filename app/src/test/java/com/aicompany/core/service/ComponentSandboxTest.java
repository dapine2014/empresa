package com.aicompany.core.service;

import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.SandboxResult.StepResult;
import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComponentSandboxTest {

    private static final List<AppComponent> FULLSTACK =
            AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));

    private static SandboxResult ok(int tests) {
        return new SandboxResult("PASS", List.of(new StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new StepResult("test", "PASS", 0, 1000, "", tests, 0)));
    }

    @Test
    void oneComponentIsReturnedUnchanged() {
        var root = List.of(new AppComponent(StackProfile.DOTNET_APP, ""));
        assertEquals(Optional.of(ok(3)), ComponentSandbox.combine(root, List.of(Optional.of(ok(3)))));
    }

    @Test
    void stepsArePrefixedAndPassOnlyWithTestsInEveryComponent() {
        var combined = ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.of(ok(2)))).orElseThrow();
        assertEquals("PASS", combined.overall());
        assertEquals(List.of("backend/build", "backend/test", "app/build", "app/test"),
                combined.stepsOrEmpty().stream().map(StepResult::name).toList());
        assertEquals(6, combined.testsPassed());

        var noAppTests = ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.of(ok(0)))).orElseThrow();
        assertEquals("FAIL", noAppTests.overall());
        assertFalse(noAppTests.passed());
    }

    // Review Focus 5: el runner cae en el segundo componente → sin resultado (UNVALIDATED), nunca PASS.
    @Test
    void aMissingComponentResultMeansNoResult() {
        assertEquals(Optional.empty(), ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.empty())));
    }

    // Review Focus 4: errores de la app vuelven con la ruta real.
    @Test
    void compileErrorsGetTheComponentPrefix() {
        var result = new SandboxResult("FAIL", List.of(
                new StepResult("backend/build", "PASS", 0, 1, "", 0, 0),
                new StepResult("app/build", "FAIL", 1, 1,
                        "lib/clientes/presentation/x.dart:3:5: Error: Undefined name 'ApiRoutes'.", 0, 0)));
        var errors = ComponentSandbox.compileErrors(result);
        assertEquals(1, errors.size());
        assertEquals("app/lib/clientes/presentation/x.dart", errors.get(0).path());
        assertEquals("build", ComponentSandbox.stepName("app/build"));
        assertEquals("app", ComponentSandbox.dirOf("app/build"));
        assertEquals("", ComponentSandbox.dirOf("build"));
    }
}
