package com.retos.repo;

import com.retos.domain.AppUser;
import com.retos.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<AppUser> findByRoleAndActiveTrue(Role role);
    List<AppUser> findAllByOrderByCreatedAtDesc();
}
