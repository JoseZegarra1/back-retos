package com.retos.domain;

import java.util.EnumSet;
import java.util.Set;

/** Tipo de reto (modulos del diagrama) y los niveles que admite cada uno. */
public enum Track {
    DEVOPS(EnumSet.of(Level.PRINCIPIANTE, Level.COMPETENTE, Level.PROFICIENTE, Level.MASTER)),
    DATA(EnumSet.of(Level.PRINCIPIANTE, Level.COMPETENTE, Level.PROFICIENTE));

    private final Set<Level> levels;

    Track(Set<Level> levels) {
        this.levels = levels;
    }

    public boolean supports(Level level) {
        return levels.contains(level);
    }

    public Set<Level> levels() {
        return levels;
    }
}
