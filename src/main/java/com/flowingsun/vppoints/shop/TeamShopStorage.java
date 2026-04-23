package com.flowingsun.vppoints.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON-backed shop file storage.
 * Files are plain text by design (no encryption).
 */
public final class TeamShopStorage {
    public static final TeamShopStorage INSTANCE = new TeamShopStorage();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String ROOT_DIR = "shops";
    private static final String FILE_NAME_AMMO = "ammo.json";
    private static final String FILE_NAME_OIL = "oil.json";

    private final Map<Path, CachedShop> cacheByPath = new ConcurrentHashMap<>();

    private TeamShopStorage() {
    }

    public List<TeamShopEntry> loadEntries(MinecraftServer server, String mapId, String teamName, ShopCurrency currency) {
        if (server == null || mapId == null || mapId.isBlank() || teamName == null || teamName.isBlank()) {
            return List.of();
        }
        Path path = resolvePath(server, mapId, teamName, currency);
        ensureFileExists(path, currency);
        long modified = lastModifiedMillis(path);
        CachedShop cached = cacheByPath.get(path);
        if (cached != null && cached.lastModifiedMillis == modified) {
            return cached.entries;
        }

        List<TeamShopEntry> loaded = readEntries(path);
        cacheByPath.put(path, new CachedShop(modified, loaded));
        return loaded;
    }

    public Path resolvePath(MinecraftServer server, String mapId, String teamName, ShopCurrency currency) {
        String safeMap = sanitizePathSegment(mapId);
        String safeTeam = sanitizePathSegment(teamName);
        String fileName = currency == ShopCurrency.OIL ? FILE_NAME_OIL : FILE_NAME_AMMO;
        return server.getServerDirectory().toPath()
                .resolve("vppoints")
                .resolve(ROOT_DIR)
                .resolve(safeMap)
                .resolve(safeTeam)
                .resolve(fileName);
    }

    private List<TeamShopEntry> readEntries(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            ShopFile file = GSON.fromJson(raw, ShopFile.class);
            if (file == null || file.entries == null || file.entries.isEmpty()) {
                return List.of();
            }
            List<TeamShopEntry> out = new ArrayList<>();
            int fallbackIndex = 0;
            for (ShopFileEntry row : file.entries) {
                if (row == null) {
                    continue;
                }
                String itemId = Objects.requireNonNullElse(row.item, "").trim();
                if (ResourceLocation.tryParse(itemId) == null) {
                    continue;
                }
                String id = Objects.requireNonNullElse(row.id, "").trim();
                if (id.isBlank()) {
                    id = "entry_" + fallbackIndex++;
                }
                out.add(new TeamShopEntry(
                        id,
                        itemId,
                        Math.max(1, row.count),
                        Math.max(0, row.price),
                        Objects.requireNonNullElse(row.displayName, ""),
                        Objects.requireNonNullElse(row.description, ""),
                        row.requireCommanderApproval,
                        row.allowedRoles == null ? List.of() : row.allowedRoles
                ));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOGGER.warn("Failed to load shop file {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private void ensureFileExists(Path path, ShopCurrency currency) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(path)) {
                return;
            }
            ShopFile file = new ShopFile();
            file.entries = defaultEntries(currency);
            Files.writeString(path, GSON.toJson(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to create default shop file {}: {}", path, e.getMessage());
        }
    }

    private static List<ShopFileEntry> defaultEntries(ShopCurrency currency) {
        ShopFileEntry row = new ShopFileEntry();
        if (currency == ShopCurrency.OIL) {
            row.id = "coal_pack";
            row.item = "minecraft:coal";
            row.count = 8;
            row.price = 30;
            row.displayName = "Coal Pack";
            row.description = "Sample item, edit this JSON file freely.";
        } else {
            row.id = "arrow_pack";
            row.item = "minecraft:arrow";
            row.count = 16;
            row.price = 40;
            row.displayName = "Arrow Pack";
            row.description = "Sample item, edit this JSON file freely.";
        }
        row.requireCommanderApproval = false;
        row.allowedRoles = List.of();
        return List.of(row);
    }

    private static long lastModifiedMillis(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time == null ? 0L : time.toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String sanitizePathSegment(String input) {
        String normalized = Objects.requireNonNullElse(input, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_");
        return normalized.isBlank() ? "default" : normalized;
    }

    private static final class CachedShop {
        private final long lastModifiedMillis;
        private final List<TeamShopEntry> entries;

        private CachedShop(long lastModifiedMillis, List<TeamShopEntry> entries) {
            this.lastModifiedMillis = lastModifiedMillis;
            this.entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private static final class ShopFile {
        private List<ShopFileEntry> entries = List.of();
    }

    private static final class ShopFileEntry {
        private String id;
        private String item;
        private int count = 1;
        private int price = 0;
        private String displayName = "";
        private String description = "";
        private boolean requireCommanderApproval = false;
        private List<String> allowedRoles = List.of();
    }
}

