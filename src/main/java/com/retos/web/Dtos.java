package com.retos.web;

import com.retos.domain.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    // ---------- reglas de validacion (las mismas que usa el frontend en core/validators.ts) ----------

    /** Letras (con tildes, n, u), separadas por un solo espacio, apostrofo o guion. Sin espacios al inicio/fin. */
    public static final String FULL_NAME_REGEX = "^[\\p{L}\\p{M}]+(?:[ '\\-][\\p{L}\\p{M}]+)*$";
    public static final String USERNAME_REGEX = "^[A-Za-z0-9._-]+$";
    /** Parte local sin puntos al inicio/fin ni dobles; dominio con etiquetas validas y extension de 2+ letras. */
    public static final String EMAIL_REGEX =
            "^[A-Za-z0-9_%+-]+(?:\\.[A-Za-z0-9_%+-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$";
    /** Minimo 8, una mayuscula, una minuscula, un numero, un caracter especial y sin espacios. */
    public static final String PASSWORD_REGEX =
            "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\p{N})(?=.*[^\\p{L}\\p{N}\\s])\\S{8,100}$";
    public static final String PASSWORD_MESSAGE = "La contrasena debe tener al menos 8 caracteres, una mayuscula, "
            + "una minuscula, un numero y un caracter especial, sin espacios";

    public static final int MIN_DURATION_MINUTES = 15;
    public static final int MAX_DURATION_MINUTES = 480;

    public record LoginRequest(
            @NotBlank(message = "El usuario es obligatorio") @Size(max = 60, message = "El usuario no puede exceder 60 caracteres") String username,
            @NotBlank(message = "La contrasena es obligatoria") @Size(max = 100, message = "La contrasena no puede exceder 100 caracteres") String password) {}

    public record LoginResponse(String token, UserDto user) {}

    public record UserDto(Long id, String username, String fullName, String email, Role role,
                          boolean active, boolean mustChangePassword) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getRole(),
                    u.isActive(), u.isMustChangePassword());
        }
    }

    public record CreateUserRequest(
            @NotBlank(message = "El usuario es obligatorio")
            @Size(min = 3, max = 60, message = "El usuario debe tener entre 3 y 60 caracteres")
            @Pattern(regexp = USERNAME_REGEX, message = "El usuario solo admite letras, numeros, punto, guion y guion bajo")
            String username,
            @NotBlank(message = "El nombre completo es obligatorio")
            @Size(min = 3, max = 100, message = "El nombre completo debe tener entre 3 y 100 caracteres")
            @Pattern(regexp = FULL_NAME_REGEX, message = "El nombre completo solo admite letras, espacios simples, "
                    + "apostrofo y guion, sin espacios al inicio o al final")
            String fullName,
            @Size(max = 150, message = "El correo no puede exceder 150 caracteres")
            @Pattern(regexp = "^$|" + EMAIL_REGEX, message = "El correo no tiene un formato valido")
            String email,
            @Pattern(regexp = "^$|" + PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password,
            Role role) {}

    public record CreateUserResponse(UserDto user, String temporaryPassword) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "La contrasena actual es obligatoria") String currentPassword,
            @NotBlank(message = "La nueva contrasena es obligatoria")
            @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {}

    /**
     * Asignacion. Admite el formato anterior ({@code level}) y el nuevo ({@code levels}); se pueden enviar ambos.
     * {@code durationMinutes} es opcional: si no se envia se usa {@code app.challenge.duration-minutes}.
     */
    public record AssignRequest(
            @NotNull(message = "Selecciona un candidato") Long candidateId,
            @NotNull(message = "Selecciona el modulo del reto") Track track,
            Level level,
            List<@NotNull(message = "Nivel invalido") Level> levels,
            @Min(value = MIN_DURATION_MINUTES, message = "La duracion minima es " + MIN_DURATION_MINUTES + " minutos")
            @Max(value = MAX_DURATION_MINUTES, message = "La duracion maxima es " + MAX_DURATION_MINUTES + " minutos")
            Integer durationMinutes) {}

    public record DurationRequest(
            @NotNull(message = "Indica la duracion en minutos")
            @Min(value = MIN_DURATION_MINUTES, message = "La duracion minima es " + MIN_DURATION_MINUTES + " minutos")
            @Max(value = MAX_DURATION_MINUTES, message = "La duracion maxima es " + MAX_DURATION_MINUTES + " minutos")
            Integer durationMinutes) {}

    public record GradeRequest(
            @NotNull(message = "Selecciona APTO o NO APTO") GradeResult result,
            @Size(max = 2000, message = "El comentario no puede exceder 2000 caracteres") String comment) {}

    public record TestMailRequest(
            @NotBlank(message = "Indica el correo de destino")
            @Size(max = 150, message = "El correo no puede exceder 150 caracteres")
            @Pattern(regexp = EMAIL_REGEX, message = "El correo no tiene un formato valido") String to) {}

    /** Parametros que el frontend necesita para validar antes de enviar. */
    public record SettingsDto(long maxUploadBytes, int defaultDurationMinutes,
                              int minDurationMinutes, int maxDurationMinutes) {}

    public record ChallengeDto(Long id, Long candidateId, String candidateName, String candidateUsername,
                               Track track, Level level, ChallengeStatus status, int durationMinutes,
                               Instant assignedAt, Instant startedAt, Instant deadlineAt, Instant submittedAt,
                               long remainingSeconds, boolean hasSubmission,
                               GradeResult result, String gradeComment, String gradedByName, Instant gradedAt) {
        /** @param defaultMinutes duracion global, para retos antiguos sin duracion propia */
        public static ChallengeDto of(Challenge c, int defaultMinutes) {
            long remaining = 0;
            if (c.getStatus() == ChallengeStatus.IN_PROGRESS && c.getDeadlineAt() != null) {
                remaining = Math.max(0, Duration.between(Instant.now(), c.getDeadlineAt()).getSeconds());
            }
            return new ChallengeDto(
                    c.getId(), c.getCandidate().getId(), c.getCandidate().getFullName(), c.getCandidate().getUsername(),
                    c.getTrack(), c.getLevel(), c.getStatus(), c.durationOr(defaultMinutes),
                    c.getAssignedAt(), c.getStartedAt(), c.getDeadlineAt(), c.getSubmittedAt(),
                    remaining, c.getSubmissionPath() != null,
                    c.getResult(), c.getGradeComment(),
                    c.getGradedBy() == null ? null : c.getGradedBy().getFullName(), c.getGradedAt());
        }
    }
}
