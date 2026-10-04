package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.BoundedContext;
import com.aicompany.core.agent.model.TeamPlan.DatabaseNeed;
import com.aicompany.core.agent.model.TeamPlan.GlossaryTerm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-ARIA-CRM-1791093806, incremento 2): Neo declaró solo [Oportunidades] y los chequeos dieron
 * el código de Clientes por fuera de la estructura. En un incremento, el producto existente se conserva.
 */
class IncrementPlanMergerTest {

    private static TeamPlan plan(List<BoundedContext> contexts, List<GlossaryTerm> terms, DatabaseNeed db,
                                 List<String> components) {
        return new TeamPlan("s", "t", "e", List.of(), List.of(), "DOTNET_APP", contexts, terms, db, components);
    }

    @Test
    void contextsComponentsAndTermsOfThePreviousPlanAreKept() {
        var previous = plan(List.of(new BoundedContext("Clientes", "clientes")),
                List.of(new GlossaryTerm("Cliente", "quien compra")), new DatabaseNeed("POSTGRESQL", "app"),
                List.of("DOTNET_APP", "FLUTTER_WEB_APP"));
        var next = plan(List.of(new BoundedContext("Oportunidades", "ventas"), new BoundedContext("clientes", "dup")),
                List.of(new GlossaryTerm("Oportunidad", "posible venta"), new GlossaryTerm("cliente", "dup")), null,
                List.of("DOTNET_APP"));

        var merged = IncrementPlanMerger.merge(next, previous);

        assertEquals(List.of("Clientes", "Oportunidades"), merged.contextNames());
        assertEquals(List.of("DOTNET_APP", "FLUTTER_WEB_APP"), merged.components());
        assertEquals(List.of("Cliente", "Oportunidad"),
                merged.ubiquitousLanguageOrEmpty().stream().map(GlossaryTerm::term).toList());
        assertEquals("app", merged.database().connectionName());
    }

    @Test
    void withoutAPreviousPlanTheNewOneIsUnchanged() {
        var next = plan(List.of(new BoundedContext("Oportunidades", "ventas")), List.of(), null, List.of("DOTNET_APP"));
        assertSame(next, IncrementPlanMerger.merge(next, null));
    }

    @Test
    void theDescriptionForNeoListsWhatAlreadyExists() {
        var previous = plan(List.of(new BoundedContext("Clientes", "clientes")), List.of(), null,
                List.of("DOTNET_APP", "FLUTTER_WEB_APP"));
        var text = IncrementPlanMerger.existingProduct(previous);
        assertTrue(text.contains("Clientes") && text.contains("DOTNET_APP") && text.contains("FLUTTER_WEB_APP"), text);
    }
}
