package com.flowingsun.vppoints.shop;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.integration.WarPatternRoleCompat;
import com.flowingsun.vppoints.match.SquadMatchService;
import com.flowingsun.vppoints.net.SquadNetwork;
import com.flowingsun.vppoints.net.TeamShopSyncS2C;
import com.flowingsun.vppoints.vp.VictoryMatchManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Team shop runtime logic: snapshot build, purchase checks, commander approval queue, and spending.
 */
public final class TeamShopService {
    public static final TeamShopService INSTANCE = new TeamShopService();

    private final Map<String, List<PendingApproval>> pendingApprovalsByTeam = new ConcurrentHashMap<>();
    private final AtomicLong requestSeq = new AtomicLong();

    private TeamShopService() {
    }

    public void onMatchEnded(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return;
        }
        String prefix = mapId.trim().toLowerCase(Locale.ROOT) + "|";
        pendingApprovalsByTeam.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public Optional<TeamShopSnapshot> snapshotFor(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        Optional<SquadMatchService.PlayerMatchContext> context = SquadMatchService.INSTANCE.contextFor(player);
        if (context.isEmpty()) {
            return Optional.empty();
        }
        String mapId = context.get().mapId();
        String teamName = context.get().playerTeam();
        Optional<VictoryMatchManager.TeamResourceView> resource = VictoryMatchManager.INSTANCE.resourceOf(mapId, teamName);
        if (resource.isEmpty()) {
            return Optional.empty();
        }

        List<TeamShopEntry> ammoEntries = TeamShopStorage.INSTANCE.loadEntries(player.server, mapId, teamName, ShopCurrency.AMMO);
        List<TeamShopEntry> oilEntries = TeamShopStorage.INSTANCE.loadEntries(player.server, mapId, teamName, ShopCurrency.OIL);
        boolean commander = isCommander(player).isPresent();
        List<TeamShopApprovalView> approvals = commander ? pendingViews(mapId, teamName) : List.of();
        return Optional.of(new TeamShopSnapshot(
                mapId,
                teamName,
                commander,
                resource.get().ammo(),
                resource.get().oil(),
                ammoEntries,
                oilEntries,
                approvals
        ));
    }

    public TeamShopBuyResult buy(ServerPlayer player, ShopCurrency currency, String entryId, int requestedQuantity) {
        Optional<SquadMatchService.PlayerMatchContext> context = SquadMatchService.INSTANCE.contextFor(player);
        if (context.isEmpty()) {
            return fail("message.vppoints.shop.not_in_match");
        }
        String mapId = context.get().mapId();
        String teamName = context.get().playerTeam();

        int quantity = clampQuantity(requestedQuantity);
        if (quantity <= 0) {
            return fail("message.vppoints.shop.invalid_quantity");
        }

        List<TeamShopEntry> entries = TeamShopStorage.INSTANCE.loadEntries(player.server, mapId, teamName, currency);
        TeamShopEntry entry = findEntry(entries, entryId);
        if (entry == null) {
            return fail("message.vppoints.shop.invalid_entry");
        }

        RoleCheck roleCheck = resolveRoleCheck(player, entry);
        if (roleCheck.failResult != null) {
            return roleCheck.failResult;
        }

        if (!entry.allowedRoles().isEmpty() && !roleCheck.bypassRoleRestrictions) {
            String roleName = roleCheck.roleName.orElse("");
            if (!entry.allowedRoles().contains(roleName)) {
                String joined = String.join(", ", entry.allowedRoles());
                return fail("message.vppoints.shop.role_restricted", joined);
            }
        }

        if (entry.requireCommanderApproval() && !roleCheck.bypassRoleRestrictions && !roleCheck.commander) {
            TeamShopBuyResult submitted = submitApprovalRequest(player, mapId, teamName, currency, entry, quantity);
            if (submitted.success()) {
                syncCommandersForTeam(player.server, mapId, teamName, true);
            }
            return submitted;
        }

        TeamShopBuyResult purchased = executePurchase(player, mapId, teamName, currency, entry, quantity);
        if (purchased.success()) {
            syncTeamPlayers(player.server, mapId, teamName);
        }
        return purchased;
    }

