package com.flowingsun.vppoints.shop;

import java.util.Locale;

/**
 * Team shop currency lanes.
 */
public enum ShopCurrency {
    AMMO("ammo"),
    OIL("oil");

    private final String id;

    ShopCurrency(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ShopCurrency fromId(String raw) {
        if (raw == null) {
            return AMMO;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "oil".equals(normalized) ? OIL : AMMO;
    }
}

