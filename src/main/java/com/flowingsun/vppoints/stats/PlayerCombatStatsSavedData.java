package com.flowingsun.vppoints.stats;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent storage for global K/A/D and last-match performance by player.
 * Stored in server root folder (not world save folder).
 */
public final class PlayerCombatStatsSavedData {
    public static final String FILE_NAME = "player_combat_stats.dat";

    private static final String KEY_GLOBAL = "global";
    private static final String KEY_LAST_MATCH = "lastMatch";
    private static final String KEY_PLAYER = "player";
    private static final String KEY_KILLS = "kills";
    private static final String KEY_ASSISTS = "assists";
    private static final String KEY_DEATHS = "deaths";
    private static final String KEY_MAP_ID = "mapId";
    private static final String KEY_MAP_NAME = "mapName";
    private static final String KEY_ENDED_AT = "endedAtEpochMs";

    private final Map<UUID, GlobalCounter> globalByPlayer = new HashMap<>();
    private final Map<UUID, LastMatchCounter> lastMatchByPlayer = new HashMap<>();
    private final Path storagePath;

    private PlayerCombatStatsSavedData(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static PlayerCombatStatsSavedData loadOrCreate(Path storagePath) {
        PlayerCombatStatsSavedData data = new PlayerCombatStatsSavedData(storagePath);
        if (storagePath == null) {
            return data;
        }
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(storagePath)) {
                CompoundTag tag = NbtIo.readCompressed(storagePath.toFile());
                if (tag != null) {
                    data.readFromTag(tag);
                }
            }
        } catch (Exception ignored) {
            // Best effort load: start with empty stats on malformed/unreadable files.
        }
        return data;
    }

    private void readFromTag(CompoundTag tag) {
        ListTag globalList = tag.getList(KEY_GLOBAL, Tag.TAG_COMPOUND);
        for (int i = 0; i < globalList.size(); i++) {
            CompoundTag row = globalList.getCompound(i);
            UUID playerId = row.getUUID(KEY_PLAYER);
            long kills = Math.max(0L, row.getLong(KEY_KILLS));
            long assists = Math.max(0L, row.getLong(KEY_ASSISTS));
            long deaths = Math.max(0L, row.getLong(KEY_DEATHS));
            globalByPlayer.put(playerId, new GlobalCounter(kills, assists, deaths));
        }

        ListTag lastMatchList = tag.getList(KEY_LAST_MATCH, Tag.TAG_COMPOUND);
        for (int i = 0; i < lastMatchList.size(); i++) {
            CompoundTag row = lastMatchList.getCompound(i);
            UUID playerId = row.getUUID(KEY_PLAYER);
            String mapId = row.getString(KEY_MAP_ID);
            String mapName = row.getString(KEY_MAP_NAME);
            int kills = Math.max(0, row.getInt(KEY_KILLS));
            int assists = Math.max(0, row.getInt(KEY_ASSISTS));
            int deaths = Math.max(0, row.getInt(KEY_DEATHS));
            long endedAtEpochMs = Math.max(0L, row.getLong(KEY_ENDED_AT));
            lastMatchByPlayer.put(playerId, new LastMatchCounter(mapId, mapName, kills, assists, deaths, endedAtEpochMs));
        }
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag globalList = new ListTag();
        for (Map.Entry<UUID, GlobalCounter> e : globalByPlayer.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_PLAYER, e.getKey());
            row.putLong(KEY_KILLS, Math.max(0L, e.getValue().kills()));
            row.putLong(KEY_ASSISTS, Math.max(0L, e.getValue().assists()));
            row.putLong(KEY_DEATHS, Math.max(0L, e.getValue().deaths()));
            globalList.add(row);
        }
        tag.put(KEY_GLOBAL, globalList);

        ListTag lastMatchList = new ListTag();
        for (Map.Entry<UUID, LastMatchCounter> e : lastMatchByPlayer.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_PLAYER, e.getKey());
            LastMatchCounter c = e.getValue();
            row.putString(KEY_MAP_ID, c.mapId() == null ? "" : c.mapId());
            row.putString(KEY_MAP_NAME, c.mapName() == null ? "" : c.mapName());
            row.putInt(KEY_KILLS, Math.max(0, c.kills()));
            row.putInt(KEY_ASSISTS, Math.max(0, c.assists()));
            row.putInt(KEY_DEATHS, Math.max(0, c.deaths()));
            row.putLong(KEY_ENDED_AT, Math.max(0L, c.endedAtEpochMs()));
            lastMatchList.add(row);
        }
        tag.put(KEY_LAST_MATCH, lastMatchList);
        return tag;
    }

    public synchronized Path storagePath() {
        return storagePath;
    }

    public synchronized GlobalCounter globalOf(UUID playerId) {
        return globalByPlayer.getOrDefault(playerId, GlobalCounter.ZERO);
    }

    public synchronized LastMatchCounter lastMatchOf(UUID playerId) {
        return lastMatchByPlayer.get(playerId);
    }

    public synchronized void addGlobal(UUID playerId, int killsDelta, int assistsDelta, int deathsDelta) {
        if (playerId == null) {
            return;
        }
        GlobalCounter old = globalByPlayer.getOrDefault(playerId, GlobalCounter.ZERO);
        long kills = clampNonNegative(old.kills() + (long) killsDelta);
        long assists = clampNonNegative(old.assists() + (long) assistsDelta);
        long deaths = clampNonNegative(old.deaths() + (long) deathsDelta);
        globalByPlayer.put(playerId, new GlobalCounter(kills, assists, deaths));
        saveQuietly();
    }

    public synchronized void setLastMatch(UUID playerId, LastMatchCounter lastMatch) {
        if (playerId == null || lastMatch == null) {
            return;
        }
        lastMatchByPlayer.put(playerId, lastMatch);
        saveQuietly();
    }

    private static long clampNonNegative(long value) {
        return Math.max(0L, value);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record GlobalCounter(long kills, long assists, long deaths) {
        public static final GlobalCounter ZERO = new GlobalCounter(0L, 0L, 0L);
    }

    public record LastMatchCounter(
            String mapId,
            String mapName,
            int kills,
            int assists,
            int deaths,
            long endedAtEpochMs
    ) {
    }

    private void saveQuietly() {
        if (storagePath == null) {
            return;
        }
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            NbtIo.writeCompressed(toTag(), storagePath.toFile());
        } catch (IOException ignored) {
            // Best effort persistence; runtime should continue even if disk write fails.
        }
    }
}
