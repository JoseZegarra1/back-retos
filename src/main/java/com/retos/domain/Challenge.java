package com.retos.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "challenge")
public class Challenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private AppUser candidate;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private AppUser assignedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Track track;

    @Enumerated(EnumType.STRING)
    @Column(name = "challenge_level", nullable = false, length = 20)
    private Level level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeStatus status = ChallengeStatus.ASSIGNED;

    @Column(nullable = false)
    private Instant assignedAt = Instant.now();

    /** Duracion propia del reto. Null en retos creados antes de este campo: se usa app.challenge.duration-minutes. */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    private Instant startedAt;
    private Instant deadlineAt;
    private Instant submittedAt;

    /** Ruta del blob con la entrega: {usuario}/{tipo}/reto-{id}/entrega.zip */
    @Column(length = 400)
    private String submissionPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_result", length = 20)
    private GradeResult result;

    @Column(length = 2000)
    private String gradeComment;

    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser gradedBy;

    private Instant gradedAt;

    public Long getId() { return id; }
    public AppUser getCandidate() { return candidate; }
    public void setCandidate(AppUser candidate) { this.candidate = candidate; }
    public AppUser getAssignedBy() { return assignedBy; }
    public void setAssignedBy(AppUser assignedBy) { this.assignedBy = assignedBy; }
    public Track getTrack() { return track; }
    public void setTrack(Track track) { this.track = track; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public ChallengeStatus getStatus() { return status; }
    public void setStatus(ChallengeStatus status) { this.status = status; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public int durationOr(int defaultMinutes) { return durationMinutes != null ? durationMinutes : defaultMinutes; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public String getSubmissionPath() { return submissionPath; }
    public void setSubmissionPath(String submissionPath) { this.submissionPath = submissionPath; }
    public GradeResult getResult() { return result; }
    public void setResult(GradeResult result) { this.result = result; }
    public String getGradeComment() { return gradeComment; }
    public void setGradeComment(String gradeComment) { this.gradeComment = gradeComment; }
    public AppUser getGradedBy() { return gradedBy; }
    public void setGradedBy(AppUser gradedBy) { this.gradedBy = gradedBy; }
    public Instant getGradedAt() { return gradedAt; }
    public void setGradedAt(Instant gradedAt) { this.gradedAt = gradedAt; }
}
