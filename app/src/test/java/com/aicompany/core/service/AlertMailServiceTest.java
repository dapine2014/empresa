package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertMailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);

    private final AlertMailService alertMailService =
            new AlertMailService(mailSender, memory, "no-reply@aicompany.dev");

    @Test
    void sendsAnEmailToTheConfiguredAlertAddress() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");

        alertMailService.send("Misión MISSION-1 requiere tu decisión", "Cuerpo del correo.");

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        var message = captor.getValue();
        assertArrayEquals(new String[]{"dapine@gmail.com"}, message.getTo());
        assertEquals("Misión MISSION-1 requiere tu decisión", message.getSubject());
        assertEquals("Cuerpo del correo.", message.getText());
        assertEquals("no-reply@aicompany.dev", message.getFrom());
    }

    @Test
    void neverThrowsWhenTheMailSenderFails() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        doThrow(new MailSendFailure()).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> alertMailService.send("asunto", "cuerpo"));
    }

    private static class MailSendFailure extends MailException {
        MailSendFailure() {
            super("smtp no configurado");
        }
    }
}
