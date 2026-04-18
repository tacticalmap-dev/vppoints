package com.flowingsun.vppoints.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Team integration bridge:
 * - Prefer FTB Teams when available
 * - Fallback to vanilla scoreboard teams when FTB Teams is absent
 */
public final class FtbTeamsCompat {
    public static final FtbTeamsCompat INSTANCE = new FtbTeamsCompat();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, Set<UUID>> createdPartyTeamIdsByMap = new HashMap<>();
    private final Map<String, VanillaMapTeamState> vanillaTeamStateByMap = new HashMap<>();

    public void syncMapTeams(String mapId, MinecraftServer server, String teamAName, Set<UUID> teamAPlayers, String teamBName, Set<UUID> teamBPlayers) {
        if (mapId == null || server == null) return;
        if (isAvailableAndLoaded()) {
            // Ensure fallback state from previous rounds is cleared when FTB becomes available.
            disbandMapTeamsByVanilla(mapId, server);
            syncMapTeamsByFtb(mapId, server, teamAName, teamAPlayers, teamBName, teamBPlayers);
            return;
        }
        // FTB missing: use vanilla scoreboard teams.
        createdPartyTeamIdsByMap.remove(mapId);
        syncMapTeamsByVanilla(mapId, server, teamAName, teamAPlayers, teamBName, teamBPlayers);
    }

    public void disbandMapTeams(String mapId, MinecraftServer server) {
        if (mapId == null || server == null) return;
        if (isAvailableAndLoaded()) {
            disbandMapTeamsByFtb(mapId, server);
        } else {
            createdPartyTeamIdsByMap.remove(mapId);
        }
        // Always attempt to clear fallback state in case runtime switched paths.
        disbandMapTeamsByVanilla(mapId, server);
    }

    private void syncMapTeamsByFtb(String mapId, MinecraftServer server, String teamAName, Set<UUID> teamAPlayers, String teamBName, Set<UUID> teamBPlayers) {
        disbandMapTeamsByFtb(mapId, server);
        Object manager = manager();
        if (manager == null) return;

        Set<UUID> createdIds = new HashSet<>();
        createAndFillParty(mapId, manager, server, teamAName, teamAPlayers, createdIds);
        createAndFillParty(mapId, manager, server, teamBName, teamBPlayers, createdIds);

        if (!createdIds.isEmpty()) {
            createdPartyTeamIdsByMap.put(mapId, createdIds);
            LOGGER.info("FTB Teams synced for map '{}': {} temporary parties", mapId, createdIds.size());
        }
    }

    private void disbandMapTeamsByFtb(String mapId, MinecraftServer server) {
        Set<UUID> ids = createdPartyTeamIdsByMap.remove(mapId);
        if (ids == null || ids.isEmpty()) return;

        Object manager = manager();
        if (manager == null) return;

        for (UUID id : ids) {
            Object team = optionalValue(invokeOneArg(manager, "getTeamByID", id));
            if (team == null) continue;
            if (!bool(invokeNoArgs(team, "isPartyTeam"))) continue;

            if (!forceDisband(team, server)) {
                fallbackDisbandByLeavingAll(team);
            }
        }

        LOGGER.info("FTB Teams disbanded for map '{}': {} temporary parties", mapId, ids.size());
    }

    private void syncMapTeamsByVanilla(
            String mapId,
            MinecraftServer server,
            String teamAName,
            Set<UUID> teamAPlayers,
            String teamBName,
            Set<UUID> teamBPlayers
    ) {
        disbandMapTeamsByVanilla(mapId, server);
        ServerScoreboard scoreboard = server.getScoreboard();
        String teamAId = buildVanillaTeamId(mapId, "a");
        String teamBId = buildVanillaTeamId(mapId, "b");

        PlayerTeam teamA = scoreboard.getPlayerTeam(teamAId);
        if (teamA == null) {
            teamA = scoreboard.addPlayerTeam(teamAId);
        }
        PlayerTeam teamB = scoreboard.getPlayerTeam(teamBId);
        if (teamB == null) {
            teamB = scoreboard.addPlayerTeam(teamBId);
        }
        teamA.setDisplayName(Component.literal(safeTeamDisplayName(teamAName, "Team A")));
        teamB.setDisplayName(Component.literal(safeTeamDisplayName(teamBName, "Team B")));

        VanillaMapTeamState state = new VanillaMapTeamState(teamAId, teamBId);
        assignPlayersToVanillaTeam(server, scoreboard, teamA, teamAPlayers, state);
        assignPlayersToVanillaTeam(server, scoreboard, teamB, teamBPlayers, state);
        vanillaTeamStateByMap.put(mapId, state);

        LOGGER.info("Vanilla teams synced for map '{}': teamA='{}' teamB='{}' players={}",
                mapId, teamAId, teamBId, state.previousTeamByPlayer.size());
    }

