package com.retos.repo;

import com.retos.domain.AppUser;
import com.retos.domain.Challenge;
import com.retos.domain.ChallengeStatus;
import com.retos.domain.Level;
import com.retos.domain.Track;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByCandidateOrderByAssignedAtDesc(AppUser candidate);
    List<Challenge> findAllByOrderByAssignedAtDesc();
    List<Challenge> findByCandidateAndStatus(AppUser candidate, ChallengeStatus status);
    boolean existsByCandidateAndStatusIn(AppUser candidate, Collection<ChallengeStatus> statuses);
    boolean existsByCandidateAndTrackNot(AppUser candidate, Track track);
    boolean existsByCandidateAndTrackAndLevelAndStatusIn(AppUser candidate, Track track, Level level, Collection<ChallengeStatus> statuses);
    List<Challenge> findByStatusAndDeadlineAtBefore(ChallengeStatus status, Instant instant);
}
