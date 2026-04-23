package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.VpPointsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Network channel registration and small helper for per-player sends.
 */
public final class SquadNetwork {

    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VpPointsMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void init() {
        // Keep discriminator order stable for protocol compatibility.
        int id = 0;
        CHANNEL.registerMessage(
                id++,
                MatchHudSyncS2C.class,
                MatchHudSyncS2C::encode,
                MatchHudSyncS2C::decode,
                MatchHudSyncS2C::handle,
                Optional.of(OptionalDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                MatchHudClearS2C.class,
                MatchHudClearS2C::encode,
                MatchHudClearS2C::decode,
                MatchHudClearS2C::handle,
                Optional.of(OptionalDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                OpenTeamShopC2S.class,
                OpenTeamShopC2S::encode,
                OpenTeamShopC2S::decode,
                OpenTeamShopC2S::handle,
                Optional.of(OptionalDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                id++,
                BuyTeamShopEntryC2S.class,
                BuyTeamShopEntryC2S::encode,
                BuyTeamShopEntryC2S::decode,
                BuyTeamShopEntryC2S::handle,
                Optional.of(OptionalDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                id++,
                TeamShopApprovalDecisionC2S.class,
                TeamShopApprovalDecisionC2S::encode,
                TeamShopApprovalDecisionC2S::decode,
                TeamShopApprovalDecisionC2S::handle,
                Optional.of(OptionalDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                id++,
                TeamShopSyncS2C.class,
                TeamShopSyncS2C::encode,
                TeamShopSyncS2C::decode,
                TeamShopSyncS2C::handle,
                Optional.of(OptionalDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static final class OptionalDirection {
        private static final NetworkDirection PLAY_TO_CLIENT = NetworkDirection.PLAY_TO_CLIENT;
        private static final NetworkDirection PLAY_TO_SERVER = NetworkDirection.PLAY_TO_SERVER;
    }
}


