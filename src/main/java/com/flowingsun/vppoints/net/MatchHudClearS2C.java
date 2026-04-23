package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.client.ClientPacketHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
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
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> ClientPacketHooks::handleHudClear
        ));
        ctx.get().setPacketHandled(true);
    }
}

