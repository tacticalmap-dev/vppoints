package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.client.ClientHudState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Lightweight packet used to clear client HUD state when match context is gone.
 */
public class MatchHudClearS2C {
    public static void encode(MatchHudClearS2C pkt, FriendlyByteBuf buf) {
        // no payload
    }

    public static MatchHudClearS2C decode(FriendlyByteBuf buf) {
        return new MatchHudClearS2C();
    }

    public static void handle(MatchHudClearS2C pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(ClientHudState::clear);
        ctx.get().setPacketHandled(true);
    }
}

