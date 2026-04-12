package com.flowingsun.vppoints.vp;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.match.SquadMatchService;
import com.flowingsun.vppoints.net.MatchHudSyncS2C;
import com.flowingsun.vppoints.net.SquadNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime scoreboard/resource manager and HUD sync producer for active matches.
 */
public final class VictoryMatchManager {
    public static final VictoryMatchManager INSTANCE = new VictoryMatchManager();

    public record TeamSide(String name, int color) {
    }

    public record MatchScoreView(float pointsA, float pointsB) {
    }

    public record TeamResourceView(String mapId, String teamName, float victoryPoints, int ammo, int oil) {
    }

    public static final class PointSnapshot {
        public long pos;
        public float progress;
        public String ownerTeam;
        public boolean contested;
        public boolean capturing;
    }

    /**
     * Server-side mutable state for one running match instance.
     */
    public static final class MatchState {
        public String mapId;
        public String mapName;
        public ResourceLocation worldId;
        public TeamSide teamA;
        public TeamSide teamB;
        public float pointsA;
        public float pointsB;
        public int ammoA;
        public int oilA;
        public int ammoB;
        public int oilB;
        public final List<PointSnapshot> points = new ArrayList<>();
    }

    private final Map<String, MatchState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastHudDigestByPlayer = new ConcurrentHashMap<>();

    private VictoryMatchManager() {
    }

    public void startMatch(SquadMatchService.ActiveMatchView ctx) {
        MatchState s = new MatchState();
        s.mapId = ctx.mapId();
        s.mapName = ctx.mapName();
        s.worldId = ctx.worldId();
        s.teamA = new TeamSide(ctx.teamA(), ctx.colorA());
        s.teamB = new TeamSide(ctx.teamB(), ctx.colorB());
        s.pointsA = SquadConfig.INITIAL_VICTORY_POINTS.get();
        s.pointsB = SquadConfig.INITIAL_VICTORY_POINTS.get();
        s.ammoA = SquadConfig.INITIAL_AMMO.get();
        s.ammoB = SquadConfig.INITIAL_AMMO.get();
        s.oilA = SquadConfig.INITIAL_OIL.get();
        s.oilB = SquadConfig.INITIAL_OIL.get();
        states.put(s.mapId, s);
    }

    public void resetMap(String mapId) {
        states.remove(mapId);
    }

    public void invalidateHudCache(UUID playerId) {
        if (playerId != null) {
            lastHudDigestByPlayer.remove(playerId);
        }
    }

    public Optional<MatchScoreView> scoreOf(String mapId) {
        MatchState st = states.get(mapId);
        if (st == null) {
            return Optional.empty();
        }
        return Optional.of(new MatchScoreView(Math.max(0F, st.pointsA), Math.max(0F, st.pointsB)));
    }

    public Optional<TeamResourceView> resourceOf(String mapId, String teamName) {
        if (mapId == null || mapId.isBlank() || teamName == null || teamName.isBlank()) {
            return Optional.empty();
        }
        MatchState st = states.get(mapId);
        if (st == null) {
            return Optional.empty();
        }
        if (st.teamA.name.equals(teamName)) {
            return Optional.of(new TeamResourceView(st.mapId, st.teamA.name, Math.max(0F, st.pointsA), Math.max(0, st.ammoA), Math.max(0, st.oilA)));
        }
        if (st.teamB.name.equals(teamName)) {
            return Optional.of(new TeamResourceView(st.mapId, st.teamB.name, Math.max(0F, st.pointsB), Math.max(0, st.ammoB), Math.max(0, st.oilB)));
        }
        return Optional.empty();
    }

