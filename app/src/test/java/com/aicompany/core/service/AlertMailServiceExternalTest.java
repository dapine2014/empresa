package com.aicompany.core.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec contacto con prospectos §3: envío real a un prospecto, sin ocultar el resultado. */
class AlertMailServiceExternalTest {

    private final JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final AlertMailService service = new AlertMailService(sender, memory);

    {
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        when(memory.alertEmail()).thenReturn("founder@forjai.com");
    }

    @Test
    void withoutSmtpNothingIsSentAndTheReasonIsReturned() {
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo");

        assertFalse(result.accepted());
        assertTrue(result.errorMessage().contains("Settings"), result.errorMessage());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void itSendsPlainTextToTheProspectWithReplyToTheFounder() throws Exception {
        when(memory.systemEmail()).thenReturn("forjai@gmail.com");
        when(memory.mailPassword()).thenReturn("app-pass");
        var captor = ArgumentCaptor.forClass(MimeMessage.class);

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo del correo");

        assertTrue(result.accepted());
        verify(sender).send(captor.capture());
        var message = captor.getValue();
        assertEquals("hola@acme.com", message.getAllRecipients()[0].toString());
        assertEquals("founder@forjai.com", message.getReplyTo()[0].toString());
        assertEquals("Asunto", message.getSubject());
    }

    @Test
    void anSmtpFailureIsReportedNotThrown() {
        when(memory.systemEmail()).thenReturn("forjai@gmail.com");
        when(memory.mailPassword()).thenReturn("app-pass");
        doThrow(new MailSendException("535 Authentication failed")).when(sender).send(any(MimeMessage.class));

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo");

        assertFalse(result.accepted());
        assertTrue(result.errorMessage().contains("535"), result.errorMessage());
    }
}
