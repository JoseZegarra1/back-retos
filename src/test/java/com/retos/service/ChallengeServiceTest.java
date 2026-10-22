package com.retos.service;

import com.retos.domain.*;
import com.retos.repo.ChallengeRepository;
import com.retos.repo.UserRepository;
import com.retos.storage.StorageService;
import com.retos.web.ApiException;
import com.retos.web.Dtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChallengeServiceTest {

    @Mock
    private ChallengeRepository challengeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private NotificationService notificationService;

    private ChallengeService challengeService;
    private AppUser candidate;
    private AppUser admin;

    @BeforeEach
    void setUp() {
        challengeService = new ChallengeService(
                challengeRepository,
                userRepository,
                storageService,
                notificationService,
                180,
                60,
                "America/Lima",
                "http://localhost:4200"
        );

        candidate = new AppUser();
        ReflectionTestUtils.setField(candidate, "id", 10L);
        candidate.setUsername("candidato.test");
        candidate.setFullName("Candidato Test");
        candidate.setRole(Role.USER);
        candidate.setActive(true);

        admin = new AppUser();
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.setUsername("admin");
        admin.setFullName("Admin Test");
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
    }

    private void saveReturnsArgument() {
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Challenge challenge(long id, Level level, ChallengeStatus status) {
        Challenge c = new Challenge();
        ReflectionTestUtils.setField(c, "id", id);
        c.setCandidate(candidate);
        c.setAssignedBy(admin);
        c.setTrack(Track.DEVOPS);
        c.setLevel(level);
        c.setStatus(status);
        c.setDurationMinutes(180);
        return c;
    }

    // ---------- asignacion ----------

    @Test
    void legacyFormatAssignsOneChallengeWithDefaultDuration() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));
        when(storageService.exists(any())).thenReturn(true);
        saveReturnsArgument();

        List<ChallengeDto> result = challengeService.assign(
                new AssignRequest(10L, Track.DEVOPS, Level.PRINCIPIANTE, null, null), admin);

        assertEquals(1, result.size());
        assertEquals(Level.PRINCIPIANTE, result.get(0).level());
        assertEquals(180, result.get(0).durationMinutes());
    }

    @Test
    void assignsSeveralLevelsOfSameTrackWithCustomDuration() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));
        when(storageService.exists(any())).thenReturn(true);
        saveReturnsArgument();

        List<ChallengeDto> result = challengeService.assign(new AssignRequest(10L, Track.DEVOPS, null,
                List.of(Level.PRINCIPIANTE, Level.COMPETENTE), 90), admin);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(d -> d.durationMinutes() == 90));
        verify(challengeRepository, times(2)).save(any(Challenge.class));
    }

    @Test
    void rejectsTrackDifferentFromExistingChallenges() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));
        when(challengeRepository.existsByCandidateAndTrackNot(candidate, Track.DATA)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.assign(
                new AssignRequest(10L, Track.DATA, Level.PRINCIPIANTE, null, null), admin));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("DEVOPS"));
        verify(challengeRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicatePendingLevel() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));
        when(challengeRepository.existsByCandidateAndTrackAndLevelAndStatusIn(
                eq(candidate), eq(Track.DEVOPS), eq(Level.PRINCIPIANTE), any()))
                .thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.assign(
                new AssignRequest(10L, Track.DEVOPS, Level.PRINCIPIANTE, null, null), admin));
        assertTrue(ex.getMessage().contains("ya tiene asignado"));
    }

    @Test
    void batchIsAtomicWhenOneLevelFails() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));
        when(storageService.exists(ChallengeService.templatePath(Track.DEVOPS, Level.PRINCIPIANTE))).thenReturn(true);
        when(storageService.exists(ChallengeService.templatePath(Track.DEVOPS, Level.COMPETENTE))).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.assign(new AssignRequest(
                10L, Track.DEVOPS, null, List.of(Level.PRINCIPIANTE, Level.COMPETENTE), null), admin));

        assertTrue(ex.getMessage().contains("COMPETENTE"));
        verify(challengeRepository, never()).save(any());
    }

    @Test
    void rejectsMasterForData() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.assign(
                new AssignRequest(10L, Track.DATA, null, List.of(Level.MASTER), null), admin));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void rejectsRepeatedLevelInRequest() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(candidate));

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.assign(new AssignRequest(
                10L, Track.DEVOPS, null, List.of(Level.COMPETENTE, Level.COMPETENTE), null), admin));
        assertTrue(ex.getMessage().contains("repetido"));
    }

    // ---------- inicio: un solo reto en curso ----------

    @Test
    void cannotStartWhileAnotherIsInProgress() {
        Challenge pending = challenge(2L, Level.COMPETENTE, ChallengeStatus.ASSIGNED);
        Challenge running = challenge(1L, Level.PRINCIPIANTE, ChallengeStatus.IN_PROGRESS);
        running.setStartedAt(Instant.now());
        running.setDeadlineAt(Instant.now().plus(Duration.ofHours(1)));
        when(challengeRepository.findById(2L)).thenReturn(Optional.of(pending));
        when(challengeRepository.findByCandidateAndStatus(candidate, ChallengeStatus.IN_PROGRESS)).thenReturn(List.of(running));

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.start(2L, candidate));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ChallengeStatus.ASSIGNED, pending.getStatus());
    }

    @Test
    void startUsesChallengeDuration() {
        Challenge pending = challenge(2L, Level.COMPETENTE, ChallengeStatus.ASSIGNED);
        pending.setDurationMinutes(45);
        when(challengeRepository.findById(2L)).thenReturn(Optional.of(pending));
        when(challengeRepository.findByCandidateAndStatus(candidate, ChallengeStatus.IN_PROGRESS)).thenReturn(List.of());

        challengeService.start(2L, candidate);

        assertEquals(ChallengeStatus.IN_PROGRESS, pending.getStatus());
        assertEquals(Duration.ofMinutes(45), Duration.between(pending.getStartedAt(), pending.getDeadlineAt()));
    }

    // ---------- duracion ----------

    @Test
    void durationChangeInProgressRecalculatesDeadline() {
        Challenge running = challenge(1L, Level.PRINCIPIANTE, ChallengeStatus.IN_PROGRESS);
        Instant started = Instant.now().minus(Duration.ofMinutes(30));
        running.setStartedAt(started);
        running.setDeadlineAt(started.plus(Duration.ofMinutes(180)));
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(running));

        challengeService.updateDuration(1L, 240);

        assertEquals(started.plus(Duration.ofMinutes(240)), running.getDeadlineAt());
        assertEquals(240, running.getDurationMinutes());
    }

    @Test
    void durationChangeRejectedWhenNewDeadlineAlreadyPassed() {
        Challenge running = challenge(1L, Level.PRINCIPIANTE, ChallengeStatus.IN_PROGRESS);
        Instant started = Instant.now().minus(Duration.ofMinutes(60));
        running.setStartedAt(started);
        running.setDeadlineAt(started.plus(Duration.ofMinutes(180)));
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(running));

        assertThrows(ApiException.class, () -> challengeService.updateDuration(1L, 30));
        assertEquals(180, running.getDurationMinutes());
    }

    @Test
    void durationChangeRejectedInFinalStates() {
        Challenge done = challenge(1L, Level.PRINCIPIANTE, ChallengeStatus.SUBMITTED);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(done));

        ApiException ex = assertThrows(ApiException.class, () -> challengeService.updateDuration(1L, 200));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    // ---------- correos a GHD ----------

    private Challenge running(long id, Duration sinceStart) {
        Challenge c = challenge(id, Level.PRINCIPIANTE, ChallengeStatus.IN_PROGRESS);
        Instant started = Instant.now().minus(sinceStart);
        c.setStartedAt(started);
        c.setDeadlineAt(started.plus(Duration.ofMinutes(180)));
        return c;
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "solucion.zip", "application/zip", new byte[] {'P', 'K', 3, 4});
    }

    @Test
    void submitSendsDeliveredMailToGhd() {
        Challenge c = running(1L, Duration.ofMinutes(85));
        when(challengeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        challengeService.submit(1L, zip(), candidate);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyGhd(subject.capture(), body.capture());
        assertEquals("Reto entregado - Candidato Test (DevOps · Principiante)", subject.getValue());
        assertTrue(body.getValue().startsWith("El candidato ha terminado la evaluación y entregó su archivo .zip. "
                + "Ya puedes descargarlo y calificarlo desde la plataforma."));
        assertTrue(body.getValue().contains("Candidato Test (usuario: candidato.test)"));
        assertTrue(body.getValue().contains("Tiempo utilizado  : 1 h 25 min"));
        assertTrue(body.getValue().contains("Duración asignada : 3 h"));
        assertTrue(body.getValue().contains("(America/Lima)"));
    }

    @Test
    void submitWithinGraceSendsOnlyDeliveredMail() {
        Challenge c = running(1L, Duration.ofMinutes(180).plusSeconds(30)); // limite + 30 s, gracia de 60 s
        when(challengeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(c));

        challengeService.submit(1L, zip(), candidate);

        assertEquals(ChallengeStatus.SUBMITTED, c.getStatus());
        verify(notificationService).notifyGhd(startsWith("Reto entregado - "), anyString());
        verify(notificationService, never()).notifyGhd(startsWith("Tiempo vencido"), anyString());
    }

    @Test
    void expiredWithoutSubmissionSendsExpiredMail() {
        Challenge c = running(1L, Duration.ofMinutes(185));
        when(challengeRepository.findByStatusAndDeadlineAtBefore(eq(ChallengeStatus.IN_PROGRESS), any())).thenReturn(List.of(c));
        when(challengeRepository.changeStatus(1L, ChallengeStatus.IN_PROGRESS, ChallengeStatus.EXPIRED)).thenReturn(1);

        challengeService.expireOverdue();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyGhd(eq("Tiempo vencido sin entrega - Candidato Test (DevOps · Principiante)"), body.capture());
        assertTrue(body.getValue().startsWith("El tiempo del reto venció y el candidato NO entregó su solución."));
        assertTrue(body.getValue().contains("Hora de inicio"));
        assertTrue(body.getValue().contains("Hora límite"));
        assertEquals(ChallengeStatus.EXPIRED, c.getStatus());
    }

    @Test
    void expireSkipsChallengeAlreadySubmitted() {
        Challenge c = running(1L, Duration.ofMinutes(185));
        when(challengeRepository.findByStatusAndDeadlineAtBefore(eq(ChallengeStatus.IN_PROGRESS), any())).thenReturn(List.of(c));
        // la entrega se confirmo entre la consulta y el UPDATE
        when(challengeRepository.changeStatus(1L, ChallengeStatus.IN_PROGRESS, ChallengeStatus.EXPIRED)).thenReturn(0);

        challengeService.expireOverdue();

        verifyNoInteractions(notificationService);
        assertEquals(ChallengeStatus.IN_PROGRESS, c.getStatus());
    }

    @Test
    void formatsMinutes() {
        assertEquals("menos de 1 min", ChallengeService.formatMinutes(0));
        assertEquals("45 min", ChallengeService.formatMinutes(45));
        assertEquals("3 h", ChallengeService.formatMinutes(180));
        assertEquals("1 h 25 min", ChallengeService.formatMinutes(85));
    }
}
