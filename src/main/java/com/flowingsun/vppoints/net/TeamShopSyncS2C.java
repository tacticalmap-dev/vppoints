package com.flowingsun.vppoints.net;

import com.flowingsun.vppoints.client.ClientPacketHooks;
import com.flowingsun.vppoints.shop.TeamShopApprovalView;
import com.flowingsun.vppoints.shop.TeamShopEntry;
import com.flowingsun.vppoints.shop.TeamShopSnapshot;
import com.flowingsun.vppoints.shop.ShopCurrency;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server-to-client full shop snapshot sync.
 */
public class TeamShopSyncS2C {
    private static final int MAX_ID_LEN = 128;
    private static final int MAX_ITEM_LEN = 128;
    private static final int MAX_TEXT_LEN = 2048;
    private static final int MAX_ROLE_LEN = 64;

    public final String mapId;
    public final String teamName;
    public final boolean canApproveRequests;
    public final int ammoBalance;
    public final int oilBalance;
    public final List<EntryView> ammoEntries;
    public final List<EntryView> oilEntries;
    public final List<ApprovalView> pendingApprovals;
    public final boolean openScreen;

    public record EntryView(
            String id,
            String itemId,
            int itemCount,
            int unitPrice,
            String displayName,
            String description,
            boolean requireCommanderApproval,
            List<String> allowedRoles
    ) {
    }

    public record ApprovalView(
            String requestId,
            String requesterName,
            ShopCurrency currency,
            String entryId,
            String entryDisplayName,
            int quantity,
            long createdAtEpochMs
    ) {
    }

    public TeamShopSyncS2C(TeamShopSnapshot snapshot, boolean openScreen) {
        this.mapId = snapshot.mapId();
        this.teamName = snapshot.teamName();
        this.canApproveRequests = snapshot.canApproveRequests();
        this.ammoBalance = snapshot.ammoBalance();
        this.oilBalance = snapshot.oilBalance();
        this.ammoEntries = toViews(snapshot.ammoEntries());
        this.oilEntries = toViews(snapshot.oilEntries());
        this.pendingApprovals = toApprovalViews(snapshot.pendingApprovals());
        this.openScreen = openScreen;
    }

    private TeamShopSyncS2C(
            String mapId,
            String teamName,
            boolean canApproveRequests,
            int ammoBalance,
            int oilBalance,
            List<EntryView> ammoEntries,
            List<EntryView> oilEntries,
            List<ApprovalView> pendingApprovals,
            boolean openScreen
    ) {
        this.mapId = mapId;
        this.teamName = teamName;
        this.canApproveRequests = canApproveRequests;
        this.ammoBalance = ammoBalance;
        this.oilBalance = oilBalance;
        this.ammoEntries = ammoEntries;
        this.oilEntries = oilEntries;
        this.pendingApprovals = pendingApprovals;
        this.openScreen = openScreen;
    }

