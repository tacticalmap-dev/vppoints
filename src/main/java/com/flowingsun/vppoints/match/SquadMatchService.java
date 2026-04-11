package com.flowingsun.vppoints.match;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.integration.FtbTeamsCompat;
import com.flowingsun.vppoints.net.MatchHudClearS2C;
import com.flowingsun.vppoints.net.SquadNetwork;
import com.flowingsun.vppoints.vp.VictoryMatchManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime match state service for VP systems.
 *
 * Responsibilities:
 * - active match registry and player assignment
 * - out-of-bounds countdown/kill rule
 * - HUD clear and per-player match context queries
 * - notifying host side when ticket-based finish is reached
 */
public final class SquadMatchService {
    private static final int RETURN_MAP_WARNING_RANGE_BLOCKS = 8;

    public static final SquadMatchService INSTANCE = new SquadMatchService();

    public record ActiveMatchView(
            String mapId,
            String mapName,
            ResourceLocation worldId,
            String teamA,
            int colorA,
            String teamB,
            int colorB,
            Set<UUID> teamAPlayers,
            Set<UUID> teamBPlayers
    ) {
    }

    public record PlayerMatchContext(
            String mapId,
            String mapName,
            String playerTeam,
            String teamA,
            int colorA,
            String teamB,
            int colorB
    ) {
    }

