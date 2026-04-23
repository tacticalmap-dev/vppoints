package com.flowingsun.vppoints.shop;

import java.util.List;
import java.util.Objects;

/**
 * Full shop data snapshot pushed to one client.
 */
public record TeamShopSnapshot(
        String mapId,
        String teamName,
        boolean canApproveRequests,
        int ammoBalance,
        int oilBalance,
        List<TeamShopEntry> ammoEntries,
        List<TeamShopEntry> oilEntries,
        List<TeamShopApprovalView> pendingApprovals
) {
    public TeamShopSnapshot {
        mapId = Objects.requireNonNullElse(mapId, "");
        teamName = Objects.requireNonNullElse(teamName, "");
        canApproveRequests = canApproveRequests;
        ammoBalance = Math.max(0, ammoBalance);
        oilBalance = Math.max(0, oilBalance);
        ammoEntries = List.copyOf(Objects.requireNonNullElse(ammoEntries, List.of()));
        oilEntries = List.copyOf(Objects.requireNonNullElse(oilEntries, List.of()));
        pendingApprovals = List.copyOf(Objects.requireNonNullElse(pendingApprovals, List.of()));
    }
}
