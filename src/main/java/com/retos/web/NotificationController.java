package com.retos.web;

import com.retos.service.NotificationService;
import com.retos.web.Dtos.TestMailRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAnyRole('GHD','ADMIN')")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Envia un correo de prueba de forma sincronica para verificar la configuracion SMTP. */
    @PostMapping("/test")
    public Map<String, String> test(@Valid @RequestBody TestMailRequest req) {
        String to = req.to().trim().toLowerCase(Locale.ROOT);
        notifications.sendTest(to);
        return Map.of("message", "Correo de prueba enviado a " + to);
    }
}
