package com.retos.web;

import com.retos.service.UserService;
import com.retos.web.Dtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** GHD (y ADMIN) crean y administran usuarios. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAnyRole('GHD','ADMIN')")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserDto> list() {
        return users.list();
    }

    @PostMapping
    public ResponseEntity<CreateUserResponse> create(@Valid @RequestBody CreateUserRequest req, Authentication auth) {
        CreateUserResponse res = users.create(req, users.byUsername(auth.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PatchMapping("/{id}/active")
    public UserDto setActive(@PathVariable Long id, @RequestParam boolean value) {
        return users.setActive(id, value);
    }
}
