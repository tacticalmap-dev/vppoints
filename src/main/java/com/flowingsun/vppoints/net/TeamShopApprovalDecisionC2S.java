package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.shop.TeamShopBuyResult;
import com.flowingsun.vppoints.shop.TeamShopService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Commander approval/rejection action for a pending team shop request.
 */
public class TeamShopApprovalDecisionC2S {
    private static final int MAX_REQUEST_ID_LEN = 64;

    public final String requestId;
    public final boolean approve;

    public TeamShopApprovalDecisionC2S(String requestId, boolean approve) {
        this.requestId = requestId == null ? "" : requestId;
        this.approve = approve;
    }

    public static void encode(TeamShopApprovalDecisionC2S pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.requestId, MAX_REQUEST_ID_LEN);
        buf.writeBoolean(pkt.approve);
    }

    public static TeamShopApprovalDecisionC2S decode(FriendlyByteBuf buf) {
        String requestId = buf.readUtf(MAX_REQUEST_ID_LEN);
        boolean approve = buf.readBoolean();
        return new TeamShopApprovalDecisionC2S(requestId, approve);
    }

    public static void handle(TeamShopApprovalDecisionC2S pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) {
                return;
            }
            TeamShopBuyResult result = TeamShopService.INSTANCE.handleApprovalDecision(player, pkt.requestId, pkt.approve);
            player.displayClientMessage(
                    Component.translatable(result.messageKey(), result.messageArgs().toArray()),
                    true
            );
            if (result.snapshot() != null) {
                SquadNetwork.sendTo(player, new TeamShopSyncS2C(result.snapshot(), false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

