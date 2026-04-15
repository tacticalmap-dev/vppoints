package com.flowingsun.vppoints.cohmode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player KD statistics with JSON persistence.
 */
public class CohStatsManager {
    public static final CohStatsManager INSTANCE = new CohStatsManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "cohmode_stats.json";

    public record PlayerData(long kills, long deaths) {
        public double getKD() {
            if (deaths <= 0) return kills > 0 ? (double) kills : 1.0;
            return (double) kills / deaths;
        }
    }

    private final Map<UUID, PlayerData> stats = new ConcurrentHashMap<>();

    private CohStatsManager() {
        load();
    }

    public PlayerData getStats(UUID playerId) {
        return stats.getOrDefault(playerId, new PlayerData(0, 0));
    }

    public double getKD(UUID playerId) {
        return getStats(playerId).getKD();
    }

    public void recordKill(UUID playerId) {
        stats.compute(playerId, (id, data) -> {
            long k = data == null ? 1 : data.kills + 1;
            long d = data == null ? 0 : data.deaths;
            return new PlayerData(k, d);
        });
        save();
    }

    public void recordDeath(UUID playerId) {
        stats.compute(playerId, (id, data) -> {
            long k = data == null ? 0 : data.kills;
            long d = data == null ? 1 : data.deaths + 1;
            return new PlayerData(k, d);
        });
        save();
    }

    private void load() {
        File file = getStorageFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<HashMap<UUID, PlayerData>>() {}.getType();
            Map<UUID, PlayerData> loaded = GSON.fromJson(reader, type);
            if (loaded != null) stats.putAll(loaded);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void save() {
        File file = getStorageFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(stats, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getStorageFile() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        return path.toFile();
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;

        // Record death
        if (event.getEntity() instanceof Player victim) {
            recordDeath(victim.getUUID());
        }

        // Record kill
        if (event.getSource().getEntity() instanceof Player killer) {
            recordKill(killer.getUUID());
        }
    }
}
