package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.shop.ShopCurrency;
import com.flowingsun.vppoints.shop.TeamShopBuyResult;
import com.flowingsun.vppoints.shop.TeamShopService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client purchase request for one shop entry and quantity.
 */
public class BuyTeamShopEntryC2S {
    private static final int MAX_ENTRY_ID_LENGTH = 128;

    public final ShopCurrency currency;
    public final String entryId;
    public final int quantity;

    public BuyTeamShopEntryC2S(ShopCurrency currency, String entryId, int quantity) {
        this.currency = currency == null ? ShopCurrency.AMMO : currency;
        this.entryId = entryId == null ? "" : entryId;
        this.quantity = quantity;
    }

    public static void encode(BuyTeamShopEntryC2S pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.currency);
        buf.writeUtf(pkt.entryId, MAX_ENTRY_ID_LENGTH);
        buf.writeVarInt(pkt.quantity);
    }

    public static BuyTeamShopEntryC2S decode(FriendlyByteBuf buf) {
        ShopCurrency currency = buf.readEnum(ShopCurrency.class);
        String entryId = buf.readUtf(MAX_ENTRY_ID_LENGTH);
        int quantity = buf.readVarInt();
        return new BuyTeamShopEntryC2S(currency, entryId, quantity);
    }

    public static void handle(BuyTeamShopEntryC2S pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) {
                return;
            }
            TeamShopBuyResult result = TeamShopService.INSTANCE.buy(player, pkt.currency, pkt.entryId, pkt.quantity);
            Component message = Component.translatable(result.messageKey(), result.messageArgs().toArray());
            player.displayClientMessage(message, true);
            if (result.snapshot() != null) {
                SquadNetwork.sendTo(player, new TeamShopSyncS2C(result.snapshot(), false));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

