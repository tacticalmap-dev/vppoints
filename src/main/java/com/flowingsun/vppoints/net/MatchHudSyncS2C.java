package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.client.ClientHudState;
import com.flowingsun.vppoints.vp.VictoryMatchManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Periodic server-to-client HUD snapshot packet for active matches.
 */
public class MatchHudSyncS2C {
    public final String mapId;
    public final String teamA;
    public final int colorA;
    public final float pointsA;
    public final int ammoA;
    public final int oilA;
    public final String teamB;
    public final int colorB;
    public final float pointsB;
    public final int ammoB;
    public final int oilB;
    public final List<PointView> points;
    // -1: hidden, 0: show "return to map", >0: show countdown seconds.
    public final int returnToMapSeconds;

    /**
     * Minimal point state needed by the HUD.
     */
    public record PointView(BlockPos pos, float progressSigned, String ownerTeam, boolean contested,
                            boolean capturing) {
    }

    public MatchHudSyncS2C(
            String mapId,
            String teamA, int colorA, float pointsA, int ammoA, int oilA,
            String teamB, int colorB, float pointsB, int ammoB, int oilB,
            List<VictoryMatchManager.PointSnapshot> snapshots,
            int returnToMapSeconds
    ) {
        this.mapId = mapId;
        this.teamA = teamA;
        this.colorA = colorA;
        this.pointsA = pointsA;
        this.ammoA = ammoA;
        this.oilA = oilA;
        this.teamB = teamB;
        this.colorB = colorB;
        this.pointsB = pointsB;
        this.ammoB = ammoB;
        this.oilB = oilB;
        this.points = new ArrayList<>();
        for (VictoryMatchManager.PointSnapshot s : snapshots) {
            this.points.add(new PointView(BlockPos.of(s.pos), s.progress, s.ownerTeam, s.contested, s.capturing));
        }
        this.returnToMapSeconds = returnToMapSeconds;
    }

    private MatchHudSyncS2C(
            String mapId,
            String teamA, int colorA, float pointsA, int ammoA, int oilA,
            String teamB, int colorB, float pointsB, int ammoB, int oilB,
            List<PointView> points,
            int returnToMapSeconds,
            boolean internal
    ) {
        this.mapId = mapId;
        this.teamA = teamA;
        this.colorA = colorA;
        this.pointsA = pointsA;
        this.ammoA = ammoA;
        this.oilA = oilA;
        this.teamB = teamB;
        this.colorB = colorB;
        this.pointsB = pointsB;
        this.ammoB = ammoB;
        this.oilB = oilB;
        this.points = points;
        this.returnToMapSeconds = returnToMapSeconds;
    }

    public static void encode(MatchHudSyncS2C pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.mapId);
        buf.writeUtf(pkt.teamA);
        buf.writeInt(pkt.colorA);
        buf.writeFloat(pkt.pointsA);
        buf.writeVarInt(pkt.ammoA);
        buf.writeVarInt(pkt.oilA);
        buf.writeUtf(pkt.teamB);
        buf.writeInt(pkt.colorB);
        buf.writeFloat(pkt.pointsB);
        buf.writeVarInt(pkt.ammoB);
        buf.writeVarInt(pkt.oilB);
        buf.writeVarInt(pkt.points.size());
        for (PointView p : pkt.points) {
            buf.writeBlockPos(p.pos);
            buf.writeFloat(p.progressSigned);
            // Owner is almost always null/teamA/teamB, so encode as tiny enum first.
            String owner = p.ownerTeam;
            if (owner == null) {
                buf.writeByte(0);
            } else if (owner.equals(pkt.teamA)) {
                buf.writeByte(1);
            } else if (owner.equals(pkt.teamB)) {
                buf.writeByte(2);
            } else {
                buf.writeByte(3);
                buf.writeUtf(owner);
            }

            int flags = 0;
            if (p.contested) {
                flags |= 1;
            }
            if (p.capturing) {
                flags |= 1 << 1;
            }
            buf.writeByte(flags);
        }
        // Shift by +1 so -1 can also be packed as VarInt.
        buf.writeVarInt(pkt.returnToMapSeconds + 1);
    }

    public static MatchHudSyncS2C decode(FriendlyByteBuf buf) {
        String mapId = buf.readUtf();
        String teamA = buf.readUtf();
        int colorA = buf.readInt();
        float pointsA = buf.readFloat();
        int ammoA = buf.readVarInt();
        int oilA = buf.readVarInt();
        String teamB = buf.readUtf();
        int colorB = buf.readInt();
        float pointsB = buf.readFloat();
        int ammoB = buf.readVarInt();
        int oilB = buf.readVarInt();
        int size = buf.readVarInt();
        List<PointView> points = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            float progress = buf.readFloat();
            int ownerCode = buf.readByte() & 0xFF;
            String owner = switch (ownerCode) {
                case 0 -> null;
                case 1 -> teamA;
                case 2 -> teamB;
                case 3 -> buf.readUtf();
                default -> null;
            };
            int flags = buf.readByte() & 0xFF;
            boolean contested = (flags & 1) != 0;
            boolean capturing = (flags & (1 << 1)) != 0;
            points.add(new PointView(pos, progress, owner, contested, capturing));
        }
        int returnToMapSeconds = buf.readVarInt() - 1;
        return new MatchHudSyncS2C(mapId, teamA, colorA, pointsA, ammoA, oilA, teamB, colorB, pointsB, ammoB, oilB, points, returnToMapSeconds, true);
    }

    public static void handle(MatchHudSyncS2C pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientHudState.apply(pkt));
        ctx.get().setPacketHandled(true);
    }
}

