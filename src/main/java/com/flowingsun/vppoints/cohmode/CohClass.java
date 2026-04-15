package com.flowingsun.vppoints.cohmode;

import net.minecraft.network.chat.Component;

/**
 * Enumeration for player classes in CohMode.
 */
public enum CohClass {
    COMMANDER("Commander", 1),
    ASSAULT("Assault", 128),
    MEDIC("Medic", 64),
    SUPPORT("Support", 64),
    RECON("Recon", 32);

    private final String name;
    private final int maxPerTeam;

    CohClass(String name, int maxPerTeam) {
        this.name = name;
        this.maxPerTeam = maxPerTeam;
    }

    public String getName() {
        return name;
    }

    public int getMaxPerTeam() {
        return maxPerTeam;
    }

    public Component getDisplayName() {
        return Component.translatable("cohmode.class." + name().toLowerCase());
    }
}
