package com.retos.service;

import com.retos.domain.*;
import com.retos.repo.ChallengeRepository;
import com.retos.repo.UserRepository;
import com.retos.storage.StorageService;
import com.retos.web.ApiException;
import com.retos.web.Dtos.*;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);

    public record DownloadFile(InputStream stream, String filename) {}

    private final ChallengeRepository challenges;
    private final UserRepository users;
    private final StorageService storage;
    private final NotificationService notifications;
    private final Duration duration;
    private final Duration grace;
    private final DateTimeFormatter fmt;

    public ChallengeService(ChallengeRepository challenges, UserRepository users, StorageService storage,
                            NotificationService notifications,
                            @Value("${app.challenge.duration-minutes:180}") long durationMinutes,
                            @Value("${app.challenge.grace-seconds:60}") long graceSeconds,
                            @Value("${app.timezone:America/Lima}") String zone) {
        this.challenges = challenges;
        this.users = users;
        this.storage = storage;
        this.notifications = notifications;
        this.duration = Duration.ofMinutes(durationMinutes);
        this.grace = Duration.ofSeconds(graceSeconds);
        this.fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es"))
                .withZone(ZoneId.of(zone));
    }

    // ---------- rutas en el Storage Account ----------

    public static String templatePath(Track track, Level level) {
        return "plantillas/" + track.name().toLowerCase() + "/" + level.name().toLowerCase() + ".zip";
    }

    /** /usuario/tipo/reto */
    public static String submissionPath(Challenge c) {
        return c.getCandidate().getUsername() + "/" + c.getTrack().name().toLowerCase()
                + "/reto-" + c.getId() + "/entrega.zip";
    }

    // ---------- consultas ----------

    @Transactional(readOnly = true)
    public List<ChallengeDto> list(AppUser actor) {
        List<Challenge> list = actor.getRole() == Role.USER
                ? challenges.findByCandidateOrderByAssignedAtDesc(actor)
                : challenges.findAllByOrderByAssignedAtDesc();
        return list.stream().map(ChallengeDto::of).toList();
    }

    @Transactional(readOnly = true)
    public ChallengeDto get(Long id, AppUser actor) {
        Challenge c = find(id);
        checkCanView(c, actor);
        return ChallengeDto.of(c);
    }

    // ---------- GHD asigna ----------

    @Transactional
    public ChallengeDto assign(AssignRequest req, AppUser actor) {
        AppUser candidate = users.findById(req.candidateId())
                .orElseThrow(() -> ApiException.notFound("Candidato no encontrado"));
        if (candidate.getRole() != Role.USER || !candidate.isActive()) {
            throw ApiException.badRequest("El candidato debe ser un usuario activo con rol USER");
        }
        if (!req.track().supports(req.level())) {
            throw ApiException.badRequest("El reto " + req.track() + " no tiene nivel " + req.level()
                    + ". Niveles validos: " + req.track().levels());
        }
        if (challenges.existsByCandidateAndStatusIn(candidate,
                EnumSet.of(ChallengeStatus.ASSIGNED, ChallengeStatus.IN_PROGRESS))) {
            throw ApiException.conflict("El candidato ya tiene un reto pendiente o en curso");
        }
        if (!storage.exists(templatePath(req.track(), req.level()))) {
            throw ApiException.conflict("Aun no se ha cargado la carpeta de codigo para "
                    + req.track() + " / " + req.level() + ". Pidale a un administrador que la suba.");
        }
        Challenge c = new Challenge();
        c.setCandidate(candidate);
        c.setAssignedBy(actor);
        c.setTrack(req.track());
        c.setLevel(req.level());
        return ChallengeDto.of(challenges.save(c));
    }

    // ---------- candidato: inicio (descarga) ----------

    /** "Inicio Temp": arranca el temporizador de 3 horas. Idempotente si ya inicio. */
    @Transactional
    public ChallengeDto start(Long id, AppUser actor) {
        Challenge c = ownChallenge(id, actor);
        if (c.getStatus() == ChallengeStatus.ASSIGNED) {
            Instant now = Instant.now();
            c.setStartedAt(now);
            c.setDeadlineAt(now.plus(duration));
            c.setStatus(ChallengeStatus.IN_PROGRESS);
        } else if (c.getStatus() != ChallengeStatus.IN_PROGRESS) {
            throw ApiException.conflict("Este reto ya no se puede iniciar (estado " + c.getStatus() + ")");
        }
        return ChallengeDto.of(c);
    }

    /** Descarga la carpeta de codigo; solo con el reto en curso y dentro del tiempo. */
    @Transactional(readOnly = true)
    public DownloadFile downloadTemplate(Long id, AppUser actor) {
        Challenge c = ownChallenge(id, actor);
        if (c.getStatus() != ChallengeStatus.IN_PROGRESS) {
            throw ApiException.conflict("Debes iniciar el reto antes de descargar la carpeta de codigo");
        }
        if (Instant.now().isAfter(c.getDeadlineAt())) {
            throw new ApiException(HttpStatus.GONE, "El tiempo del reto ha terminado");
        }
        String path = templatePath(c.getTrack(), c.getLevel());
        return new DownloadFile(storage.download(path),
                c.getTrack().name().toLowerCase() + "-" + c.getLevel().name().toLowerCase() + ".zip");
    }

    // ---------- candidato: fin (carga) ----------

    /** "Fin Temp": carga de la carpeta de codigo dentro de las 3 horas. */
    @Transactional
    public ChallengeDto submit(Long id, MultipartFile file, AppUser actor) {
        Challenge c = ownChallenge(id, actor);
        if (c.getStatus() != ChallengeStatus.IN_PROGRESS) {
            throw ApiException.conflict("El reto no esta en curso (estado " + c.getStatus() + ")");
        }
        Instant now = Instant.now();
        if (now.isAfter(c.getDeadlineAt().plus(grace))) {
            throw new ApiException(HttpStatus.GONE, "El tiempo del reto ha terminado");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Debes adjuntar la carpeta de codigo en un archivo .zip");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip")) {
            throw ApiException.badRequest("El archivo debe ser un .zip");
        }
        String path = submissionPath(c);
        try (InputStream in = file.getInputStream()) {
            storage.upload(path, in, file.getSize());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la entrega");
        }
        c.setSubmissionPath(path);
        c.setSubmittedAt(now);
        c.setStatus(ChallengeStatus.SUBMITTED);

        notifications.notifyGhd(
                "Reto entregado: " + c.getCandidate().getFullName() + " (" + c.getTrack() + " " + c.getLevel() + ")",
                summary(c));
        return ChallengeDto.of(c);
    }

    // ---------- calificacion ----------

    /** "CL/LT califica: APTO / NO APTO". */
    @Transactional
    public ChallengeDto grade(Long id, GradeRequest req, AppUser actor) {
        Challenge c = find(id);
        if (c.getStatus() != ChallengeStatus.SUBMITTED && c.getStatus() != ChallengeStatus.EXPIRED
                && c.getStatus() != ChallengeStatus.GRADED) {
            throw ApiException.conflict("Solo se pueden calificar retos entregados o vencidos");
        }
        c.setResult(req.result());
        c.setGradeComment(req.comment());
        c.setGradedBy(actor);
        c.setGradedAt(Instant.now());
        c.setStatus(ChallengeStatus.GRADED);
        return ChallengeDto.of(c);
    }

    @Transactional(readOnly = true)
    public DownloadFile downloadSubmission(Long id) {
        Challenge c = find(id);
        if (c.getSubmissionPath() == null) {
            throw ApiException.notFound("Este reto no tiene entrega");
        }
        return new DownloadFile(storage.download(c.getSubmissionPath()),
                c.getCandidate().getUsername() + "-" + c.getTrack().name().toLowerCase() + "-reto-" + c.getId() + ".zip");
    }

    // ---------- plantillas (carpeta de codigo de cada reto) ----------

    public void uploadTemplate(Track track, Level level, MultipartFile file) {
        if (!track.supports(level)) {
            throw ApiException.badRequest("El reto " + track + " no tiene nivel " + level);
        }
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw ApiException.badRequest("Adjunta un archivo .zip");
        }
        try (InputStream in = file.getInputStream()) {
            storage.upload(templatePath(track, level), in, file.getSize());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la plantilla");
        }
    }

    // ---------- vencimiento (lo llama el scheduler) ----------

    @Transactional
    public int expireOverdue() {
        Instant cutoff = Instant.now().minus(grace);
        List<Challenge> overdue = challenges.findByStatusAndDeadlineAtBefore(ChallengeStatus.IN_PROGRESS, cutoff);
        for (Challenge c : overdue) {
            c.setStatus(ChallengeStatus.EXPIRED);
            log.info("Reto {} vencido sin entrega ({})", c.getId(), c.getCandidate().getUsername());
            notifications.notifyGhd(
                    "Reto vencido sin entrega: " + c.getCandidate().getFullName() + " (" + c.getTrack() + " " + c.getLevel() + ")",
                    summary(c));
        }
        return overdue.size();
    }

    // ---------- helpers ----------

    private Challenge find(Long id) {
        return challenges.findById(id).orElseThrow(() -> ApiException.notFound("Reto no encontrado"));
    }

    private Challenge ownChallenge(Long id, AppUser actor) {
        Challenge c = find(id);
        if (!c.getCandidate().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Este reto no esta asignado a tu usuario");
        }
        return c;
    }

    private void checkCanView(Challenge c, AppUser actor) {
        if (actor.getRole() == Role.USER && !c.getCandidate().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Este reto no esta asignado a tu usuario");
        }
    }

    private String summary(Challenge c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resumen del reto tecnico\n\n");
        sb.append("Candidato : ").append(c.getCandidate().getFullName())
                .append(" (").append(c.getCandidate().getUsername()).append(")\n");
        sb.append("Reto      : ").append(c.getTrack()).append(" - ").append(c.getLevel()).append("\n");
        sb.append("Estado    : ").append(c.getStatus()).append("\n");
        sb.append("Asignado  : ").append(fmt.format(c.getAssignedAt())).append("\n");
        if (c.getStartedAt() != null) {
            sb.append("Inicio    : ").append(fmt.format(c.getStartedAt())).append("\n");
            sb.append("Limite    : ").append(fmt.format(c.getDeadlineAt())).append("\n");
        }
        if (c.getSubmittedAt() != null) {
            long mins = Duration.between(c.getStartedAt(), c.getSubmittedAt()).toMinutes();
            sb.append("Entrega   : ").append(fmt.format(c.getSubmittedAt()))
                    .append(" (").append(mins).append(" min de ").append(duration.toMinutes()).append(")\n");
            sb.append("Archivo   : ").append(c.getSubmissionPath()).append("\n");
        } else {
            sb.append("Entrega   : sin entrega\n");
        }
        sb.append("\nPendiente de calificacion (APTO / NO APTO).");
        return sb.toString();
    }
}
