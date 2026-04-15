package com.flowingsun.vppoints.stats;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.match.SquadMatchService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player K/A/D for active VP matches and persists global + last-match stats.
 */
public final class PlayerCombatStatsService {
    public static final PlayerCombatStatsService INSTANCE = new PlayerCombatStatsService();

    private final Map<String, Map<UUID, MatchCounter>> matchCountersByMap = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> recentAttackersByVictim = new ConcurrentHashMap<>();
    private final Map<Path, PlayerCombatStatsSavedData> storeByPath = new ConcurrentHashMap<>();

    private PlayerCombatStatsService() {
    }

    public record GlobalCombatView(long kills, long assists, long deaths, double kd) {
    }

    public record LastMatchCombatView(
            String mapId,
            String mapName,
            int kills,
            int assists,
            int deaths,
            double kd,
            long endedAtEpochMs
    ) {
    }

    public record PlayerCombatSummaryView(
            UUID playerId,
            GlobalCombatView global,
            LastMatchCombatView lastMatch
    ) {
    }

    public void onMatchStarted(String mapId, Set<UUID> playersA, Set<UUID> playersB) {
        if (mapId == null || mapId.isBlank()) {
            return;
        }
        Map<UUID, MatchCounter> counters = new HashMap<>();
        if (playersA != null) {
            for (UUID id : playersA) {
                if (id != null) {
                    counters.put(id, new MatchCounter());
                }
            }
        }
        if (playersB != null) {
            for (UUID id : playersB) {
                if (id != null) {
                    counters.put(id, new MatchCounter());
                }
            }
        }
        matchCountersByMap.put(mapId, counters);
    }

    public void onMatchEnded(String mapId, String mapName, MinecraftServer server, Set<UUID> playersA, Set<UUID> playersB) {
        if (mapId == null || mapId.isBlank() || server == null) {
            return;
        }

        Map<UUID, MatchCounter> removed = matchCountersByMap.remove(mapId);
        Set<UUID> participants = new HashSet<>();
        if (playersA != null) {
            participants.addAll(playersA);
        }
        if (playersB != null) {
            participants.addAll(playersB);
        }
        if (removed != null) {
            participants.addAll(removed.keySet());
        }

        PlayerCombatStatsSavedData data = getData(server);
        if (data == null) {
            return;
        }

        long endedAt = System.currentTimeMillis();
        for (UUID playerId : participants) {
            if (playerId == null) {
                continue;
            }
            MatchCounter c = removed == null ? null : removed.get(playerId);
            int kills = c == null ? 0 : c.kills;
            int assists = c == null ? 0 : c.assists;
            int deaths = c == null ? 0 : c.deaths;
            data.setLastMatch(playerId, new PlayerCombatStatsSavedData.LastMatchCounter(
                    mapId,
                    mapName == null ? "" : mapName,
                    Math.max(0, kills),
                    Math.max(0, assists),
                    Math.max(0, deaths),
                    endedAt
            ));
            recentAttackersByVictim.remove(playerId);
        }

        for (Map<UUID, Long> attackers : recentAttackersByVictim.values()) {
            attackers.keySet().removeIf(participants::contains);
        }
    }

    public Optional<GlobalCombatView> globalCombatOf(MinecraftServer server, UUID playerId) {
        if (server == null || playerId == null) {
            return Optional.empty();
        }
        PlayerCombatStatsSavedData data = getData(server);
        if (data == null) {
            return Optional.empty();
        }
        PlayerCombatStatsSavedData.GlobalCounter c = data.globalOf(playerId);
        return Optional.of(new GlobalCombatView(c.kills(), c.assists(), c.deaths(), kd(c.kills(), c.deaths())));
    }

    public Optional<LastMatchCombatView> lastMatchCombatOf(MinecraftServer server, UUID playerId) {
        if (server == null || playerId == null) {
            return Optional.empty();
        }
        PlayerCombatStatsSavedData data = getData(server);
        if (data == null) {
            return Optional.empty();
        }
        PlayerCombatStatsSavedData.LastMatchCounter c = data.lastMatchOf(playerId);
        if (c == null) {
            return Optional.empty();
        }
        return Optional.of(new LastMatchCombatView(
                c.mapId(),
                c.mapName(),
                c.kills(),
                c.assists(),
                c.deaths(),
                kd(c.kills(), c.deaths()),
                c.endedAtEpochMs()
        ));
    }

    public Optional<PlayerCombatSummaryView> combatSummaryOf(MinecraftServer server, UUID playerId) {
        Optional<GlobalCombatView> global = globalCombatOf(server, playerId);
        if (global.isEmpty()) {
            return Optional.empty();
        }
        LastMatchCombatView last = lastMatchCombatOf(server, playerId).orElse(null);
        return Optional.of(new PlayerCombatSummaryView(playerId, global.get(), last));
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        if (event.getAmount() <= 0F) {
            return;
        }

        Optional<SquadMatchService.PlayerMatchContext> victimCtx = SquadMatchService.INSTANCE.contextFor(victim);
        if (victimCtx.isEmpty()) {
            return;
        }

        ServerPlayer attacker = resolvePlayerAttacker(event.getSource());
        if (attacker == null || attacker.getUUID().equals(victim.getUUID())) {
            return;
        }

        Optional<SquadMatchService.PlayerMatchContext> attackerCtx = SquadMatchService.INSTANCE.contextFor(attacker);
        if (attackerCtx.isEmpty() || !attackerCtx.get().mapId().equals(victimCtx.get().mapId())) {
            return;
        }

        long now = nowTick(victim.serverLevel());
        Map<UUID, Long> attackers = recentAttackersByVictim.computeIfAbsent(victim.getUUID(), ignored -> new ConcurrentHashMap<>());
        attackers.put(attacker.getUUID(), now);
        pruneOldAttackers(attackers, now);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }

