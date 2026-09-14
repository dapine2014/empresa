package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertMailServiceTest {

    private final JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);

    private final AlertMailService alertMailService = new AlertMailService(mailSender, memory);

    @Test
    void sendsAnEmailFromTheSystemAccountToTheConfiguredAlertAddress() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");

        alertMailService.send("Misión MISSION-1 requiere tu decisión", "Cuerpo del correo.");

        verify(mailSender).setUsername("ai-company@gmail.com");
        verify(mailSender).setPassword("app-password-secreta");

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        var message = captor.getValue();
        assertArrayEquals(new String[]{"dapine@gmail.com"}, message.getTo());
        assertEquals("ai-company@gmail.com", message.getFrom());
        assertEquals("Misión MISSION-1 requiere tu decisión", message.getSubject());
        assertEquals("Cuerpo del correo.", message.getText());
    }

    @Test
    void doesNothingWhenTheSystemAccountIsNotConfiguredYet() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        alertMailService.send("asunto", "cuerpo");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void neverThrowsWhenTheMailSenderFails() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        doThrow(new MailSendFailure()).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> alertMailService.send("asunto", "cuerpo"));
    }

    private static class MailSendFailure extends MailException {
        MailSendFailure() {
            super("smtp no configurado");
        }
    }
}
