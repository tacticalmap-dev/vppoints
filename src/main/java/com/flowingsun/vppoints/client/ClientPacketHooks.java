package com.flowingsun.vppoints.client;

import com.flowingsun.vppoints.net.TeamShopSyncS2C;
import net.minecraft.client.Minecraft;

/**
 * Client-only packet side effects.
 */
public final class ClientPacketHooks {
    private ClientPacketHooks() {
    }

    public static void handleTeamShopSync(TeamShopSyncS2C pkt) {
        TeamShopClientState.apply(pkt);
        Minecraft mc = Minecraft.getInstance();
        if (pkt.openScreen && mc.player != null && !(mc.screen instanceof TeamShopScreen)) {
            mc.setScreen(new TeamShopScreen());
        }
    }

    public static void handleHudClear() {
        ClientHudState.clear();
        TeamShopClientState.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TeamShopScreen) {
            mc.setScreen(null);
        }
    }
}

