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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChallengeService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);

    private static final Set<ChallengeStatus> PENDING = EnumSet.of(ChallengeStatus.ASSIGNED, ChallengeStatus.IN_PROGRESS);

    private static final Map<Track, String> TRACK_LABEL = Map.of(Track.DEVOPS, "DevOps", Track.DATA, "Data");
    private static final Map<Level, String> LEVEL_LABEL = Map.of(
            Level.PRINCIPIANTE, "Principiante", Level.COMPETENTE, "Competente",
            Level.PROFICIENTE, "Proficiente", Level.MASTER, "Master");

    public record DownloadFile(InputStream stream, String filename) {}

    private final ChallengeRepository challenges;
    private final UserRepository users;
    private final StorageService storage;
    private final NotificationService notifications;
    private final int defaultMinutes;
    private final Duration grace;
    private final DateTimeFormatter fmt;
    private final String zone;
    private final String baseUrl;

    public ChallengeService(ChallengeRepository challenges, UserRepository users, StorageService storage,
                            NotificationService notifications,
                            @Value("${app.challenge.duration-minutes:180}") int defaultMinutes,
                            @Value("${app.challenge.grace-seconds:60}") long graceSeconds,
                            @Value("${app.timezone:America/Lima}") String zone,
                            @Value("${app.base-url:}") String baseUrl) {
        this.challenges = challenges;
        this.users = users;
        this.storage = storage;
        this.notifications = notifications;
        this.defaultMinutes = defaultMinutes;
        this.grace = Duration.ofSeconds(graceSeconds);
        this.fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es"))
                .withZone(ZoneId.of(zone));
        this.zone = zone;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public int defaultMinutes() {
        return defaultMinutes;
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
        return list.stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public ChallengeDto get(Long id, AppUser actor) {
        Challenge c = find(id);
        checkCanView(c, actor);
        return dto(c);
    }

    // ---------- GHD asigna ----------

    /**
     * Asigna uno o varios niveles del mismo modulo. Es atomico: primero se validan todos los niveles
     * y solo si todos son validos se crean los retos.
     */
    @Transactional
    public List<ChallengeDto> assign(AssignRequest req, AppUser actor) {
        AppUser candidate = users.findById(req.candidateId())
                .orElseThrow(() -> ApiException.notFound("Candidato no encontrado"));
        if (candidate.getRole() != Role.USER || !candidate.isActive()) {
            throw ApiException.badRequest("El candidato debe ser un usuario activo con rol USER");
        }
        Track track = req.track();
        Set<Level> levels = requestedLevels(req);

        if (challenges.existsByCandidateAndTrackNot(candidate, track)) {
            Track other = track == Track.DEVOPS ? Track.DATA : Track.DEVOPS;
            throw ApiException.conflict("El candidato ya tiene retos del modulo " + other
                    + "; solo se le pueden asignar retos de ese mismo modulo");
        }
        for (Level level : levels) {
            if (!track.supports(level)) {
                throw ApiException.badRequest("El reto " + track + " no tiene nivel " + level
                        + ". Niveles validos: " + track.levels());
            }
            if (challenges.existsByCandidateAndTrackAndLevelAndStatusIn(candidate, track, level, PENDING)) {
                throw ApiException.conflict("El candidato ya tiene asignado el reto "
                        + track + " - " + level + " en estado pendiente o en curso");
            }
            if (!storage.exists(templatePath(track, level))) {
                throw ApiException.conflict("Aun no se ha cargado la carpeta de codigo para "
                        + track + " / " + level + ". Pidale a un administrador que la suba.");
            }
        }

        int minutes = req.durationMinutes() != null ? req.durationMinutes() : defaultMinutes;
        List<Challenge> created = new ArrayList<>();
        for (Level level : levels) {
            Challenge c = new Challenge();
            c.setCandidate(candidate);
            c.setAssignedBy(actor);
            c.setTrack(track);
            c.setLevel(level);
            c.setDurationMinutes(minutes);
            created.add(challenges.save(c));
        }
        return created.stream().map(this::dto).toList();
    }

    /** Une {@code level} (formato anterior) y {@code levels}; rechaza listas vacias o con repetidos. */
    private static Set<Level> requestedLevels(AssignRequest req) {
        Set<Level> levels = new LinkedHashSet<>();
        if (req.level() != null) {
            levels.add(req.level());
        }
        if (req.levels() != null) {
            Set<Level> seen = EnumSet.noneOf(Level.class);
            for (Level l : req.levels()) {
                if (!seen.add(l)) {
                    throw ApiException.badRequest("El nivel " + l + " esta repetido en la solicitud");
                }
            }
            levels.addAll(seen);
        }
        if (levels.isEmpty()) {
            throw ApiException.badRequest("Selecciona al menos un nivel");
        }
        return levels;
    }

    // ---------- GHD / ADMIN: duracion ----------

    /**
     * ASSIGNED: solo cambia la duracion. IN_PROGRESS: recalcula el limite desde el inicio
     * (sirve para dar mas tiempo). Estados finales: 409.
     */
    @Transactional
    public ChallengeDto updateDuration(Long id, int minutes) {
        Challenge c = find(id);
        switch (c.getStatus()) {
            case ASSIGNED -> c.setDurationMinutes(minutes);
            case IN_PROGRESS -> {
                Instant now = Instant.now();
                if (now.isAfter(c.getDeadlineAt().plus(grace))) {
                    throw ApiException.conflict("El tiempo de este reto ya termino; no se puede cambiar su duracion");
                }
                Instant newDeadline = c.getStartedAt().plus(Duration.ofMinutes(minutes));
                if (!newDeadline.isAfter(now)) {
                    long used = Duration.between(c.getStartedAt(), now).toMinutes();
                    throw ApiException.badRequest("Con " + minutes + " minutos el limite ya habria pasado: el candidato "
                            + "lleva " + used + " minutos. Indica una duracion mayor a " + used + " minutos.");
                }
                c.setDurationMinutes(minutes);
                c.setDeadlineAt(newDeadline);
            }
            default -> throw ApiException.conflict("No se puede cambiar la duracion de un reto en estado " + c.getStatus());
        }
        log.info("Duracion del reto {} cambiada a {} min", c.getId(), minutes);
        return dto(c);
    }

    // ---------- candidato: inicio (descarga) ----------

    /** "Inicio Temp": arranca el temporizador del reto. Idempotente si ya inicio. Solo un reto en curso a la vez. */
    @Transactional
    public ChallengeDto start(Long id, AppUser actor) {
        Challenge c = ownChallenge(id, actor);
        if (c.getStatus() == ChallengeStatus.ASSIGNED) {
            Instant now = Instant.now();
            for (Challenge other : challenges.findByCandidateAndStatus(actor, ChallengeStatus.IN_PROGRESS)) {
                if (now.isAfter(other.getDeadlineAt().plus(grace))) {
                    expire(other); // vencido pero el scheduler aun no lo marco
                } else {
                    throw ApiException.conflict("Ya tienes un reto en curso (" + other.getTrack() + " - "
                            + other.getLevel() + "). Entregalo o espera a que termine su tiempo para iniciar otro.");
                }
            }
            c.setStartedAt(now);
            c.setDeadlineAt(now.plus(Duration.ofMinutes(c.durationOr(defaultMinutes))));
            c.setStatus(ChallengeStatus.IN_PROGRESS);
        } else if (c.getStatus() != ChallengeStatus.IN_PROGRESS) {
            throw ApiException.conflict("Este reto ya no se puede iniciar (estado " + c.getStatus() + ")");
        }
        return dto(c);
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

    /** "Fin Temp": carga de la carpeta de codigo dentro del tiempo del reto. */
    @Transactional
    public ChallengeDto submit(Long id, MultipartFile file, AppUser actor) {
        // Fila bloqueada mientras se sube el .zip: si el vencimiento corre a la vez, espera y ve SUBMITTED
        Challenge c = challenges.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Reto no encontrado"));
        checkOwner(c, actor);
        if (c.getStatus() != ChallengeStatus.IN_PROGRESS) {
            throw ApiException.conflict("El reto no esta en curso (estado " + c.getStatus() + ")");
        }
        Instant now = Instant.now();
        if (now.isAfter(c.getDeadlineAt().plus(grace))) {
            throw new ApiException(HttpStatus.GONE, "El tiempo del reto ha terminado");
        }
        checkZip(file);
        String path = submissionPath(c);
        try (InputStream in = file.getInputStream()) {
            storage.upload(path, in, file.getSize());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar la entrega");
        }
        c.setSubmissionPath(path);
        c.setSubmittedAt(now);
        c.setStatus(ChallengeStatus.SUBMITTED);

        notifyGhdAfterCommit("Reto entregado - " + headline(c), submittedBody(c));
        return dto(c);
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
        return dto(c);
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
        checkZip(file);
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
        overdue.forEach(this::expire);
        return overdue.size();
    }

    private void expire(Challenge c) {
        // UPDATE condicionado: si el candidato entrego (aun dentro de la gracia), no se vence ni se avisa
        if (challenges.changeStatus(c.getId(), ChallengeStatus.IN_PROGRESS, ChallengeStatus.EXPIRED) == 0) {
            log.info("Reto {} no se vence: ya no esta en curso", c.getId());
            return;
        }
        c.setStatus(ChallengeStatus.EXPIRED);
        log.info("Reto {} vencido sin entrega ({})", c.getId(), c.getCandidate().getUsername());
        notifyGhdAfterCommit("Tiempo vencido sin entrega - " + headline(c), expiredBody(c));
    }

    // ---------- helpers ----------

    private ChallengeDto dto(Challenge c) {
        return ChallengeDto.of(c, defaultMinutes);
    }

    private Challenge find(Long id) {
        return challenges.findById(id).orElseThrow(() -> ApiException.notFound("Reto no encontrado"));
    }

    private Challenge ownChallenge(Long id, AppUser actor) {
        Challenge c = find(id);
        checkOwner(c, actor);
        return c;
    }

    private static void checkOwner(Challenge c, AppUser actor) {
        if (!c.getCandidate().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Este reto no esta asignado a tu usuario");
        }
    }

    private void checkCanView(Challenge c, AppUser actor) {
        if (actor.getRole() == Role.USER && !c.getCandidate().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Este reto no esta asignado a tu usuario");
        }
    }

    /** Extension .zip, no vacio y firma ZIP ("PK") en los primeros bytes. */
    private static void checkZip(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Adjunta un archivo .zip que no este vacio");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip")) {
            throw ApiException.badRequest("El archivo debe ser un .zip");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                throw ApiException.badRequest("El archivo no es un .zip valido");
            }
        } catch (IOException e) {
            throw ApiException.badRequest("No se pudo leer el archivo enviado");
        }
    }

    /** El correo sale despues del commit: si la transaccion falla no se avisa de algo que no ocurrio. */
    private void notifyGhdAfterCommit(String subject, String body) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifications.notifyGhd(subject, body);
                }
            });
        } else {
            notifications.notifyGhd(subject, body);
        }
    }

    // ---------- correos a GHD ----------

    /** "{Nombre completo} ({Modulo} · {Nivel})", parte comun de ambos asuntos. */
    private String headline(Challenge c) {
        return c.getCandidate().getFullName() + " (" + reto(c) + ")";
    }

    String submittedBody(Challenge c) {
        long used = Duration.between(c.getStartedAt(), c.getSubmittedAt()).toMinutes();
        return "El candidato ha terminado la evaluación y entregó su archivo .zip. "
                + "Ya puedes descargarlo y calificarlo desde la plataforma.\n\n"
                + candidateLines(c)
                + line("Fecha de entrega", fmt.format(c.getSubmittedAt()) + " (" + zone + ")")
                + line("Tiempo utilizado", formatMinutes(used))
                + line("Duración asignada", formatMinutes(c.durationOr(defaultMinutes)))
                + footer();
    }

    String expiredBody(Challenge c) {
        return "El tiempo del reto venció y el candidato NO entregó su solución.\n\n"
                + candidateLines(c)
                + line("Duración asignada", formatMinutes(c.durationOr(defaultMinutes)))
                + line("Hora de inicio", fmt.format(c.getStartedAt()) + " (" + zone + ")")
                + line("Hora límite", fmt.format(c.getDeadlineAt()) + " (" + zone + ")")
                + footer();
    }

    private String candidateLines(Challenge c) {
        return line("Candidato", c.getCandidate().getFullName() + " (usuario: " + c.getCandidate().getUsername() + ")")
                + line("Reto", reto(c));
    }

    private static String reto(Challenge c) {
        return TRACK_LABEL.get(c.getTrack()) + " · " + LEVEL_LABEL.get(c.getLevel());
    }

    private static String line(String label, String value) {
        return String.format(Locale.ROOT, "%-18s: %s\n", label, value);
    }

    private String footer() {
        return baseUrl.isBlank() ? "" : "\nPlataforma: " + baseUrl + "/retos\n";
    }

    /** 85 -> "1 h 25 min"; 180 -> "3 h"; 0 -> "menos de 1 min". */
    static String formatMinutes(long minutes) {
        if (minutes < 1) {
            return "menos de 1 min";
        }
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) {
            return m + " min";
        }
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
