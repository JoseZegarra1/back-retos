package com.retos.service;

import com.retos.domain.AppUser;
import com.retos.domain.Role;
import com.retos.repo.UserRepository;
import com.retos.web.ApiException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** "Notification: enviar correo de resumen a GHD". */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final UserRepository users;
    private final String from;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final List<String> extraRecipients;
    private final boolean enabled;

    public NotificationService(ObjectProvider<JavaMailSender> mailSender,
                               UserRepository users,
                               @Value("${app.notification.from:no-reply@retos.local}") String from,
                               @Value("${app.notification.extra-recipients:}") List<String> extraRecipients,
                               @Value("${spring.mail.host:}") String smtpHost,
                               @Value("${spring.mail.port:587}") int smtpPort,
                               @Value("${spring.mail.username:}") String smtpUser,
                               @Value("${app.notification.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.mailSender = mailSender;
        this.users = users;
        this.from = from;
        this.extraRecipients = extraRecipients;
    }

    /**
     * Un solo correo por evento, con todos los GHD activos con correo (mas GHD_EXTRA_EMAILS) como destinatarios.
     * Asincrono: un SMTP lento, caido o deshabilitado nunca bloquea ni hace fallar la entrega o el vencimiento.
     */
    @Async
    public void notifyGhd(String subject, String body) {
        List<String> to = new ArrayList<>();
        for (AppUser u : users.findByRoleAndActiveTrue(Role.GHD)) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                to.add(u.getEmail().trim());
            }
        }
        if (to.isEmpty()) {
            log.warn("No hay usuarios GHD activos con correo registrado para el aviso: {}", subject);
        }
        extraRecipients.stream().map(String::trim).filter(s -> !s.isBlank()).forEach(to::add);

        if (!enabled) {
            log.info("Envio de correos deshabilitado (MAIL_ENABLED=false). No se envia a {}: {}\n{}",
                    to.stream().distinct().toList(), subject, body);
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || smtpHost.isBlank() || to.isEmpty()) {
            log.warn("Correo NO enviado (SMTP sin configurar o sin destinatarios). Asunto: {}\n{}", subject, body);
            return;
        }
        try {
            List<String> recipients = to.stream().distinct().toList();
            sender.send(message(recipients.toArray(String[]::new), subject, body));
            log.info("Correo '{}' enviado a {}", subject, recipients);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de resumen: {}", readable(e), e);
        }
    }

    /** Sincrono, para POST /api/notifications/test: lanza ApiException con el error SMTP legible. */
    public void sendTest(String to) {
        if (!enabled) {
            throw ApiException.badRequest("El envio de correos esta deshabilitado (MAIL_ENABLED=false)");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || smtpHost.isBlank()) {
            throw ApiException.badRequest("SMTP sin configurar: define SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD y MAIL_FROM");
        }
        if (smtpUser.isBlank()) {
            throw ApiException.badRequest("Falta SMTP_USER (y SMTP_PASSWORD): el servidor " + smtpHost + " requiere autenticacion");
        }
        try {
            sender.send(message(new String[] {to}, "[Retos tecnicos] Correo de prueba",
                    "Este es un correo de prueba de la Plataforma de Retos Tecnicos.\n\n"
                            + "Si lo recibiste, la configuracion SMTP (" + smtpHost + ":" + smtpPort + ") funciona y "
                            + "GHD recibira los resumenes de entrega y vencimiento.\n"));
            log.info("Correo de prueba enviado a {}", to);
        } catch (MailException e) {
            log.warn("Fallo el correo de prueba a {}: {}", to, readable(e));
            throw new ApiException(HttpStatus.BAD_GATEWAY, readable(e));
        }
    }

    private SimpleMailMessage message(String[] to, String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        return msg;
    }

    private String readable(Exception e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage().trim();
        if (e instanceof MailAuthenticationException) {
            return "El servidor SMTP rechazo las credenciales (revisa SMTP_USER y SMTP_PASSWORD): " + detail;
        }
        if (root instanceof SocketTimeoutException || root instanceof ConnectException
                || root instanceof UnknownHostException) {
            return "No se pudo conectar a " + smtpHost + ":" + smtpPort + " (" + detail + ")";
        }
        return "Error SMTP: " + detail;
    }
}
