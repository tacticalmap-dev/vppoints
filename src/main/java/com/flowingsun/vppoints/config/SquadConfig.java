package com.flowingsun.vppoints.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Central Forge config definitions for gameplay and client HUD tuning.
 */
public final class SquadConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.IntValue INITIAL_VICTORY_POINTS;
    public static final ForgeConfigSpec.IntValue INITIAL_AMMO;
    public static final ForgeConfigSpec.IntValue INITIAL_OIL;
    public static final ForgeConfigSpec.IntValue OUT_OF_BOUNDS_COUNTDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue ASSIST_WINDOW_SECONDS;
    public static final ForgeConfigSpec.IntValue SHOP_MAX_PURCHASE_QUANTITY;
    public static final ForgeConfigSpec.BooleanValue SHOP_REQUIRE_WARPATTERN_FOR_ROLE_CHECKS;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_SECONDS_VICTORY;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_SECONDS_NORMAL;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_SECONDS_AMMO;
    public static final ForgeConfigSpec.DoubleValue CAPTURE_SECONDS_OIL;
    public static final ForgeConfigSpec.DoubleValue DRAIN_PER_MINUTE;
    public static final ForgeConfigSpec.IntValue RESOURCE_CYCLE_SECONDS;
    public static final ForgeConfigSpec.IntValue VICTORY_POINT_AMMO_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue VICTORY_POINT_OIL_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue NORMAL_POINT_AMMO_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue NORMAL_POINT_OIL_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue AMMO_POINT_AMMO_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue AMMO_POINT_OIL_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue OIL_POINT_AMMO_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue OIL_POINT_OIL_PER_CYCLE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_CAPTURE_ACTIONBAR;
    public static final ForgeConfigSpec.BooleanValue DEBUG_CAPTURE_LOG;

    public static final ForgeConfigSpec.DoubleValue HUD_LEFT_X;
    public static final ForgeConfigSpec.DoubleValue HUD_RIGHT_X;
    public static final ForgeConfigSpec.DoubleValue HUD_TOP_Y;
    public static final ForgeConfigSpec.DoubleValue HUD_CENTER_Y;
    public static final ForgeConfigSpec.DoubleValue HUD_SCALE;
    public static final ForgeConfigSpec.IntValue SHOP_DEFAULT_PURCHASE_QUANTITY;

    static {
        // Shared server/common gameplay knobs.
        ForgeConfigSpec.Builder common = new ForgeConfigSpec.Builder();
        INITIAL_VICTORY_POINTS = common.comment("Initial victory points for each side in every match")
                .defineInRange("initialVictoryPoints", 500, 1, 100000);
        INITIAL_AMMO = common.comment("Initial team ammo for each side in every match")
                .defineInRange("initialAmmo", 0, 0, 1000000);
        INITIAL_OIL = common.comment("Initial team oil for each side in every match")
                .defineInRange("initialOil", 0, 0, 1000000);
        OUT_OF_BOUNDS_COUNTDOWN_SECONDS = common.comment("Seconds allowed outside map bounds before death")
                .defineInRange("outOfBoundsCountdownSeconds", 30, 1, 600);
        ASSIST_WINDOW_SECONDS = common.comment("Seconds after damage during which a helper is counted as an assist")
                .defineInRange("assistWindowSeconds", 10, 1, 300);
        SHOP_MAX_PURCHASE_QUANTITY = common.comment("Maximum quantity allowed per single shop purchase action")
                .defineInRange("shopMaxPurchaseQuantity", 64, 1, 100000);
        SHOP_REQUIRE_WARPATTERN_FOR_ROLE_CHECKS = common.comment("If true, role/commander restrictions require WarPattern role data and deny when unavailable")
                .define("shopRequireWarpatternForRoleChecks", true);
        CAPTURE_SECONDS = common.comment("Seconds required for 1 player advantage to fully capture a neutral point")
                .defineInRange("captureSeconds", 15.0D, 1.0D, 300.0D);
        CAPTURE_SECONDS_VICTORY = common.comment("Seconds required to fully capture a Victory point with 1 player advantage (-1 uses captureSeconds)")
                .defineInRange("captureSecondsVictory", -1.0D, -1.0D, 300.0D);
        CAPTURE_SECONDS_NORMAL = common.comment("Seconds required to fully capture a Normal point with 1 player advantage (-1 uses captureSeconds)")
                .defineInRange("captureSecondsNormal", -1.0D, -1.0D, 300.0D);
        CAPTURE_SECONDS_AMMO = common.comment("Seconds required to fully capture an Ammo point with 1 player advantage (-1 uses captureSeconds)")
                .defineInRange("captureSecondsAmmo", -1.0D, -1.0D, 300.0D);
        CAPTURE_SECONDS_OIL = common.comment("Seconds required to fully capture an Oil point with 1 player advantage (-1 uses captureSeconds)")
                .defineInRange("captureSecondsOil", -1.0D, -1.0D, 300.0D);
        DRAIN_PER_MINUTE = common.comment("Point drain applied to enemy side for each owned point per minute")
                .defineInRange("drainPerMinute", 25.0D, 0.1D, 1000.0D);
        RESOURCE_CYCLE_SECONDS = common.comment("Resource production cycle in seconds")
                .defineInRange("resourceCycleSeconds", 30, 1, 300);
        VICTORY_POINT_AMMO_PER_CYCLE = common.comment("Ammo produced per cycle by one owned Victory point")
                .defineInRange("victoryPointAmmoPerCycle", 0, 0, 1000000);
        VICTORY_POINT_OIL_PER_CYCLE = common.comment("Oil produced per cycle by one owned Victory point")
                .defineInRange("victoryPointOilPerCycle", 0, 0, 1000000);
        NORMAL_POINT_AMMO_PER_CYCLE = common.comment("Ammo produced per cycle by one owned Normal point")
                .defineInRange("normalPointAmmoPerCycle", 5, 0, 1000000);
        NORMAL_POINT_OIL_PER_CYCLE = common.comment("Oil produced per cycle by one owned Normal point")
                .defineInRange("normalPointOilPerCycle", 3, 0, 1000000);
        AMMO_POINT_AMMO_PER_CYCLE = common.comment("Ammo produced per cycle by one owned Ammo point")
                .defineInRange("ammoPointAmmoPerCycle", 11, 0, 1000000);
        AMMO_POINT_OIL_PER_CYCLE = common.comment("Oil produced per cycle by one owned Ammo point")
                .defineInRange("ammoPointOilPerCycle", 0, 0, 1000000);
        OIL_POINT_AMMO_PER_CYCLE = common.comment("Ammo produced per cycle by one owned Oil point")
                .defineInRange("oilPointAmmoPerCycle", 0, 0, 1000000);
        OIL_POINT_OIL_PER_CYCLE = common.comment("Oil produced per cycle by one owned Oil point")
                .defineInRange("oilPointOilPerCycle", 11, 0, 1000000);

        common.push("debug");
        DEBUG_MODE = common.comment("Master switch for all debug output features")
                .define("debugMode", false);
        DEBUG_CAPTURE_ACTIONBAR = common.comment("Show capture debug info in action bar once per second")
                .define("captureActionbar", true);
        DEBUG_CAPTURE_LOG = common.comment("Write capture debug info to server log")
                .define("captureLog", true);
        common.pop();
        COMMON_SPEC = common.build();

        // Client-only HUD layout knobs.
        ForgeConfigSpec.Builder client = new ForgeConfigSpec.Builder();
        HUD_LEFT_X = client.comment("Left team ticket text X (0-1 of screen width)")
                .defineInRange("hudLeftX", 0.2D, 0.0D, 1.0D);
        HUD_RIGHT_X = client.comment("Right team ticket text X (0-1 of screen width)")
                .defineInRange("hudRightX", 0.8D, 0.0D, 1.0D);
        HUD_TOP_Y = client.comment("Top Y for ticket texts (0-1 of screen height)")
                .defineInRange("hudTopY", 0.08D, 0.0D, 1.0D);
        HUD_CENTER_Y = client.comment("Center Y for point status stars (0-1 of screen height)")
                .defineInRange("hudCenterY", 0.08D, 0.0D, 1.0D);
        HUD_SCALE = client.comment("HUD scale")
                .defineInRange("hudScale", 1.0D, 0.5D, 3.0D);
        SHOP_DEFAULT_PURCHASE_QUANTITY = client.comment("Default quantity prefilled in team shop purchase UI")
                .defineInRange("shopDefaultPurchaseQuantity", 1, 1, 100000);
        CLIENT_SPEC = client.build();
    }
}

