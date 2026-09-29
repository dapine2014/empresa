package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.ApiKeyMemoryService.StoredApiKey;
import com.aicompany.core.service.OpenAiCompatibleClient.KeyCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Keys de modelos editables desde Settings (2026-09-29): cifradas, probadas antes de guardar y aplicadas en caliente. */
class ApiKeyServiceTest {

    private final OpenAiCompatibleClient ceoClient = mock(OpenAiCompatibleClient.class);
    private final OpenAiCompatibleClient engClient = mock(OpenAiCompatibleClient.class);
    private final ApiKeyMemoryService memory = mock(ApiKeyMemoryService.class);
    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final ApiKeyService service = new ApiKeyService(Map.of("nvidia-ceo", ceoClient, "nvidia", engClient),
            memory, cipher, events, companyMemory);

    {
        when(companyMemory.agents()).thenReturn(List.of(
                Map.of("id", "ceo", "name", "Alex", "model", "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b", "fallbackModel", "qwen3-coder:30b"),
                Map.of("id", "engineering", "name", "Neo", "model", "nvidia:moonshotai/kimi-k3", "fallbackModel", "nvidia:z-ai/glm-5.3")));
        when(ceoClient.keyHint()).thenReturn("…a3F9");
        when(engClient.keyHint()).thenReturn("");
    }

    @Test
    void aValidKeyIsStoredEncryptedAppliedAndAudited() {
        when(ceoClient.checkKey("nvidia/nemotron-3-ultra-550b-a55b", "nvapi-nueva")).thenReturn(KeyCheck.OK);

        service.update("nvidia-ceo", " nvapi-nueva ", "human");

        verify(memory).save(eq("nvidia-ceo"), argThat(c -> !c.contains("nvapi-nueva") && "nvapi-nueva".equals(cipher.decrypt(c))),
                eq("human"));
        verify(ceoClient).setApiKey("nvapi-nueva");
        verify(events).publish(eq("EMPRESA_API_KEY_UPDATED"), isNull(), isNull(), eq("human"),
                argThat(m -> "nvidia-ceo".equals(m.get("provider")) && !m.toString().contains("nvapi")));
    }

    @Test
    void aRejectedKeyIsNotStoredAndTheCurrentOneStays() {
        when(ceoClient.checkKey(anyString(), anyString())).thenReturn(KeyCheck.UNAUTHORIZED);

        var ex = assertThrows(IllegalArgumentException.class, () -> service.update("nvidia-ceo", "nvapi-mala", "human"));

        assertTrue(ex.getMessage().contains("rechazó"), ex.getMessage());
        assertFalse(ex.getMessage().contains("nvapi-mala"));
        verify(memory, never()).save(any(), any(), any());
        verify(ceoClient, never()).setApiKey(any());
    }

    @Test
    void ifNoModelAnswersTheKeyIsNotStored() {
        when(engClient.checkKey(anyString(), anyString())).thenReturn(KeyCheck.UNAVAILABLE);

        var ex = assertThrows(IllegalArgumentException.class, () -> service.update("nvidia", "nvapi-x", "human"));

        assertTrue(ex.getMessage().contains("No se pudo comprobar"), ex.getMessage());
        verify(engClient).checkKey("moonshotai/kimi-k3", "nvapi-x");
        verify(engClient).checkKey("z-ai/glm-5.3", "nvapi-x");
        verify(memory, never()).save(any(), any(), any());
    }

    @Test
    void anUnknownProviderOrABlankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.update("openai", "k", "human"));
        assertThrows(IllegalArgumentException.class, () -> service.update("nvidia", "  ", "human"));
    }

    @Test
    void theListShowsOnlyAHintTheSourceAndWhoUsesEachProvider() {
        when(memory.all()).thenReturn(List.of(new StoredApiKey("nvidia-ceo", cipher.encrypt("nvapi-secreta"),
                Instant.parse("2026-09-29T12:00:00Z"), "human")));

        var list = service.list();

        var ceo = list.stream().filter(k -> k.provider().equals("nvidia-ceo")).findFirst().orElseThrow();
        assertEquals("…a3F9", ceo.hint());
        assertEquals("FOUNDER", ceo.source());
        assertEquals(List.of("Alex"), ceo.agents());
        var nvidia = list.stream().filter(k -> k.provider().equals("nvidia")).findFirst().orElseThrow();
        assertEquals("NONE", nvidia.source());
        assertEquals(List.of("Neo"), nvidia.agents());
        assertFalse(list.toString().contains("nvapi-secreta"));
    }

    @Test
    void storedKeysAreAppliedAtStartup() {
        when(memory.all()).thenReturn(List.of(new StoredApiKey("nvidia-ceo", cipher.encrypt("nvapi-guardada"), Instant.now(), "human")));

        service.applyStoredKeys();

        verify(ceoClient).setApiKey("nvapi-guardada");
        verify(engClient, never()).setApiKey(any());
    }

    @Test
    void anUnreadableStoredKeyKeepsTheEnvOneAndDoesNotFailStartup() {
        when(memory.all()).thenReturn(List.of(new StoredApiKey("nvidia-ceo", "v1:basura", Instant.now(), "human")));

        assertDoesNotThrow(service::applyStoredKeys);
        verify(ceoClient, never()).setApiKey(any());
    }

    @Test
    void withoutTheMasterKeyNothingIsSaved() {
        var noKey = new ApiKeyService(Map.of("nvidia", engClient), memory, new SecretCipher(""), events, companyMemory);

        var ex = assertThrows(IllegalStateException.class, () -> noKey.update("nvidia", "nvapi-x", "human"));

        assertTrue(ex.getMessage().contains("FORJAI_SECRETS_KEY"), ex.getMessage());
        verify(engClient, never()).checkKey(any(), any());
    }
}
