package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Alertas inmediatas por correo (`empresa.md` §18: bloqueo, fallo crítico,
 * decisión estratégica, riesgo importante, límite de presupuesto, incidente
 * grave). Hoy solo se dispara para los dos casos con señal clara y ya
 * manejada explícitamente por {@code MissionExecutor}: una misión que llega
 * a {@code AWAITING_INVESTOR} (decisión estratégica) y una misión que
 * termina en {@code FAILED} (fallo crítico).
 *
 * <p>{@link #send} nunca lanza: un problema de correo (SMTP sin configurar,
 * credenciales inválidas, red caída) no debe tumbar el flujo de misiones,
 * que es lo que de verdad importa. Solo se loguea como {@code WARN}.
 */
@Service
public class AlertMailService {

    private static final Logger log = LoggerFactory.getLogger(AlertMailService.class);

    private final JavaMailSender mailSender;
    private final CompanyMemoryService memory;
    private final String from;

    public AlertMailService(
            JavaMailSender mailSender,
            CompanyMemoryService memory,
            @Value("${spring.mail.username:}") String from) {

        this.mailSender = mailSender;
        this.memory = memory;
        this.from = from;
    }

    public void send(String subject, String body) {
        try {
            var message = new SimpleMailMessage();
            message.setTo(memory.alertEmail());
            if (from != null && !from.isBlank()) {
                message.setFrom(from);
            }
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

        } catch (Exception ex) {
            log.warn("No se pudo enviar la alerta por correo: {}", ex.getMessage());
        }
    }
}
