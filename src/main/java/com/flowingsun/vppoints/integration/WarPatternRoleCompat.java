package com.flowingsun.vppoints.integration;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Optional WarPattern role bridge.
 * Uses reflection so vppoints stays loadable without a hard dependency.
 */
public final class WarPatternRoleCompat {
    private static final String WARPATTERN_MOD_ID = "warpattern";
    private static final String SERVICE_CLASS = "com.flowingsun.warpattern.cohmode.CohModeService";
    private static final String PROFILE_INNER_SIMPLE_NAME = "PlayerProfile";

    private static volatile Access access;

    private WarPatternRoleCompat() {
    }

    public static boolean isAvailable() {
        if (!ModList.get().isLoaded(WARPATTERN_MOD_ID)) {
            return false;
        }
        return resolveAccess().available;
    }

    public static Optional<String> roleNameOf(ServerPlayer player) {
        if (player == null || !ModList.get().isLoaded(WARPATTERN_MOD_ID)) {
            return Optional.empty();
        }
        Access a = resolveAccess();
        if (!a.available) {
            return Optional.empty();
        }
        try {
            Object service = a.instanceField.get(null);
            if (service == null) {
                return Optional.empty();
            }
            Object profilesObj = a.profilesField.get(service);
            if (!(profilesObj instanceof Map<?, ?> profiles)) {
                return Optional.empty();
            }
            Object profile = profiles.get(player.getUUID());
            if (profile == null) {
                return Optional.empty();
            }
            Object roleObj = a.roleField.get(profile);
            if (roleObj == null) {
                return Optional.empty();
            }
            String role = (roleObj instanceof Enum<?> e)
                    ? e.name()
                    : roleObj.toString();
            if (role == null || role.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(role.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Access resolveAccess() {
        Access local = access;
        if (local != null) {
            return local;
        }
        synchronized (WarPatternRoleCompat.class) {
            local = access;
            if (local != null) {
                return local;
            }
            access = buildAccess();
            return access;
        }
    }

    private static Access buildAccess() {
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS);
            Field instanceField = serviceClass.getField("INSTANCE");
            Field profilesField = serviceClass.getDeclaredField("profiles");
            profilesField.setAccessible(true);

            Class<?> profileClass = null;
            for (Class<?> inner : serviceClass.getDeclaredClasses()) {
                if (PROFILE_INNER_SIMPLE_NAME.equals(inner.getSimpleName())) {
                    profileClass = inner;
                    break;
                }
            }
            if (profileClass == null) {
                return Access.unavailable();
            }
            Field roleField = profileClass.getDeclaredField("role");
            roleField.setAccessible(true);
            return new Access(true, instanceField, profilesField, roleField);
        } catch (Throwable ignored) {
            return Access.unavailable();
        }
    }

    private record Access(boolean available, Field instanceField, Field profilesField, Field roleField) {
        private static Access unavailable() {
            return new Access(false, null, null, null);
        }
    }
}