    public TeamShopBuyResult handleApprovalDecision(ServerPlayer commander, String requestId, boolean approve) {
        Optional<SquadMatchService.PlayerMatchContext> context = SquadMatchService.INSTANCE.contextFor(commander);
        if (context.isEmpty()) {
            return fail("message.vppoints.shop.not_in_match");
        }
        String mapId = context.get().mapId();
        String teamName = context.get().playerTeam();

        Optional<String> role = WarPatternRoleCompat.roleNameOf(commander);
        if (role.isEmpty()) {
            if (SquadConfig.SHOP_REQUIRE_WARPATTERN_FOR_ROLE_CHECKS.get()) {
                return fail("message.vppoints.shop.warpattern_required");
            }
            return fail("message.vppoints.shop.approval_only_commander");
        }
        if (!"COMMANDER".equals(role.get())) {
            return fail("message.vppoints.shop.approval_only_commander");
        }

        PendingApproval request = findPendingApproval(mapId, teamName, requestId);
        if (request == null) {
            return fail("message.vppoints.shop.approval_not_found");
        }

        if (!approve) {
            removePendingApproval(mapId, teamName, request.requestId);
            notifyRequester(commander.server, request, "message.vppoints.shop.request_rejected", commander.getGameProfile().getName());
            syncCommandersForTeam(commander.server, mapId, teamName, false);
            return successWithSnapshot(commander, "message.vppoints.shop.approval_rejected");
        }

        ServerPlayer requester = commander.server.getPlayerList().getPlayer(request.requesterId);
        if (requester == null) {
            removePendingApproval(mapId, teamName, request.requestId);
            syncCommandersForTeam(commander.server, mapId, teamName, false);
            return failWithSnapshot(commander, "message.vppoints.shop.requester_offline");
        }

        Optional<SquadMatchService.PlayerMatchContext> requesterContext = SquadMatchService.INSTANCE.contextFor(requester);
        if (requesterContext.isEmpty()
                || !mapId.equals(requesterContext.get().mapId())
                || !teamName.equals(requesterContext.get().playerTeam())) {
            removePendingApproval(mapId, teamName, request.requestId);
            syncCommandersForTeam(commander.server, mapId, teamName, false);
            return failWithSnapshot(commander, "message.vppoints.shop.requester_left_match");
        }

        List<TeamShopEntry> entries = TeamShopStorage.INSTANCE.loadEntries(requester.server, mapId, teamName, request.currency);
        TeamShopEntry entry = findEntry(entries, request.entryId);
        if (entry == null) {
            removePendingApproval(mapId, teamName, request.requestId);
            syncCommandersForTeam(commander.server, mapId, teamName, false);
            return failWithSnapshot(commander, "message.vppoints.shop.invalid_entry");
        }

        RoleCheck requesterRole = resolveRoleCheck(requester, entry);
        if (requesterRole.failResult != null) {
            removePendingApproval(mapId, teamName, request.requestId);
            syncCommandersForTeam(commander.server, mapId, teamName, false);
            return failWithSnapshot(commander, requesterRole.failResult.messageKey(), requesterRole.failResult.messageArgs().toArray(new String[0]));
        }
        if (!entry.allowedRoles().isEmpty() && !requesterRole.bypassRoleRestrictions) {
            String roleName = requesterRole.roleName.orElse("");
            if (!entry.allowedRoles().contains(roleName)) {
                String joined = String.join(", ", entry.allowedRoles());
                removePendingApproval(mapId, teamName, request.requestId);
                syncCommandersForTeam(commander.server, mapId, teamName, false);
                return failWithSnapshot(commander, "message.vppoints.shop.role_restricted", joined);
            }
        }

        TeamShopBuyResult bought = executePurchase(requester, mapId, teamName, request.currency, entry, request.quantity);
        if (bought.success()) {
            removePendingApproval(mapId, teamName, request.requestId);
            requester.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.vppoints.shop.request_approved",
                            commander.getGameProfile().getName()),
                    true
            );
            syncTeamPlayers(commander.server, mapId, teamName);
            return successWithSnapshot(commander, "message.vppoints.shop.approval_done", requester.getGameProfile().getName());
        }

        syncCommandersForTeam(commander.server, mapId, teamName, false);
        return failWithSnapshot(commander, bought.messageKey(), bought.messageArgs().toArray(new String[0]));
    }

    private TeamShopBuyResult submitApprovalRequest(
            ServerPlayer requester,
            String mapId,
            String teamName,
            ShopCurrency currency,
            TeamShopEntry entry,
            int quantity
    ) {
        if (!hasOnlineCommander(requester.server, mapId, teamName)) {
            return failWithSnapshot(requester, "message.vppoints.shop.no_commander_online");
        }

        String teamKey = teamKey(mapId, teamName);
        List<PendingApproval> pending = pendingApprovalsByTeam.computeIfAbsent(teamKey, ignored -> new ArrayList<>());
        synchronized (pending) {
            for (PendingApproval row : pending) {
                if (row.requesterId.equals(requester.getUUID())
                        && row.currency == currency
                        && row.entryId.equals(entry.id())) {
                    return failWithSnapshot(requester, "message.vppoints.shop.request_already_pending");
                }
            }
            String requestId = "REQ-" + requestSeq.incrementAndGet();
            pending.add(new PendingApproval(
                    requestId,
                    requester.getUUID(),
                    requester.getGameProfile().getName(),
                    currency,
                    entry.id(),
                    entry.displayName().isBlank() ? entry.itemId() : entry.displayName(),
                    quantity,
                    System.currentTimeMillis()
            ));
            pending.sort(Comparator.comparingLong(p -> p.createdAtEpochMs));
        }

        notifyCommanders(requester.server, mapId, teamName, requester.getGameProfile().getName(), entry, quantity);
        return successWithSnapshot(requester, "message.vppoints.shop.request_submitted");
    }

    private TeamShopBuyResult executePurchase(
            ServerPlayer player,
            String mapId,
            String teamName,
            ShopCurrency currency,
            TeamShopEntry entry,
            int quantity
    ) {
        Optional<VictoryMatchManager.TeamResourceView> resource = VictoryMatchManager.INSTANCE.resourceOf(mapId, teamName);
        if (resource.isEmpty()) {
            return failWithSnapshot(player, "message.vppoints.shop.not_in_match");
        }

        long totalCostLong = (long) entry.unitPrice() * (long) quantity;
        if (totalCostLong > Integer.MAX_VALUE) {
            return failWithSnapshot(player, "message.vppoints.shop.invalid_quantity");
        }
        int totalCost = (int) totalCostLong;

        boolean enough = currency == ShopCurrency.OIL
                ? resource.get().oil() >= totalCost
                : resource.get().ammo() >= totalCost;
        if (!enough) {
            return failWithSnapshot(player, "message.vppoints.shop.insufficient_funds");
        }

        boolean deducted = spend(mapId, teamName, currency, totalCost);
        if (!deducted) {
            return failWithSnapshot(player, "message.vppoints.shop.spend_failed");
        }

        ResourceLocation itemKey = ResourceLocation.tryParse(entry.itemId());
        Item item = itemKey == null ? null : ForgeRegistries.ITEMS.getValue(itemKey);
        if (item == null || item == Items.AIR) {
            spend(mapId, teamName, currency, -totalCost);
            return failWithSnapshot(player, "message.vppoints.shop.invalid_item");
        }

        long totalItemCountLong = (long) entry.itemCount() * (long) quantity;
        int totalItemCount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, totalItemCountLong));
        giveItemToPlayer(player, item, totalItemCount);

        List<String> args = List.of(
                Integer.toString(quantity),
                entry.displayName().isBlank() ? entry.itemId() : entry.displayName()
        );
        TeamShopSnapshot snapshot = snapshotFor(player).orElse(null);
        return new TeamShopBuyResult(true, "message.vppoints.shop.buy_success", args, snapshot);
    }

    private Optional<String> isCommander(ServerPlayer player) {
        return WarPatternRoleCompat.roleNameOf(player).filter("COMMANDER"::equals);
    }

    private RoleCheck resolveRoleCheck(ServerPlayer player, TeamShopEntry entry) {
        boolean needRoleChecks = entry.requireCommanderApproval() || !entry.allowedRoles().isEmpty();
        if (!needRoleChecks) {
            return new RoleCheck(Optional.empty(), false, false, null);
        }

        Optional<String> role = WarPatternRoleCompat.roleNameOf(player);
        boolean strict = SquadConfig.SHOP_REQUIRE_WARPATTERN_FOR_ROLE_CHECKS.get();
        if (role.isEmpty()) {
            if (strict) {
                return new RoleCheck(Optional.empty(), false, false, fail("message.vppoints.shop.warpattern_required"));
            }
            return new RoleCheck(Optional.empty(), true, false, null);
        }
        String normalized = role.get().trim().toUpperCase(Locale.ROOT);
        return new RoleCheck(Optional.of(normalized), false, "COMMANDER".equals(normalized), null);
    }

    private List<TeamShopApprovalView> pendingViews(String mapId, String teamName) {
        String key = teamKey(mapId, teamName);
        List<PendingApproval> pending = pendingApprovalsByTeam.get(key);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        synchronized (pending) {
            if (pending.isEmpty()) {
                return List.of();
            }
            List<TeamShopApprovalView> out = new ArrayList<>();
            for (PendingApproval row : pending) {
                out.add(new TeamShopApprovalView(
                        row.requestId,
                        row.requesterName,
                        row.currency,
                        row.entryId,
                        row.entryDisplayName,
                        row.quantity,
                        row.createdAtEpochMs
                ));
            }
            return List.copyOf(out);
        }
    }

    private PendingApproval findPendingApproval(String mapId, String teamName, String requestId) {
        String key = teamKey(mapId, teamName);
        List<PendingApproval> pending = pendingApprovalsByTeam.get(key);
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        String wanted = requestId == null ? "" : requestId.trim();
        synchronized (pending) {
            for (PendingApproval row : pending) {
                if (row.requestId.equals(wanted)) {
                    return row;
                }
            }
        }
        return null;
    }

    private void removePendingApproval(String mapId, String teamName, String requestId) {
        String key = teamKey(mapId, teamName);
        List<PendingApproval> pending = pendingApprovalsByTeam.get(key);
        if (pending == null) {
            return;
        }
        synchronized (pending) {
            pending.removeIf(row -> row.requestId.equals(requestId));
            if (pending.isEmpty()) {
                pendingApprovalsByTeam.remove(key);
            }
        }
    }

    private boolean hasOnlineCommander(MinecraftServer server, String mapId, String teamName) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Optional<SquadMatchService.PlayerMatchContext> ctx = SquadMatchService.INSTANCE.contextFor(online);
            if (ctx.isEmpty()) {
                continue;
            }
            if (!mapId.equals(ctx.get().mapId()) || !teamName.equals(ctx.get().playerTeam())) {
                continue;
            }
            if (isCommander(online).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void notifyCommanders(
            MinecraftServer server,
            String mapId,
            String teamName,
            String requesterName,
            TeamShopEntry entry,
            int quantity
    ) {
        String entryName = entry.displayName().isBlank() ? entry.itemId() : entry.displayName();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Optional<SquadMatchService.PlayerMatchContext> ctx = SquadMatchService.INSTANCE.contextFor(online);
            if (ctx.isEmpty()) {
                continue;
            }
            if (!mapId.equals(ctx.get().mapId()) || !teamName.equals(ctx.get().playerTeam())) {
                continue;
            }
            if (isCommander(online).isEmpty()) {
                continue;
            }
            online.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.vppoints.shop.new_request",
                            requesterName,
                            quantity,
                            entryName),
                    true
            );
        }
    }

    private void notifyRequester(MinecraftServer server, PendingApproval request, String key, String... args) {
        ServerPlayer requester = server.getPlayerList().getPlayer(request.requesterId);
        if (requester == null) {
            return;
        }
        requester.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(key, (Object[]) args),
                true
        );
    }

    private void syncCommandersForTeam(MinecraftServer server, String mapId, String teamName, boolean openScreen) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Optional<SquadMatchService.PlayerMatchContext> ctx = SquadMatchService.INSTANCE.contextFor(online);
            if (ctx.isEmpty()) {
                continue;
            }
            if (!mapId.equals(ctx.get().mapId()) || !teamName.equals(ctx.get().playerTeam())) {
                continue;
            }
            if (isCommander(online).isEmpty()) {
                continue;
            }
            TeamShopSnapshot snapshot = snapshotFor(online).orElse(null);
            if (snapshot != null) {
                SquadNetwork.sendTo(online, new TeamShopSyncS2C(snapshot, openScreen));
            }
        }
    }

    private void syncTeamPlayers(MinecraftServer server, String mapId, String teamName) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Optional<SquadMatchService.PlayerMatchContext> ctx = SquadMatchService.INSTANCE.contextFor(online);
            if (ctx.isEmpty()) {
                continue;
            }
            if (!mapId.equals(ctx.get().mapId()) || !teamName.equals(ctx.get().playerTeam())) {
                continue;
            }
            TeamShopSnapshot snapshot = snapshotFor(online).orElse(null);
            if (snapshot != null) {
                SquadNetwork.sendTo(online, new TeamShopSyncS2C(snapshot, false));
            }
        }
    }

    private static TeamShopEntry findEntry(List<TeamShopEntry> entries, String entryId) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String wanted = entryId == null ? "" : entryId.trim();
        for (TeamShopEntry entry : entries) {
            if (entry.id().equals(wanted)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean spend(String mapId, String teamName, ShopCurrency currency, int amount) {
        int ammoDelta = currency == ShopCurrency.AMMO ? -amount : 0;
        int oilDelta = currency == ShopCurrency.OIL ? -amount : 0;
        return VictoryMatchManager.INSTANCE.adjustTeamResources(mapId, teamName, 0F, ammoDelta, oilDelta);
    }

    private static void giveItemToPlayer(ServerPlayer player, Item item, int count) {
        int remaining = Math.max(1, count);
        int maxStack = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            int stackCount = Math.min(maxStack, remaining);
            ItemStack stack = new ItemStack(item, stackCount);
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= stackCount;
        }
    }

    private static int clampQuantity(int quantity) {
        int max = Math.max(1, SquadConfig.SHOP_MAX_PURCHASE_QUANTITY.get());
        if (quantity < 1) {
            return 1;
        }
        return Math.min(quantity, max);
    }

    private static String teamKey(String mapId, String teamName) {
        return mapId.trim().toLowerCase(Locale.ROOT) + "|" + teamName.trim().toLowerCase(Locale.ROOT);
    }

    private static TeamShopBuyResult successWithSnapshot(ServerPlayer player, String key, String... args) {
        List<String> list = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                list.add(arg == null ? "" : arg);
            }
        }
        TeamShopSnapshot snapshot = INSTANCE.snapshotFor(player).orElse(null);
        return new TeamShopBuyResult(true, key, list, snapshot);
    }

    private static TeamShopBuyResult failWithSnapshot(ServerPlayer player, String key, String... args) {
        List<String> list = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                list.add(arg == null ? "" : arg);
            }
        }
        TeamShopSnapshot snapshot = INSTANCE.snapshotFor(player).orElse(null);
        return new TeamShopBuyResult(false, key, list, snapshot);
    }

    private static TeamShopBuyResult fail(String key, String... args) {
        List<String> list = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                list.add(arg == null ? "" : arg);
            }
        }
        return new TeamShopBuyResult(false, key, list, null);
    }

    private record PendingApproval(
            String requestId,
            UUID requesterId,
            String requesterName,
            ShopCurrency currency,
            String entryId,
            String entryDisplayName,
            int quantity,
            long createdAtEpochMs
    ) {
    }

    private record RoleCheck(
            Optional<String> roleName,
            boolean bypassRoleRestrictions,
            boolean commander,
            TeamShopBuyResult failResult
    ) {
    }
}
