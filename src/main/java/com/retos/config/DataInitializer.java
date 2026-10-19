package com.retos.config;

import com.retos.domain.AppUser;
import com.retos.domain.Role;
import com.retos.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea el administrador y un usuario GHD iniciales si no existen. Cambie las claves en produccion. */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String ghdPassword;
    private final String ghdEmail;

    public DataInitializer(UserRepository users, PasswordEncoder encoder,
                           @Value("${app.seed.admin-password}") String adminPassword,
                           @Value("${app.seed.ghd-password}") String ghdPassword,
                           @Value("${app.seed.ghd-email:}") String ghdEmail) {
        this.users = users;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.ghdPassword = ghdPassword;
        this.ghdEmail = ghdEmail;
    }

    @Override
    public void run(String... args) {
        seed("admin", "Administrador", null, adminPassword, Role.ADMIN);
        seed("ghd", "Gestion Humana", ghdEmail.isBlank() ? null : ghdEmail, ghdPassword, Role.GHD);
    }

    private void seed(String username, String fullName, String email, String password, Role role) {
        if (users.existsByUsername(username)) {
            return;
        }
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setFullName(fullName);
        u.setEmail(email);
        u.setRole(role);
        u.setPasswordHash(encoder.encode(password));
        users.save(u);
        log.info("Usuario inicial creado: {} ({})", username, role);
    }
}
