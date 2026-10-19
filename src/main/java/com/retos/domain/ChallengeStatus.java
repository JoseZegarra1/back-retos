package com.retos.domain;

public enum ChallengeStatus {
    /** Asignado por GHD, el candidato aun no inicia. */
    ASSIGNED,
    /** Candidato inicio (descargo la carpeta): corre el temporizador de 3 horas. */
    IN_PROGRESS,
    /** Candidato cargo su carpeta de codigo dentro del tiempo. */
    SUBMITTED,
    /** Se agoto el tiempo sin entrega. */
    EXPIRED,
    /** Calificado como APTO / NO APTO. */
    GRADED
}
