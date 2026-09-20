package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Alertas inmediatas por correo (`empresa.md` §18: bloqueo, fallo crítico,
 * decisión estratégica, riesgo importante, límite de presupuesto, incidente
 * grave). Hoy solo se dispara para los dos casos con señal clara y ya
 * manejada explícitamente por {@code MissionExecutor}: una misión que llega
 * a {@code AWAITING_INVESTOR} (decisión estratégica) y una misión que
 * termina en {@code FAILED} (fallo crítico) — {@code critical} distingue
 * ambos para el color de la plantilla ({@link AlertEmailTemplate}).
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

    /**
     * Sin {@code providerMessageId}: {@code JavaMailSenderImpl.send(...)}
     * es {@code void} -- SMTP genérico (Gmail vía {@code spring.mail.*})
     * no da ese dato de forma confiable, a diferencia de un proveedor de
     * email tipo API (SendGrid/Mailgun). No se inventa un campo que no
     * existe.
     */
    public record ExternalMailResult(boolean accepted, String errorMessage) {
    }

    /**
     * Envío real a un destinatario arbitrario (nunca {@code alertEmail()}
     * del fundador, a diferencia de {@link #send}) -- usado para
     * contactar prospectos reales. A diferencia de {@code send}, que
     * nunca lanza porque una alerta interna fallida no debe tumbar
     * nada, este método SÍ devuelve el resultado real: el chat le tiene
     * que decir la verdad al fundador sobre si el correo a un
     * prospecto real salió o no.
     */
    public synchronized ExternalMailResult sendToExternal(String to, String subject, String body) {
        try {
            var systemEmail = memory.systemEmail();
            var password = memory.mailPassword();

            if (systemEmail == null || systemEmail.isBlank()
                    || password == null || password.isBlank()) {

                return new ExternalMailResult(
                        false,
                        "El correo propio del sistema no está configurado todavía "
                                + "(Settings del Command Center web)."
                );
            }

            mailSender.setUsername(systemEmail);
            mailSender.setPassword(password);

            var mimeMessage = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(systemEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, AlertEmailTemplate.html(subject, body, false));

            mailSender.send(mimeMessage);

            return new ExternalMailResult(true, null);

        } catch (Exception ex) {

            log.warn("No se pudo contactar al prospecto {}: {}", to, ex.getMessage());

            return new ExternalMailResult(
                    false,
                    ex.getMessage() == null ? "error desconocido" : ex.getMessage()
            );
        }
    }

    public synchronized void send(String subject, String body, boolean critical) {
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

            var mimeMessage = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(systemEmail);
            helper.setTo(memory.alertEmail());
            helper.setSubject(subject);
            helper.setText(body, AlertEmailTemplate.html(subject, body, critical));

            mailSender.send(mimeMessage);

        } catch (Exception ex) {
            log.warn("No se pudo enviar la alerta por correo: {}", ex.getMessage());
        }
    }
}
