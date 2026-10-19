package com.retos.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChallengeScheduler {

    private final ChallengeService challenges;

    public ChallengeScheduler(ChallengeService challenges) {
        this.challenges = challenges;
    }

    @Scheduled(fixedDelayString = "${app.challenge.expire-check-ms:30000}")
    public void expireOverdue() {
        challenges.expireOverdue();
    }
}
