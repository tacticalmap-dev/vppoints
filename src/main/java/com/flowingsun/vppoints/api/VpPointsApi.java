package com.flowingsun.vppoints.api;

import com.flowingsun.vppoints.match.SquadMatchService;
import com.flowingsun.vppoints.vp.VictoryMatchManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Public API surface for host mods.
 *
 * Host mod flow:
 * 1) teleport players to match world/spawn externally
 * 2) call {@link #startMatch(SquadMatchService.StartMatchRequest)}
 * 3) listen with {@link #setMatchFinishListener(SquadMatchService.MatchFinishListener)}
 * 4) after post-match teleport, call {@link #endMatch(String, MinecraftServer, String)}
 */
public final class VpPointsApi {
    private VpPointsApi() {
    }

    public static void setMatchFinishListener(SquadMatchService.MatchFinishListener listener) {
        SquadMatchService.INSTANCE.setMatchFinishListener(listener);
    }

    public static boolean startMatch(SquadMatchService.StartMatchRequest request) {
        return SquadMatchService.INSTANCE.startMatch(request);
    }

    public static boolean endMatch(String mapId, MinecraftServer server, String reason) {
        return SquadMatchService.INSTANCE.endMatch(mapId, server, reason);
    }

    public static Optional<SquadMatchService.ActiveMatchView> activeForLevel(ServerLevel level) {
        return SquadMatchService.INSTANCE.activeForLevel(level);
    }

    public static Optional<SquadMatchService.PlayerMatchContext> contextFor(ServerPlayer player) {
        return SquadMatchService.INSTANCE.contextFor(player);
    }

    public static String teamForPlayer(String mapId, java.util.UUID playerId) {
        return SquadMatchService.INSTANCE.teamForPlayer(mapId, playerId);
    }

    public static int returnToMapSecondsForHud(ServerPlayer player) {
        return SquadMatchService.INSTANCE.returnToMapSecondsForHud(player);
    }

    /**
     * Query current resources for one team in one active match.
     */
    public static Optional<VictoryMatchManager.TeamResourceView> resourceOf(String mapId, String teamName) {
        return VictoryMatchManager.INSTANCE.resourceOf(mapId, teamName);
    }

    /**
     * Adjust team resources with signed deltas.
     * victoryPointsDelta/ammoDelta/oilDelta can be positive(add) or negative(subtract).
     * Values are clamped to 0 minimum.
     */
    public static boolean adjustTeamResources(String mapId, String teamName, float victoryPointsDelta, int ammoDelta, int oilDelta) {
        return VictoryMatchManager.INSTANCE.adjustTeamResources(mapId, teamName, victoryPointsDelta, ammoDelta, oilDelta);
    }

    public static boolean addVictoryPoints(String mapId, String teamName, float amount) {
        if (!Float.isFinite(amount) || amount < 0F) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, amount, 0, 0);
    }

    public static boolean subVictoryPoints(String mapId, String teamName, float amount) {
        if (!Float.isFinite(amount) || amount < 0F) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, -amount, 0, 0);
    }

    public static boolean addAmmo(String mapId, String teamName, int amount) {
        if (amount < 0) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, 0F, amount, 0);
    }

    public static boolean subAmmo(String mapId, String teamName, int amount) {
        if (amount < 0) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, 0F, -amount, 0);
    }

    public static boolean addOil(String mapId, String teamName, int amount) {
        if (amount < 0) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, 0F, 0, amount);
    }

    public static boolean subOil(String mapId, String teamName, int amount) {
        if (amount < 0) {
            return false;
        }
        return adjustTeamResources(mapId, teamName, 0F, 0, -amount);
    }
}
