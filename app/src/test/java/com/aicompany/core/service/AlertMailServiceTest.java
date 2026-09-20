package com.aicompany.core.service;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertMailServiceTest {

    private final JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);

    private final AlertMailService alertMailService = new AlertMailService(mailSender, memory);

    @Test
    void sendsAMultipartEmailFromTheSystemAccountToTheConfiguredAlertAddress() throws Exception {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        alertMailService.send("Misión MISSION-1 requiere tu decisión", "Cuerpo del correo.", false);

        verify(mailSender).setUsername("ai-company@gmail.com");
        verify(mailSender).setPassword("app-password-secreta");

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        var message = captor.getValue();
        message.saveChanges();
        assertEquals("dapine@gmail.com", message.getAllRecipients()[0].toString());
        assertEquals("ai-company@gmail.com", message.getFrom()[0].toString());
        assertEquals("Misión MISSION-1 requiere tu decisión", message.getSubject());
        assertTrue(message.getContentType().startsWith("multipart/"));

        var allText = extractAllText((Multipart) message.getContent());
        assertTrue(allText.contains("Cuerpo del correo."));
        assertTrue(allText.contains("FORJAI"));
    }

    private static String extractAllText(Multipart multipart) throws Exception {
        var text = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof Multipart nested) {
                text.append(extractAllText(nested));
            } else {
                text.append(part.getContent());
            }
        }
        return text.toString();
    }

    @Test
    void doesNothingWhenTheSystemAccountIsNotConfiguredYet() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        alertMailService.send("asunto", "cuerpo", false);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void neverThrowsWhenTheMailSenderFails() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());
        doThrow(new MailSendFailure()).when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> alertMailService.send("asunto", "cuerpo", false));
    }

    private static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private static class MailSendFailure extends MailException {
        MailSendFailure() {
            super("smtp no configurado");
        }
    }

    @Test
    void sendToExternalSendsToTheGivenRecipientNotTheAlertEmail() throws Exception {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        var result = alertMailService.sendToExternal(
                "ventas@panaderiaelsol.com", "Oportunidad de colaboración", "Cuerpo real."
        );

        assertTrue(result.accepted());

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        var message = captor.getValue();
        message.saveChanges();
        assertEquals("ventas@panaderiaelsol.com", message.getAllRecipients()[0].toString());
        assertEquals("ai-company@gmail.com", message.getFrom()[0].toString());

        var allText = extractAllText((Multipart) message.getContent());
        assertFalse(allText.contains("Alerta automática"));
    }

    @Test
    void sendToExternalReturnsNotAcceptedWhenTheSystemAccountIsNotConfigured() {
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        var result = alertMailService.sendToExternal("ventas@panaderiaelsol.com", "asunto", "cuerpo");

        assertFalse(result.accepted());
        assertNotNull(result.errorMessage());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendToExternalNeverThrowsWhenTheMailSenderFails() {
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());
        doThrow(new MailSendFailure()).when(mailSender).send(any(MimeMessage.class));

        var result = assertDoesNotThrow(() ->
                alertMailService.sendToExternal("ventas@panaderiaelsol.com", "asunto", "cuerpo"));

        assertFalse(result.accepted());
        assertNotNull(result.errorMessage());
    }
}
