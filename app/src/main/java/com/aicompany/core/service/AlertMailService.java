package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * Alertas inmediatas por correo (`empresa.md` §18: bloqueo, fallo crítico,
 * decisión estratégica, riesgo importante, límite de presupuesto, incidente
 * grave). Hoy solo se dispara para los dos casos con señal clara y ya
 * manejada explícitamente por {@code MissionExecutor}: una misión que llega
 * a {@code AWAITING_INVESTOR} (decisión estratégica) y una misión que
 * termina en {@code FAILED} (fallo crítico).
 *
 * <p>El remitente (correo propio del sistema + su App Password) es
 * configurable desde el Command Center web y vive en Neo4j
 * ({@link CompanyMemoryService#systemEmail}/{@link CompanyMemoryService#mailPassword}),
 * no en variables de entorno — por eso se inyecta {@link JavaMailSenderImpl}
 * concreto (autoconfigurado por Spring Boot con host/puerto de
 * {@code spring.mail.*}) en vez de la interfaz {@code JavaMailSender}: sus
 * credenciales se fijan de nuevo en cada envío, leyendo el valor más
 * reciente de la base en vez de lo que había al arrancar el proceso.
 *
 * <p>{@link #send} nunca lanza: un problema de correo (cuenta del sistema
 * sin configurar, credenciales inválidas, red caída) no debe tumbar el
 * flujo de misiones, que es lo que de verdad importa. Solo se loguea como
 * {@code WARN}.
 */
@Service
public class AlertMailService {

    private static final Logger log = LoggerFactory.getLogger(AlertMailService.class);

    private final JavaMailSenderImpl mailSender;
    private final CompanyMemoryService memory;

    public AlertMailService(
            JavaMailSenderImpl mailSender,
            CompanyMemoryService memory) {

        this.mailSender = mailSender;
        this.memory = memory;
    }

    public synchronized void send(String subject, String body) {
        try {
            var systemEmail = memory.systemEmail();
            var password = memory.mailPassword();

            if (systemEmail == null || systemEmail.isBlank()
                    || password == null || password.isBlank()) {

                log.warn(
                        "No se pudo enviar la alerta por correo: el correo "
                                + "propio del sistema no está configurado "
                                + "todavía (Settings del Command Center web)."
                );
                return;
            }

            mailSender.setUsername(systemEmail);
            mailSender.setPassword(password);

            var message = new SimpleMailMessage();
            message.setFrom(systemEmail);
            message.setTo(memory.alertEmail());
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

        } catch (Exception ex) {
            log.warn("No se pudo enviar la alerta por correo: {}", ex.getMessage());
        }
    }
}