    public static void encode(TeamShopSyncS2C pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.mapId, MAX_TEXT_LEN);
        buf.writeUtf(pkt.teamName, MAX_TEXT_LEN);
        buf.writeBoolean(pkt.canApproveRequests);
        buf.writeVarInt(pkt.ammoBalance);
        buf.writeVarInt(pkt.oilBalance);
        writeEntries(buf, pkt.ammoEntries);
        writeEntries(buf, pkt.oilEntries);
        writeApprovals(buf, pkt.pendingApprovals);
        buf.writeBoolean(pkt.openScreen);
    }

    public static TeamShopSyncS2C decode(FriendlyByteBuf buf) {
        String mapId = buf.readUtf(MAX_TEXT_LEN);
        String teamName = buf.readUtf(MAX_TEXT_LEN);
        boolean canApproveRequests = buf.readBoolean();
        int ammoBalance = buf.readVarInt();
        int oilBalance = buf.readVarInt();
        List<EntryView> ammoEntries = readEntries(buf);
        List<EntryView> oilEntries = readEntries(buf);
        List<ApprovalView> pendingApprovals = readApprovals(buf);
        boolean openScreen = buf.readBoolean();
        return new TeamShopSyncS2C(
                mapId,
                teamName,
                canApproveRequests,
                ammoBalance,
                oilBalance,
                ammoEntries,
                oilEntries,
                pendingApprovals,
                openScreen
        );
    }

    public static void handle(TeamShopSyncS2C pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPacketHooks.handleTeamShopSync(pkt)
        ));
        ctx.get().setPacketHandled(true);
    }

    private static void writeEntries(FriendlyByteBuf buf, List<EntryView> entries) {
        buf.writeVarInt(entries.size());
        for (EntryView row : entries) {
            buf.writeUtf(row.id, MAX_ID_LEN);
            buf.writeUtf(row.itemId, MAX_ITEM_LEN);
            buf.writeVarInt(row.itemCount);
            buf.writeVarInt(row.unitPrice);
            buf.writeUtf(row.displayName, MAX_TEXT_LEN);
            buf.writeUtf(row.description, MAX_TEXT_LEN);
            buf.writeBoolean(row.requireCommanderApproval);
            buf.writeVarInt(row.allowedRoles.size());
            for (String role : row.allowedRoles) {
                buf.writeUtf(role, MAX_ROLE_LEN);
            }
        }
    }

    private static List<EntryView> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<EntryView> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf(MAX_ID_LEN);
            String itemId = buf.readUtf(MAX_ITEM_LEN);
            int itemCount = buf.readVarInt();
            int unitPrice = buf.readVarInt();
            String displayName = buf.readUtf(MAX_TEXT_LEN);
            String description = buf.readUtf(MAX_TEXT_LEN);
            boolean requireCommanderApproval = buf.readBoolean();
            int roleCount = buf.readVarInt();
            List<String> roles = new ArrayList<>();
            for (int r = 0; r < roleCount; r++) {
                roles.add(buf.readUtf(MAX_ROLE_LEN));
            }
            out.add(new EntryView(
                    id,
                    itemId,
                    itemCount,
                    unitPrice,
                    displayName,
                    description,
                    requireCommanderApproval,
                    roles
            ));
        }
        return out;
    }

    private static List<EntryView> toViews(List<TeamShopEntry> entries) {
        List<EntryView> out = new ArrayList<>();
        for (TeamShopEntry row : entries) {
            out.add(new EntryView(
                    row.id(),
                    row.itemId(),
                    row.itemCount(),
                    row.unitPrice(),
                    row.displayName(),
                    row.description(),
                    row.requireCommanderApproval(),
                    row.allowedRoles()
            ));
        }
        return out;
    }

    private static void writeApprovals(FriendlyByteBuf buf, List<ApprovalView> approvals) {
        buf.writeVarInt(approvals.size());
        for (ApprovalView row : approvals) {
            buf.writeUtf(row.requestId, MAX_ID_LEN);
            buf.writeUtf(row.requesterName, MAX_TEXT_LEN);
            buf.writeEnum(row.currency);
            buf.writeUtf(row.entryId, MAX_ID_LEN);
            buf.writeUtf(row.entryDisplayName, MAX_TEXT_LEN);
            buf.writeVarInt(row.quantity);
            buf.writeVarLong(row.createdAtEpochMs);
        }
    }

    private static List<ApprovalView> readApprovals(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ApprovalView> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String requestId = buf.readUtf(MAX_ID_LEN);
            String requesterName = buf.readUtf(MAX_TEXT_LEN);
            ShopCurrency currency = buf.readEnum(ShopCurrency.class);
            String entryId = buf.readUtf(MAX_ID_LEN);
            String entryDisplayName = buf.readUtf(MAX_TEXT_LEN);
            int quantity = buf.readVarInt();
            long createdAtEpochMs = buf.readVarLong();
            out.add(new ApprovalView(
                    requestId,
                    requesterName,
                    currency,
                    entryId,
                    entryDisplayName,
                    quantity,
                    createdAtEpochMs
            ));
        }
        return out;
    }

    private static List<ApprovalView> toApprovalViews(List<TeamShopApprovalView> approvals) {
        List<ApprovalView> out = new ArrayList<>();
        for (TeamShopApprovalView row : approvals) {
            out.add(new ApprovalView(
                    row.requestId(),
                    row.requesterName(),
                    row.currency(),
                    row.entryId(),
                    row.entryDisplayName(),
                    row.quantity(),
                    row.createdAtEpochMs()
            ));
        }
        return out;
    }
}
