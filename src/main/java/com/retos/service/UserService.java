package com.retos.service;

import com.retos.domain.AppUser;
import com.retos.domain.Role;
import com.retos.repo.UserRepository;
import com.retos.web.ApiException;
import com.retos.web.Dtos.*;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final String PWD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final EmailDomainChecker domainChecker;

    public UserService(UserRepository users, PasswordEncoder encoder, EmailDomainChecker domainChecker) {
        this.users = users;
        this.encoder = encoder;
        this.domainChecker = domainChecker;
    }

    public AppUser byUsername(String username) {
        return users.findByUsername(username).orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
    }

    public List<UserDto> list() {
        return users.findAllByOrderByCreatedAtDesc().stream().map(UserDto::of).toList();
    }

    /**
     * GHD crea usuarios con rol USER. Solo ADMIN puede crear usuarios ADMIN o GHD.
     */
    @Transactional
    public CreateUserResponse create(CreateUserRequest req, AppUser creator) {
        Role role = req.role() == null ? Role.USER : req.role();
        if (role != Role.USER && creator.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Solo un administrador puede crear usuarios con rol " + role);
        }
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("El usuario '" + req.username() + "' ya existe");
        }
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim().toLowerCase(Locale.ROOT);
        if (email == null && role != Role.USER) {
            throw ApiException.badRequest("El correo es obligatorio para usuarios con rol " + role);
        }
        if (email != null && !domainChecker.canReceiveMail(email)) {
            throw ApiException.badRequest("El dominio del correo '" + email + "' no existe o no recibe correo");
        }
        boolean generated = req.password() == null || req.password().isBlank();
        String rawPassword = generated ? randomPassword(10) : req.password();

        AppUser u = new AppUser();
        u.setUsername(req.username());
        u.setFullName(req.fullName());
        u.setEmail(email);
        u.setRole(role);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setMustChangePassword(true);
        users.save(u);
        return new CreateUserResponse(UserDto.of(u), generated ? rawPassword : null);
    }

    @Transactional
    public UserDto setActive(Long id, boolean active) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        u.setActive(active);
        return UserDto.of(u);
    }

    @Transactional
    public void changePassword(AppUser user, ChangePasswordRequest req) {
        if (!encoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("La contrasena actual no es correcta");
        }
        if (encoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("La nueva contrasena debe ser distinta de la actual");
        }
        user.setPasswordHash(encoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        users.save(user);
    }

    private String randomPassword(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(PWD_CHARS.charAt(random.nextInt(PWD_CHARS.length())));
        }
        return sb.toString();
    }
}
