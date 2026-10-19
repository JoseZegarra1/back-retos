package com.retos.service;

import com.retos.domain.AppUser;
import com.retos.domain.Role;
import com.retos.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final List<String> extraRecipients;

    public NotificationService(ObjectProvider<JavaMailSender> mailSender,
                               UserRepository users,
                               @Value("${app.notification.from:no-reply@retos.local}") String from,
                               @Value("${app.notification.extra-recipients:}") List<String> extraRecipients,
                               @Value("${spring.mail.host:}") String smtpHost) {
        this.smtpHost = smtpHost;
        this.mailSender = mailSender;
        this.users = users;
        this.from = from;
        this.extraRecipients = extraRecipients;
    }

    @Async
    public void notifyGhd(String subject, String body) {
        List<String> to = new ArrayList<>();
        for (AppUser u : users.findByRoleAndActiveTrue(Role.GHD)) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                to.add(u.getEmail());
            }
        }
        extraRecipients.stream().filter(s -> !s.isBlank()).forEach(to::add);

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || smtpHost.isBlank() || to.isEmpty()) {
            log.warn("Correo NO enviado (SMTP sin configurar o sin destinatarios GHD). Asunto: {}\n{}", subject, body);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to.stream().distinct().toArray(String[]::new));
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.info("Correo de resumen enviado a {}", to);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo de resumen", e);
        }
    }
}
