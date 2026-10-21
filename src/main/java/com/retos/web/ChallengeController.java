package com.retos.web;

import com.retos.domain.Level;
import com.retos.domain.Track;
import com.retos.service.ChallengeService;
import com.retos.service.ChallengeService.DownloadFile;
import com.retos.service.UserService;
import com.retos.web.Dtos.*;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ChallengeController {

    private final ChallengeService challenges;
    private final UserService users;

    private final DataSize maxUpload;

    public ChallengeController(ChallengeService challenges, UserService users,
                               @Value("${spring.servlet.multipart.max-file-size:200MB}") DataSize maxUpload) {
        this.challenges = challenges;
        this.users = users;
        this.maxUpload = maxUpload;
    }

    /** Limites que el frontend usa para validar antes de enviar (MAX_UPLOAD, duraciones). */
    @GetMapping("/settings")
    public SettingsDto settings() {
        return new SettingsDto(maxUpload.toBytes(), challenges.defaultMinutes(),
                Dtos.MIN_DURATION_MINUTES, Dtos.MAX_DURATION_MINUTES);
    }

    /** Tipos de reto y niveles que admite cada uno (DevOps: 4 niveles, Data: 3). */
    @GetMapping("/tracks")
    public Map<Track, List<Level>> tracks() {
        Map<Track, List<Level>> map = new LinkedHashMap<>();
        for (Track t : Track.values()) {
            map.put(t, Arrays.stream(Level.values()).filter(t::supports).toList());
        }
        return map;
    }

    @GetMapping("/challenges")
    public List<ChallengeDto> list(Authentication auth) {
        return challenges.list(users.byUsername(auth.getName()));
    }

    @GetMapping("/challenges/{id}")
    public ChallengeDto get(@PathVariable Long id, Authentication auth) {
        return challenges.get(id, users.byUsername(auth.getName()));
    }

    // ---- GHD ----

    /**
     * Asigna. Con el formato anterior ({@code level}) responde un objeto; con {@code levels} responde una lista.
     */
    @PostMapping("/challenges")
    @PreAuthorize("hasAnyRole('GHD','ADMIN')")
    public ResponseEntity<Object> assign(@Valid @RequestBody AssignRequest req, Authentication auth) {
        List<ChallengeDto> dtos = challenges.assign(req, users.byUsername(auth.getName()));
        Object body = req.levels() == null ? dtos.get(0) : dtos;
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PatchMapping("/challenges/{id}/duration")
    @PreAuthorize("hasAnyRole('GHD','ADMIN')")
    public ChallengeDto updateDuration(@PathVariable Long id, @Valid @RequestBody DurationRequest req) {
        return challenges.updateDuration(id, req.durationMinutes());
    }

    // ---- candidato ----

    @PostMapping("/challenges/{id}/start")
    @PreAuthorize("hasRole('USER')")
    public ChallengeDto start(@PathVariable Long id, Authentication auth) {
        return challenges.start(id, users.byUsername(auth.getName()));
    }

    @GetMapping("/challenges/{id}/download")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id, Authentication auth) {
        return zip(challenges.downloadTemplate(id, users.byUsername(auth.getName())));
    }

    @PostMapping(value = "/challenges/{id}/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ChallengeDto submit(@PathVariable Long id, @RequestPart("file") MultipartFile file, Authentication auth) {
        return challenges.submit(id, file, users.byUsername(auth.getName()));
    }

    // ---- calificacion ----

    @PostMapping("/challenges/{id}/grade")
    @PreAuthorize("hasAnyRole('GHD','ADMIN')")
    public ChallengeDto grade(@PathVariable Long id, @Valid @RequestBody GradeRequest req, Authentication auth) {
        return challenges.grade(id, req, users.byUsername(auth.getName()));
    }

    @GetMapping("/challenges/{id}/submission")
    @PreAuthorize("hasAnyRole('GHD','ADMIN')")
    public ResponseEntity<InputStreamResource> submission(@PathVariable Long id) {
        return zip(challenges.downloadSubmission(id));
    }

    // ---- carpeta de codigo de cada reto ----

    @PutMapping(value = "/templates/{track}/{level}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> uploadTemplate(@PathVariable Track track, @PathVariable Level level,
                                               @RequestPart("file") MultipartFile file) {
        challenges.uploadTemplate(track, level, file);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<InputStreamResource> zip(DownloadFile f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.filename()).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new InputStreamResource(f.stream()));
    }
}