    public boolean adjustTeamResources(String mapId, String teamName, float victoryPointsDelta, int ammoDelta, int oilDelta) {
        if (mapId == null || mapId.isBlank() || teamName == null || teamName.isBlank()) {
            return false;
        }
        if (!Float.isFinite(victoryPointsDelta)) {
            return false;
        }

        MatchState st = states.get(mapId);
        if (st == null) {
            return false;
        }

        if (st.teamA.name.equals(teamName)) {
            st.pointsA = addAndClampNonNegative(st.pointsA, victoryPointsDelta);
            st.ammoA = addAndClampNonNegative(st.ammoA, ammoDelta);
            st.oilA = addAndClampNonNegative(st.oilA, oilDelta);
            return true;
        }
        if (st.teamB.name.equals(teamName)) {
            st.pointsB = addAndClampNonNegative(st.pointsB, victoryPointsDelta);
            st.ammoB = addAndClampNonNegative(st.ammoB, ammoDelta);
            st.oilB = addAndClampNonNegative(st.oilB, oilDelta);
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        drainByOwnedVictoryPoints(event.getServer());
        int tickCount = event.getServer().getTickCount();
        produceTeamResources(tickCount);
        if (tickCount % 20L == 0L) {
            syncHud(event.getServer());
        }
    }

    private void drainByOwnedVictoryPoints(MinecraftServer server) {
        Map<String, Integer> ownedByA = new HashMap<>();
        Map<String, Integer> ownedByB = new HashMap<>();

        for (VictoryPointBlockEntity point : VictoryPointBlockEntity.ACTIVE_POINTS) {
            if (point.isRemoved() || point.getLevel() == null || point.getMapId() == null) {
                continue;
            }
            if (point.getPointType() != CapturePointType.VICTORY) {
                continue;
            }
            MatchState st = states.get(point.getMapId());
            if (st == null) {
                continue;
            }
            if (st.teamA.name.equals(point.getOwnerTeam())) {
                ownedByA.merge(st.mapId, 1, Integer::sum);
            } else if (st.teamB.name.equals(point.getOwnerTeam())) {
                ownedByB.merge(st.mapId, 1, Integer::sum);
            }
        }

        float perTick = (float) (SquadConfig.DRAIN_PER_MINUTE.get() / 1200.0D);
        for (MatchState s : states.values()) {
            int a = ownedByA.getOrDefault(s.mapId, 0);
            int b = ownedByB.getOrDefault(s.mapId, 0);
            if (a > 0) {
                s.pointsB -= perTick * a;
            }
            if (b > 0) {
                s.pointsA -= perTick * b;
            }
            if (s.pointsA <= 0F || s.pointsB <= 0F) {
                SquadMatchService.INSTANCE.finishByTickets(s.mapId, server);
            }
        }
    }

    private void produceTeamResources(int tickCount) {
        int cycleTicks = Math.max(1, SquadConfig.RESOURCE_CYCLE_SECONDS.get() * 20);
        if (tickCount % cycleTicks != 0L) {
            return;
        }
        for (VictoryPointBlockEntity point : VictoryPointBlockEntity.ACTIVE_POINTS) {
            if (point.isRemoved() || point.getLevel() == null || point.getMapId() == null || point.getOwnerTeam() == null) {
                continue;
            }
            MatchState st = states.get(point.getMapId());
            if (st == null) {
                continue;
            }
            CapturePointType type = point.getPointType();
            int ammoGain = type.ammoPerCycle();
            int oilGain = type.oilPerCycle();
            if (ammoGain <= 0 && oilGain <= 0) {
                continue;
            }
            if (st.teamA.name.equals(point.getOwnerTeam())) {
                st.ammoA += ammoGain;
                st.oilA += oilGain;
            } else if (st.teamB.name.equals(point.getOwnerTeam())) {
                st.ammoB += ammoGain;
                st.oilB += oilGain;
            }
        }
    }

    private void syncHud(MinecraftServer server) {
        // Build lightweight indexes once per tick to avoid repeated full scans per player.
        Map<String, List<VictoryPointBlockEntity>> pointsByMap = new HashMap<>();
        Map<ResourceLocation, List<VictoryPointBlockEntity>> pointsByWorld = new HashMap<>();
        for (VictoryPointBlockEntity point : VictoryPointBlockEntity.ACTIVE_POINTS) {
            if (point.isRemoved() || point.getLevel() == null) {
                continue;
            }
            if (point.getMapId() != null) {
                pointsByMap.computeIfAbsent(point.getMapId(), k -> new ArrayList<>()).add(point);
            }
            pointsByWorld.computeIfAbsent(point.getLevel().dimension().location(), k -> new ArrayList<>()).add(point);
        }
        for (List<VictoryPointBlockEntity> points : pointsByMap.values()) {
            points.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        }
        for (List<VictoryPointBlockEntity> points : pointsByWorld.values()) {
            points.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        }
        refreshMatchPointSnapshots(pointsByMap, pointsByWorld);

        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayers.add(player.getUUID());
            Optional<SquadMatchService.PlayerMatchContext> c = SquadMatchService.INSTANCE.contextFor(player);
            if (c.isEmpty()) {
                lastHudDigestByPlayer.remove(player.getUUID());
                continue;
            }
            MatchState st = states.get(c.get().mapId());
            if (st == null) {
                lastHudDigestByPlayer.remove(player.getUUID());
                continue;
            }

            int returnToMapSeconds = SquadMatchService.INSTANCE.returnToMapSecondsForHud(player);
            int digest = computeHudDigest(st, returnToMapSeconds);
            Integer lastDigest = lastHudDigestByPlayer.get(player.getUUID());
            if (lastDigest != null && lastDigest == digest) {
                continue;
            }
            lastHudDigestByPlayer.put(player.getUUID(), digest);

            SquadNetwork.sendTo(player, new MatchHudSyncS2C(
                    st.mapId,
                    st.teamA.name, st.teamA.color, Math.max(0, st.pointsA), st.ammoA, st.oilA,
                    st.teamB.name, st.teamB.color, Math.max(0, st.pointsB), st.ammoB, st.oilB,
                    st.points,
                    returnToMapSeconds
            ));
        }
        lastHudDigestByPlayer.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }

