package com.aicompany.core.controller;

import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductStatusCommand;
import com.aicompany.core.service.ProductService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** El Command Center actúa como el fundador ("human"). */
class ProductControllerTest {

    private final ProductService products = mock(ProductService.class);
    private final ProductController controller = new ProductController(products);

    @Test
    void theCommandCenterActsAsTheFounder() {
        when(products.list()).thenReturn(List.of());

        assertEquals(List.of(), controller.list());
        controller.changeStatus("P1", new ProductStatusCommand("PAUSED", "Sin capacidad"));
        controller.changeStatus("P2", new ProductStatusCommand("", "Reanudar"));

        verify(products).changeStatus("P1", CatalogStatus.PAUSED, "Sin capacidad", "human");
        verify(products).changeStatus("P2", null, "Reanudar", "human");
    }
}
