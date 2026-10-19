package com.retos.web;

import com.retos.domain.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;

public final class Dtos {
    private Dtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, UserDto user) {}

    public record UserDto(Long id, String username, String fullName, String email, Role role,
                          boolean active, boolean mustChangePassword) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getRole(),
                    u.isActive(), u.isMustChangePassword());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "solo letras, numeros, punto, guion y guion bajo") String username,
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 150) String email,
            @Size(min = 8, max = 100) String password,
            Role role) {}

    public record CreateUserResponse(UserDto user, String temporaryPassword) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 100) String newPassword) {}

    public record AssignRequest(@NotNull Long candidateId, @NotNull Track track, @NotNull Level level) {}

    public record GradeRequest(@NotNull GradeResult result, @Size(max = 2000) String comment) {}

    public record ChallengeDto(Long id, Long candidateId, String candidateName, String candidateUsername,
                               Track track, Level level, ChallengeStatus status,
                               Instant assignedAt, Instant startedAt, Instant deadlineAt, Instant submittedAt,
                               long remainingSeconds, boolean hasSubmission,
                               GradeResult result, String gradeComment, String gradedByName, Instant gradedAt) {
        public static ChallengeDto of(Challenge c) {
            long remaining = 0;
            if (c.getStatus() == ChallengeStatus.IN_PROGRESS && c.getDeadlineAt() != null) {
                remaining = Math.max(0, Duration.between(Instant.now(), c.getDeadlineAt()).getSeconds());
            }
            return new ChallengeDto(
                    c.getId(), c.getCandidate().getId(), c.getCandidate().getFullName(), c.getCandidate().getUsername(),
                    c.getTrack(), c.getLevel(), c.getStatus(),
                    c.getAssignedAt(), c.getStartedAt(), c.getDeadlineAt(), c.getSubmittedAt(),
                    remaining, c.getSubmissionPath() != null,
                    c.getResult(), c.getGradeComment(),
                    c.getGradedBy() == null ? null : c.getGradedBy().getFullName(), c.getGradedAt());
        }
    }
}
