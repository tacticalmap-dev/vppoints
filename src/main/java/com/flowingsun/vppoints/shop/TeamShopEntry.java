package com.flowingsun.vppoints.shop;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One buyable item row in one team shop file.
 */
public record TeamShopEntry(
        String id,
        String itemId,
        int itemCount,
        int unitPrice,
        String displayName,
        String description,
        boolean requireCommanderApproval,
        List<String> allowedRoles
) {
    public TeamShopEntry {
        id = Objects.requireNonNullElse(id, "").trim();
        itemId = Objects.requireNonNullElse(itemId, "").trim();
        itemCount = Math.max(1, itemCount);
        unitPrice = Math.max(0, unitPrice);
        displayName = Objects.requireNonNullElse(displayName, "").trim();
        description = Objects.requireNonNullElse(description, "").trim();
        allowedRoles = normalizeRoles(allowedRoles);
    }

    private static List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<String> out = roles.stream()
                .filter(Objects::nonNull)
                .map(text -> text.trim().toUpperCase(Locale.ROOT))
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
        return Collections.unmodifiableList(out);
    }
}