    private void disbandMapTeamsByVanilla(String mapId, MinecraftServer server) {
        VanillaMapTeamState state = vanillaTeamStateByMap.remove(mapId);
        if (state == null) {
            return;
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam teamA = scoreboard.getPlayerTeam(state.teamAId);
        PlayerTeam teamB = scoreboard.getPlayerTeam(state.teamBId);

        for (Map.Entry<UUID, VanillaPlayerSnapshot> e : state.previousTeamByPlayer.entrySet()) {
            VanillaPlayerSnapshot snap = e.getValue();
            String entry = snap.entryName();
            if (entry == null || entry.isBlank()) {
                continue;
            }

            PlayerTeam current = scoreboard.getPlayersTeam(entry);
            if (current != null && (state.teamAId.equals(current.getName()) || state.teamBId.equals(current.getName()))) {
                scoreboard.removePlayerFromTeam(entry, current);
            }

            String previousTeamName = snap.previousTeamName();
            if (previousTeamName != null && !previousTeamName.isBlank()) {
                PlayerTeam previous = scoreboard.getPlayerTeam(previousTeamName);
                if (previous != null) {
                    scoreboard.addPlayerToTeam(entry, previous);
                }
            }
        }

        if (teamA != null) {
            scoreboard.removePlayerTeam(teamA);
        }
        if (teamB != null && teamB != teamA) {
            scoreboard.removePlayerTeam(teamB);
        }
        LOGGER.info("Vanilla teams disbanded for map '{}'", mapId);
    }

    private void assignPlayersToVanillaTeam(
            MinecraftServer server,
            ServerScoreboard scoreboard,
            PlayerTeam targetTeam,
            Set<UUID> teamPlayers,
            VanillaMapTeamState state
    ) {
        if (teamPlayers == null || teamPlayers.isEmpty() || targetTeam == null) {
            return;
        }
        for (UUID playerId : teamPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            String entry = player.getScoreboardName();
            PlayerTeam previous = scoreboard.getPlayersTeam(entry);
            state.previousTeamByPlayer.putIfAbsent(
                    playerId,
                    new VanillaPlayerSnapshot(entry, previous == null ? null : previous.getName())
            );
            if (previous != null && previous == targetTeam) {
                continue;
            }
            scoreboard.addPlayerToTeam(entry, targetTeam);
        }
    }

    private String buildVanillaTeamId(String mapId, String suffix) {
        // Team packet uses small name limits; keep deterministic and short.
        String hex = Integer.toUnsignedString(Objects.hash(mapId, suffix), 16);
        String id = "vp" + suffix + "_" + hex;
        return id.length() <= 16 ? id : id.substring(0, 16);
    }

    private String safeTeamDisplayName(String configuredName, String fallback) {
        if (configuredName == null || configuredName.isBlank()) {
            return fallback;
        }
        return configuredName;
    }

    private void createAndFillParty(
            String mapId,
            Object manager,
            MinecraftServer server,
            String teamName,
            Set<UUID> teamPlayers,
            Set<UUID> createdIds
    ) {
        if (teamName == null || teamPlayers == null || teamPlayers.isEmpty()) {
            return;
        }

        ServerPlayer owner = pickOwner(server, manager, teamPlayers);
        if (owner == null) {
            return;
        }

        Object party = createPartyTeam(manager, owner, buildPartyName(mapId, teamName));
        if (party == null) {
            return;
        }

        UUID partyId = teamId(party);
        if (partyId != null) {
            createdIds.add(partyId);
        }

        for (UUID playerId : teamPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            movePlayerToParty(manager, party, player, server);
        }
    }

    private boolean isAvailableAndLoaded() {
        try {
            // Reflection avoids classloading failures when FTB Teams is not installed.
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Object api = apiClass.getMethod("api").invoke(null);
            if (api == null) return false;
            return bool(invokeNoArgs(api, "isManagerLoaded"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object manager() {
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Object api = apiClass.getMethod("api").invoke(null);
            return invokeNoArgs(api, "getManager");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String buildPartyName(String mapId, String teamName) {
        String raw = mapId + "_" + teamName;
        return raw.replaceAll("[^a-zA-Z0-9_\\- ]", "_");
    }

    private ServerPlayer pickOwner(MinecraftServer server, Object manager, Set<UUID> members) {
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            Object current = optionalValue(invokeOneArg(manager, "getTeamForPlayer", p));
            if (current != null && !bool(invokeNoArgs(current, "isPartyTeam"))) {
                return p;
            }
        }
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) return p;
        }
        return null;
    }

    private Object createPartyTeam(Object manager, ServerPlayer owner, String partyName) {
        try {
            Object current = optionalValue(invokeOneArg(manager, "getTeamForPlayer", owner));
            if (current != null && bool(invokeNoArgs(current, "isPartyTeam"))) {
                if (!leaveParty(current, owner.getUUID())) {
                    forceDisband(current, owner.server);
                }
            }
            return invokeWithFallback(manager, "createPartyTeam", owner, partyName, null, null);
        } catch (Throwable e) {
            LOGGER.warn("Failed to create FTB party '{}': {}", partyName, e.getMessage());
            return null;
        }
    }

    private void movePlayerToParty(Object manager, Object targetParty, ServerPlayer player, MinecraftServer server) {
        UUID playerId = player.getUUID();
        UUID targetId = teamId(targetParty);
        if (targetId == null) return;

        Object current = optionalValue(invokeOneArg(manager, "getTeamForPlayer", player));
        UUID currentId = teamId(current);
        if (targetId.equals(currentId)) return;

        if (current != null && bool(invokeNoArgs(current, "isPartyTeam"))) {
            leaveParty(current, playerId);
        }

        joinParty(targetParty, player);

        Object now = optionalValue(invokeOneArg(manager, "getTeamForPlayer", player));
        UUID nowId = teamId(now);
        if (!targetId.equals(nowId) && now != null && bool(invokeNoArgs(now, "isPartyTeam"))) {
            forceDisband(now, server);
            joinParty(targetParty, player);
        }
    }

    private boolean joinParty(Object party, ServerPlayer player) {
        try {
            Object res = invokeOneArg(party, "join", player);
            return res != null;
        } catch (Throwable e) {
            LOGGER.debug("FTB party join failed for {}: {}", player.getGameProfile().getName(), e.getMessage());
            return false;
        }
    }

    private boolean leaveParty(Object party, UUID playerId) {
        try {
            Object res = invokeOneArg(party, "leave", playerId);
            return res != null;
        } catch (Throwable e) {
            LOGGER.debug("FTB party leave failed for {}: {}", playerId, e.getMessage());
            return false;
        }
    }

    private boolean forceDisband(Object party, MinecraftServer server) {
        try {
            Object source = server.createCommandSourceStack().withSuppressedOutput();
            Object res = invokeOneArg(party, "forceDisband", source);
            return res != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private void fallbackDisbandByLeavingAll(Object party) {
        Object membersObj = invokeNoArgs(party, "getMembers");
        if (!(membersObj instanceof Collection<?> members)) return;
        List<UUID> ids = new ArrayList<>();
        for (Object o : members) {
            if (o instanceof UUID u) ids.add(u);
        }
        for (UUID id : ids) {
            leaveParty(party, id);
        }
    }

    private UUID teamId(Object teamObj) {
        Object id = invokeNoArgs(teamObj, "getId");
        return id instanceof UUID u ? u : null;
    }

    private Object invokeNoArgs(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeOneArg(Object target, String method, Object arg) {
        if (target == null || arg == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) continue;
            try {
                return m.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private Object invokeWithFallback(Object target, String method, Object... args) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                Object a = args[i];
                if (a == null) continue;
                if (!p[i].isAssignableFrom(a.getClass())) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            try {
                return m.invoke(target, args);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private Object optionalValue(Object maybeOptional) {
        if (maybeOptional instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        return maybeOptional;
    }

    private boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }

    private record VanillaPlayerSnapshot(String entryName, String previousTeamName) {
    }

    private static final class VanillaMapTeamState {
        final String teamAId;
        final String teamBId;
        final Map<UUID, VanillaPlayerSnapshot> previousTeamByPlayer = new HashMap<>();

        private VanillaMapTeamState(String teamAId, String teamBId) {
            this.teamAId = teamAId;
            this.teamBId = teamBId;
        }
    }
}