    private void refreshMatchPointSnapshots(
            Map<String, List<VictoryPointBlockEntity>> pointsByMap,
            Map<ResourceLocation, List<VictoryPointBlockEntity>> pointsByWorld
    ) {
        for (MatchState st : states.values()) {
            st.points.clear();
            List<VictoryPointBlockEntity> orderedPoints = pointsByMap.get(st.mapId);
            if ((orderedPoints == null || orderedPoints.isEmpty()) && st.worldId != null) {
                orderedPoints = pointsByWorld.get(st.worldId);
            }
            if (orderedPoints == null || orderedPoints.isEmpty()) {
                continue;
            }
            for (VictoryPointBlockEntity be : orderedPoints) {
                if (!be.getPointType().showInTopHud()) {
                    continue;
                }
                PointSnapshot ps = new PointSnapshot();
                ps.pos = be.getBlockPos().asLong();
                ps.progress = be.getProgressForHud(st.teamA.name, st.teamB.name);
                ps.ownerTeam = be.getOwnerTeam();
                ps.contested = be.isContested();
                ps.capturing = be.isCapturing();
                st.points.add(ps);
            }
        }
    }

    private static int computeHudDigest(MatchState st, int returnToMapSeconds) {
        int hash = 1;
        hash = 31 * hash + Objects.hashCode(st.mapId);
        hash = 31 * hash + Objects.hashCode(st.teamA.name);
        hash = 31 * hash + st.teamA.color;
        hash = 31 * hash + Math.round(Math.max(0, st.pointsA));
        hash = 31 * hash + st.ammoA;
        hash = 31 * hash + st.oilA;
        hash = 31 * hash + Objects.hashCode(st.teamB.name);
        hash = 31 * hash + st.teamB.color;
        hash = 31 * hash + Math.round(Math.max(0, st.pointsB));
        hash = 31 * hash + st.ammoB;
        hash = 31 * hash + st.oilB;
        hash = 31 * hash + returnToMapSeconds;
        hash = 31 * hash + st.points.size();
        for (PointSnapshot p : st.points) {
            hash = 31 * hash + Long.hashCode(p.pos);
            hash = 31 * hash + Float.floatToIntBits(p.progress);
            hash = 31 * hash + Objects.hashCode(p.ownerTeam);
            hash = 31 * hash + (p.contested ? 1 : 0);
            hash = 31 * hash + (p.capturing ? 1 : 0);
        }
        return hash;
    }

    private static int addAndClampNonNegative(int base, int delta) {
        long value = (long) base + delta;
        if (value <= 0L) {
            return 0;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static float addAndClampNonNegative(float base, float delta) {
        double value = base + (double) delta;
        if (value <= 0D) {
            return 0F;
        }
        if (value >= Float.MAX_VALUE) {
            return Float.MAX_VALUE;
        }
        return (float) value;
    }
}