        Optional<SquadMatchService.PlayerMatchContext> victimCtx = SquadMatchService.INSTANCE.contextFor(victim);
        if (victimCtx.isEmpty()) {
            return;
        }
        String mapId = victimCtx.get().mapId();
        UUID victimId = victim.getUUID();

        incrementMatchDeath(mapId, victimId);
        addGlobal(victim.server, victimId, 0, 0, 1);

        ServerPlayer killer = resolvePlayerAttacker(event.getSource());
        UUID killerId = null;
        if (killer != null && !killer.getUUID().equals(victimId)) {
            Optional<SquadMatchService.PlayerMatchContext> killerCtx = SquadMatchService.INSTANCE.contextFor(killer);
            if (killerCtx.isPresent() && mapId.equals(killerCtx.get().mapId())) {
                killerId = killer.getUUID();
                incrementMatchKill(mapId, killerId);
                addGlobal(victim.server, killerId, 1, 0, 0);
            }
        }

        long now = nowTick(victim.serverLevel());
        Map<UUID, Long> attackers = recentAttackersByVictim.remove(victimId);
        if (attackers == null || attackers.isEmpty()) {
            return;
        }
        long assistWindowTicks = assistWindowTicks();
        for (Map.Entry<UUID, Long> e : attackers.entrySet()) {
            UUID assisterId = e.getKey();
            long tick = e.getValue();
            if (assisterId == null || assisterId.equals(victimId) || assisterId.equals(killerId)) {
                continue;
            }
            if (now - tick > assistWindowTicks) {
                continue;
            }
            ServerPlayer assister = victim.server.getPlayerList().getPlayer(assisterId);
            if (assister == null) {
                continue;
            }
            Optional<SquadMatchService.PlayerMatchContext> assistCtx = SquadMatchService.INSTANCE.contextFor(assister);
            if (assistCtx.isEmpty() || !mapId.equals(assistCtx.get().mapId())) {
                continue;
            }
            incrementMatchAssist(mapId, assisterId);
            addGlobal(victim.server, assisterId, 0, 1, 0);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            recentAttackersByVictim.remove(player.getUUID());
        }
    }

    private void incrementMatchKill(String mapId, UUID playerId) {
        counterFor(mapId, playerId).kills++;
    }

    private void incrementMatchAssist(String mapId, UUID playerId) {
        counterFor(mapId, playerId).assists++;
    }

    private void incrementMatchDeath(String mapId, UUID playerId) {
        counterFor(mapId, playerId).deaths++;
    }

    private MatchCounter counterFor(String mapId, UUID playerId) {
        Map<UUID, MatchCounter> byPlayer = matchCountersByMap.computeIfAbsent(mapId, ignored -> new ConcurrentHashMap<>());
        return byPlayer.computeIfAbsent(playerId, ignored -> new MatchCounter());
    }

    private void addGlobal(MinecraftServer server, UUID playerId, int killsDelta, int assistsDelta, int deathsDelta) {
        PlayerCombatStatsSavedData data = getData(server);
        if (data == null) {
            return;
        }
        data.addGlobal(playerId, killsDelta, assistsDelta, deathsDelta);
    }

    private PlayerCombatStatsSavedData getData(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        Path path = resolveStoragePath(server);
        return storeByPath.computeIfAbsent(path, PlayerCombatStatsSavedData::loadOrCreate);
    }

    private static void pruneOldAttackers(Map<UUID, Long> attackers, long now) {
        long windowTicks = assistWindowTicks();
        attackers.entrySet().removeIf(e -> now - e.getValue() > windowTicks);
    }

    private static ServerPlayer resolvePlayerAttacker(DamageSource source) {
        if (source == null) {
            return null;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer sp) {
            return sp;
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof ServerPlayer sp) {
            return sp;
        }
        if (direct instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer sp) {
            return sp;
        }
        return null;
    }

    private static long nowTick(ServerLevel level) {
        return level.getServer() == null ? 0L : level.getServer().getTickCount();
    }

    private static long assistWindowTicks() {
        return Math.max(1L, (long) SquadConfig.ASSIST_WINDOW_SECONDS.get()) * 20L;
    }

    private static Path resolveStoragePath(MinecraftServer server) {
        File serverDir = server.getServerDirectory();
        return serverDir.toPath()
                .resolve("vppoints")
                .resolve(PlayerCombatStatsSavedData.FILE_NAME);
    }

    private static double kd(long kills, long deaths) {
        if (deaths <= 0L) {
            return (double) kills;
        }
        return (double) kills / (double) deaths;
    }

    private static final class MatchCounter {
        int kills;
        int assists;
        int deaths;
    }
}
