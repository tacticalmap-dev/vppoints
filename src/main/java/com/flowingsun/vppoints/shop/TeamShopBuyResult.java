package com.flowingsun.vppoints.shop;

import java.util.List;
import java.util.Objects;

/**
 * Purchase operation result with optional translated message args.
 */
public record TeamShopBuyResult(
        boolean success,
        String messageKey,
        List<String> messageArgs,
        TeamShopSnapshot snapshot
) {
    public TeamShopBuyResult {
        messageKey = Objects.requireNonNullElse(messageKey, "");
        messageArgs = List.copyOf(Objects.requireNonNullElse(messageArgs, List.of()));
    }
}

