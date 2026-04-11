package com.flowingsun.vppoints.vp;

import com.flowingsun.vppoints.config.SquadConfig;

/**
 * Capture point role metadata: HUD visibility and per-cycle resource output.
 */
public enum CapturePointType {
    VICTORY(true),
    NORMAL(false),
    AMMO(false),
    OIL(false);

    private final boolean showInTopHud;

    CapturePointType(boolean showInTopHud) {
        this.showInTopHud = showInTopHud;
    }

    public boolean showInTopHud() {
        return showInTopHud;
    }

    public double captureSeconds() {
        double fallback = Math.max(1.0D, SquadConfig.CAPTURE_SECONDS.get());
        double typed = switch (this) {
            case VICTORY -> SquadConfig.CAPTURE_SECONDS_VICTORY.get();
            case NORMAL -> SquadConfig.CAPTURE_SECONDS_NORMAL.get();
            case AMMO -> SquadConfig.CAPTURE_SECONDS_AMMO.get();
            case OIL -> SquadConfig.CAPTURE_SECONDS_OIL.get();
        };
        return typed > 0 ? typed : fallback;
    }

    public int ammoPerCycle() {
        return switch (this) {
            case VICTORY -> SquadConfig.VICTORY_POINT_AMMO_PER_CYCLE.get();
            case NORMAL -> SquadConfig.NORMAL_POINT_AMMO_PER_CYCLE.get();
            case AMMO -> SquadConfig.AMMO_POINT_AMMO_PER_CYCLE.get();
            case OIL -> SquadConfig.OIL_POINT_AMMO_PER_CYCLE.get();
        };
    }

    public int oilPerCycle() {
        return switch (this) {
            case VICTORY -> SquadConfig.VICTORY_POINT_OIL_PER_CYCLE.get();
            case NORMAL -> SquadConfig.NORMAL_POINT_OIL_PER_CYCLE.get();
            case AMMO -> SquadConfig.AMMO_POINT_OIL_PER_CYCLE.get();
            case OIL -> SquadConfig.OIL_POINT_OIL_PER_CYCLE.get();
        };
    }
}

