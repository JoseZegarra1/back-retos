package com.retos.repo;

import com.retos.domain.AppUser;
import com.retos.domain.Challenge;
import com.retos.domain.ChallengeStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByCandidateOrderByAssignedAtDesc(AppUser candidate);
    List<Challenge> findAllByOrderByAssignedAtDesc();
    boolean existsByCandidateAndStatusIn(AppUser candidate, Collection<ChallengeStatus> statuses);
    List<Challenge> findByStatusAndDeadlineAtBefore(ChallengeStatus status, Instant instant);
}
