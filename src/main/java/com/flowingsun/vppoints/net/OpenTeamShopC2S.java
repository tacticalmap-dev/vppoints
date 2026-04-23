package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.shop.TeamShopService;
import com.flowingsun.vppoints.shop.TeamShopSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client asks server to open the current team shop screen.
 */
public class OpenTeamShopC2S {
    public static void encode(OpenTeamShopC2S pkt, FriendlyByteBuf buf) {
        // no payload
    }

    public static OpenTeamShopC2S decode(FriendlyByteBuf buf) {
        return new OpenTeamShopC2S();
    }

    public static void handle(OpenTeamShopC2S pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) {
                return;
            }
            TeamShopSnapshot snapshot = TeamShopService.INSTANCE.snapshotFor(player).orElse(null);
            if (snapshot == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.vppoints.shop.not_in_match"), true);
                return;
            }
            SquadNetwork.sendTo(player, new TeamShopSyncS2C(snapshot, true));
        });
        ctx.get().setPacketHandled(true);
    }
}

