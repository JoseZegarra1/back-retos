package com.retos.web;

import com.retos.domain.AppUser;
import com.retos.security.JwtService;
import com.retos.service.UserService;
import com.retos.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwt;
    private final UserService users;

    public AuthController(AuthenticationManager authManager, JwtService jwt, UserService users) {
        this.authManager = authManager;
        this.jwt = jwt;
        this.users = users;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        AppUser u = users.byUsername(req.username());
        return new LoginResponse(jwt.generate(u.getUsername(), u.getRole().name()), UserDto.of(u));
    }

    @GetMapping("/me")
    public UserDto me(Authentication auth) {
        return UserDto.of(users.byUsername(auth.getName()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth) {
        users.changePassword(users.byUsername(auth.getName()), req);
        return ResponseEntity.noContent().build();
    }
}
