package com.flowingsun.vppoints.client;

import com.flowingsun.vppoints.net.TeamShopSyncS2C;

import java.util.ArrayList;
import java.util.List;

/**
 * Client cache of latest team shop snapshot.
 */
public final class TeamShopClientState {
    public static String mapId;
    public static String teamName;
    public static boolean canApproveRequests;
    public static int ammoBalance;
    public static int oilBalance;
    public static final List<TeamShopSyncS2C.EntryView> ammoEntries = new ArrayList<>();
    public static final List<TeamShopSyncS2C.EntryView> oilEntries = new ArrayList<>();
    public static final List<TeamShopSyncS2C.ApprovalView> pendingApprovals = new ArrayList<>();

    private TeamShopClientState() {
    }

    public static void apply(TeamShopSyncS2C pkt) {
        mapId = pkt.mapId;
        teamName = pkt.teamName;
        canApproveRequests = pkt.canApproveRequests;
        ammoBalance = pkt.ammoBalance;
        oilBalance = pkt.oilBalance;
        ammoEntries.clear();
        oilEntries.clear();
        pendingApprovals.clear();
        ammoEntries.addAll(pkt.ammoEntries);
        oilEntries.addAll(pkt.oilEntries);
        pendingApprovals.addAll(pkt.pendingApprovals);
    }

    public static void clear() {
        mapId = null;
        teamName = null;
        canApproveRequests = false;
        ammoBalance = 0;
        oilBalance = 0;
        ammoEntries.clear();
        oilEntries.clear();
        pendingApprovals.clear();
    }
}
