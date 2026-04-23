package com.flowingsun.vppoints.shop;

import java.util.Objects;

/**
 * One pending commander approval request in team shop queue.
 */
public record TeamShopApprovalView(
        String requestId,
        String requesterName,
        ShopCurrency currency,
        String entryId,
        String entryDisplayName,
        int quantity,
        long createdAtEpochMs
) {
    public TeamShopApprovalView {
        requestId = Objects.requireNonNullElse(requestId, "");
        requesterName = Objects.requireNonNullElse(requesterName, "");
        currency = currency == null ? ShopCurrency.AMMO : currency;
        entryId = Objects.requireNonNullElse(entryId, "");
        entryDisplayName = Objects.requireNonNullElse(entryDisplayName, "");
        quantity = Math.max(1, quantity);
        createdAtEpochMs = Math.max(0L, createdAtEpochMs);
    }
}

