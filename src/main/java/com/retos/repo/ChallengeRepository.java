package com.retos.repo;

import com.retos.domain.AppUser;
import com.retos.domain.Challenge;
import com.retos.domain.ChallengeStatus;
import com.retos.domain.Level;
import com.retos.domain.Track;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    /** Bloquea la fila hasta el fin de la transaccion (entrega en curso: el vencimiento espera). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Challenge c where c.id = :id")
    Optional<Challenge> findByIdForUpdate(@Param("id") Long id);

    /** Cambia el estado solo si sigue en {@code from}; devuelve 0 si otro proceso ya lo cambio. */
    @Modifying
    @Query("update Challenge c set c.status = :to where c.id = :id and c.status = :from")
    int changeStatus(@Param("id") Long id, @Param("from") ChallengeStatus from, @Param("to") ChallengeStatus to);

    List<Challenge> findByCandidateOrderByAssignedAtDesc(AppUser candidate);
    List<Challenge> findAllByOrderByAssignedAtDesc();
    List<Challenge> findByCandidateAndStatus(AppUser candidate, ChallengeStatus status);
    boolean existsByCandidateAndStatusIn(AppUser candidate, Collection<ChallengeStatus> statuses);
    boolean existsByCandidateAndTrackNot(AppUser candidate, Track track);
    boolean existsByCandidateAndTrackAndLevelAndStatusIn(AppUser candidate, Track track, Level level, Collection<ChallengeStatus> statuses);
    List<Challenge> findByStatusAndDeadlineAtBefore(ChallengeStatus status, Instant instant);
}