    /** Horizontal bounds used for out-of-bounds checks. */
    public record MatchBounds(int minX, int maxX, int minZ, int maxZ) {
        public MatchBounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid bounds range");
            }
        }
    }

    /** Request object used by host mods to start one runtime match instance. */
    public record StartMatchRequest(
            MinecraftServer server,
            String mapId,
            String mapName,
            ResourceLocation worldId,
            String teamA,
            int colorA,
            String teamB,
            int colorB,
            Set<UUID> teamAPlayers,
            Set<UUID> teamBPlayers,
            MatchBounds bounds
    ) {
    }

    public record MatchFinished(
            String mapId,
            String mapName,
            String reason,
            String teamA,
            String teamB,
            float pointsA,
            float pointsB,
            Set<UUID> playersA,
            Set<UUID> playersB
    ) {
    }

    @FunctionalInterface
    public interface MatchFinishListener {
        void onMatchFinished(MatchFinished result, MinecraftServer server);
    }

    private final Map<String, ActiveMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAssignment> playerAssignments = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, String> worldToMapId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> outOfBoundsDeadlineTickByPlayer = new ConcurrentHashMap<>();

    private volatile MatchFinishListener finishListener;

    private SquadMatchService() {
    }

    public void setMatchFinishListener(MatchFinishListener listener) {
        this.finishListener = listener;
    }

    public boolean startMatch(StartMatchRequest req) {
        if (req == null || req.mapId() == null || req.mapId().isBlank() || req.worldId() == null || req.bounds() == null) {
            return false;
        }

        Set<UUID> teamA = new LinkedHashSet<>(Objects.requireNonNullElse(req.teamAPlayers(), Set.of()));
        Set<UUID> teamB = new LinkedHashSet<>(Objects.requireNonNullElse(req.teamBPlayers(), Set.of()));
        if (teamA.isEmpty() || teamB.isEmpty()) {
            return false;
        }

        Set<UUID> overlap = new HashSet<>(teamA);
        overlap.retainAll(teamB);
        if (!overlap.isEmpty()) {
            return false;
        }

        if (activeMatches.containsKey(req.mapId()) || worldToMapId.containsKey(req.worldId())) {
            return false;
        }

        for (UUID playerId : teamA) {
            if (playerAssignments.containsKey(playerId)) {
                return false;
            }
        }
        for (UUID playerId : teamB) {
            if (playerAssignments.containsKey(playerId)) {
                return false;
            }
        }

        ActiveMatch match = new ActiveMatch();
        match.mapId = req.mapId();
        match.mapName = req.mapName();
        match.worldId = req.worldId();
        match.teamA = Objects.requireNonNullElse(req.teamA(), "red");
        match.teamB = Objects.requireNonNullElse(req.teamB(), "blue");
        match.colorA = req.colorA();
        match.colorB = req.colorB();
        match.playersA.addAll(teamA);
        match.playersB.addAll(teamB);
        match.bounds = req.bounds();

        activeMatches.put(match.mapId, match);
        worldToMapId.put(match.worldId, match.mapId);
        for (UUID playerId : teamA) {
            playerAssignments.put(playerId, new PlayerAssignment(match.mapId, match.teamA));
        }
        for (UUID playerId : teamB) {
            playerAssignments.put(playerId, new PlayerAssignment(match.mapId, match.teamB));
        }

        VictoryMatchManager.INSTANCE.startMatch(match.view());
        if (req.server() != null) {
            FtbTeamsCompat.INSTANCE.syncMapTeams(match.mapId, req.server(), match.teamA, teamA, match.teamB, teamB);
        }
        return true;
    }

    public boolean endMatch(String mapId, MinecraftServer server, String reason) {
        ActiveMatch match = activeMatches.remove(mapId);
        if (match == null) {
            return false;
        }

        worldToMapId.remove(match.worldId);
        for (UUID uuid : match.playersA) {
            playerAssignments.remove(uuid);
            outOfBoundsDeadlineTickByPlayer.remove(uuid);
        }
        for (UUID uuid : match.playersB) {
            playerAssignments.remove(uuid);
            outOfBoundsDeadlineTickByPlayer.remove(uuid);
        }

        VictoryMatchManager.INSTANCE.resetMap(match.mapId);
        if (server != null) {
            FtbTeamsCompat.INSTANCE.disbandMapTeams(match.mapId, server);
            sendHudClearToMatchPlayers(server, match);
        }
        return true;
    }

    /**
     * Triggered by VP ticket logic. Host mod may teleport/cleanup and then call {@link #endMatch}.
     */
    public void finishByTickets(String mapId, MinecraftServer server) {
        ActiveMatch match = activeMatches.get(mapId);
        if (match == null || match.finishNotified) {
            return;
        }
        match.finishNotified = true;

        float pointsA = 0F;
        float pointsB = 0F;
        Optional<VictoryMatchManager.MatchScoreView> score = VictoryMatchManager.INSTANCE.scoreOf(mapId);
        if (score.isPresent()) {
            pointsA = score.get().pointsA();
            pointsB = score.get().pointsB();
        }

        MatchFinished result = new MatchFinished(
                match.mapId,
                match.mapName,
                "ticket-zero",
                match.teamA,
                match.teamB,
                pointsA,
                pointsB,
                Set.copyOf(match.playersA),
                Set.copyOf(match.playersB)
        );

        MatchFinishListener listener = this.finishListener;
        if (listener != null) {
            listener.onMatchFinished(result, server);
        }
    }

    public Optional<ActiveMatchView> activeForLevel(ServerLevel level) {
        String mapId = worldToMapId.get(level.dimension().location());
        if (mapId == null) {
            return Optional.empty();
        }
        ActiveMatch match = activeMatches.get(mapId);
        if (match == null) {
            return Optional.empty();
        }
        return Optional.of(match.view());
    }

    public Optional<PlayerMatchContext> contextFor(ServerPlayer player) {
        PlayerAssignment assignment = playerAssignments.get(player.getUUID());
        if (assignment == null) {
            return Optional.empty();
        }
        ActiveMatch match = activeMatches.get(assignment.mapId());
        if (match == null) {
            return Optional.empty();
        }
        return Optional.of(new PlayerMatchContext(
                match.mapId,
                match.mapName,
                assignment.team(),
                match.teamA,
                match.colorA,
                match.teamB,
                match.colorB
        ));
    }

    public String teamForPlayer(String mapId, UUID playerId) {
        PlayerAssignment assignment = playerAssignments.get(playerId);
        if (assignment == null || !assignment.mapId().equals(mapId)) {
            return null;
        }
        return assignment.team();
    }

    // -1: hidden, 0: warning text only, >0: outside countdown seconds.
    public int returnToMapSecondsForHud(ServerPlayer player) {
        if (player == null) {
            return -1;
        }
        PlayerAssignment assignment = playerAssignments.get(player.getUUID());
        if (assignment == null) {
            return -1;
        }
        ActiveMatch match = activeMatches.get(assignment.mapId());
        if (match == null || match.bounds == null) {
            return -1;
        }
        if (!player.serverLevel().dimension().location().equals(match.worldId)) {
            return -1;
        }

        MatchBounds bounds = match.bounds;
        double x = player.getX();
        double z = player.getZ();
        if (isOutsideBounds(x, z, bounds)) {
            MinecraftServer server = player.getServer();
            long now = server == null ? 0L : server.getTickCount();
            Long deadline = outOfBoundsDeadlineTickByPlayer.get(player.getUUID());
            if (deadline == null) {
                return outOfBoundsCountdownSeconds();
            }
            int seconds = (int) Math.ceil((deadline - now) / 20.0D);
            return Math.max(1, seconds);
        }
        if (isInsideWarningRange(x, z, bounds)) {
            return 0;
        }
        return -1;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        for (ActiveMatch match : new ArrayList<>(activeMatches.values())) {
            endMatch(match.mapId, server, "server-stopping");
        }
        activeMatches.clear();
        playerAssignments.clear();
        worldToMapId.clear();
        outOfBoundsDeadlineTickByPlayer.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        tickReturnToMapState(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        outOfBoundsDeadlineTickByPlayer.remove(player.getUUID());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        sendHudClear(player);
    }

    private void sendHudClearToMatchPlayers(MinecraftServer server, ActiveMatch match) {
        Set<UUID> all = new HashSet<>();
        all.addAll(match.playersA);
        all.addAll(match.playersB);
        for (UUID uuid : all) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendHudClear(player);
            }
        }
    }

    private void sendHudClear(ServerPlayer player) {
        VictoryMatchManager.INSTANCE.invalidateHudCache(player.getUUID());
        SquadNetwork.sendTo(player, new MatchHudClearS2C());
    }

    private void tickReturnToMapState(MinecraftServer server) {
        if (activeMatches.isEmpty()) {
            outOfBoundsDeadlineTickByPlayer.clear();
            return;
        }

        long now = server.getTickCount();
        outOfBoundsDeadlineTickByPlayer.keySet().removeIf(uuid -> {
            PlayerAssignment assignment = playerAssignments.get(uuid);
            return assignment == null || !activeMatches.containsKey(assignment.mapId());
        });

        for (Map.Entry<UUID, PlayerAssignment> entry : playerAssignments.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveMatch match = activeMatches.get(entry.getValue().mapId());
            if (match == null || match.bounds == null) {
                outOfBoundsDeadlineTickByPlayer.remove(playerId);
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !player.serverLevel().dimension().location().equals(match.worldId)) {
                outOfBoundsDeadlineTickByPlayer.remove(playerId);
                continue;
            }

            if (isOutsideBounds(player.getX(), player.getZ(), match.bounds)) {
                long deadline = outOfBoundsDeadlineTickByPlayer.computeIfAbsent(
                        playerId,
                        ignored -> now + outOfBoundsCountdownSeconds() * 20L
                );
                if (now >= deadline) {
                    outOfBoundsDeadlineTickByPlayer.remove(playerId);
                    player.kill();
                }
            } else {
                outOfBoundsDeadlineTickByPlayer.remove(playerId);
            }
        }
    }

    private boolean isOutsideBounds(double x, double z, MatchBounds bounds) {
        return x < bounds.minX || x > (bounds.maxX + 1.0D)
                || z < bounds.minZ || z > (bounds.maxZ + 1.0D);
    }

    private boolean isInsideWarningRange(double x, double z, MatchBounds bounds) {
        if (isOutsideBounds(x, z, bounds)) {
            return false;
        }
        double dLeft = x - bounds.minX;
        double dRight = (bounds.maxX + 1.0D) - x;
        double dTop = z - bounds.minZ;
        double dBottom = (bounds.maxZ + 1.0D) - z;
        double nearest = Math.min(Math.min(dLeft, dRight), Math.min(dTop, dBottom));
        return nearest <= RETURN_MAP_WARNING_RANGE_BLOCKS;
    }

    private static int outOfBoundsCountdownSeconds() {
        return Math.max(1, SquadConfig.OUT_OF_BOUNDS_COUNTDOWN_SECONDS.get());
    }

    private static final class ActiveMatch {
        String mapId;
        String mapName;
        ResourceLocation worldId;
        String teamA;
        String teamB;
        int colorA;
        int colorB;
        final Set<UUID> playersA = new HashSet<>();
        final Set<UUID> playersB = new HashSet<>();
        MatchBounds bounds;
        boolean finishNotified;

        ActiveMatchView view() {
            return new ActiveMatchView(
                    mapId,
                    mapName,
                    worldId,
                    teamA,
                    colorA,
                    teamB,
                    colorB,
                    Set.copyOf(playersA),
                    Set.copyOf(playersB)
            );
        }
    }

    private record PlayerAssignment(String mapId, String team) {
    }
}
