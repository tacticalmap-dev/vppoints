package com.flowingsun.vppoints.client;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.net.BuyTeamShopEntryC2S;
import com.flowingsun.vppoints.net.SquadNetwork;
import com.flowingsun.vppoints.net.TeamShopApprovalDecisionC2S;
import com.flowingsun.vppoints.net.TeamShopSyncS2C;
import com.flowingsun.vppoints.shop.ShopCurrency;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight in-game team shop screen with commander approval queue panel.
 */
public class TeamShopScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 262;
    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 6;

    private static final int APPROVAL_ROW_HEIGHT = 18;
    private static final int APPROVAL_VISIBLE_ROWS = 3;

    private static ShopCurrency lastCurrency = ShopCurrency.AMMO;

    private EditBox searchBox;
    private EditBox quantityBox;
    private Button tabAmmoButton;
    private Button tabOilButton;
    private Button buyButton;
    private Button plusButton;
    private Button minusButton;
    private Button setDefaultQuantityButton;
    private Button approveButton;
    private Button rejectButton;

    private ShopCurrency currentCurrency = lastCurrency;
    private final List<TeamShopSyncS2C.EntryView> filteredEntries = new ArrayList<>();
    private final Map<String, ItemStack> stackCache = new HashMap<>();
    private TeamShopSyncS2C.EntryView selected;
    private int scroll;

    private String selectedApprovalId;
    private int approvalScroll;

    private ItemStack hoveredTooltipStack = ItemStack.EMPTY;

    public TeamShopScreen() {
        super(Component.translatable("screen.vppoints.team_shop.title"));
    }

    @Override
    protected void init() {
        if (TeamShopClientState.teamName == null) {
            onClose();
            return;
        }
        int left = left();
        int top = top();

        tabAmmoButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.vppoints.team_shop.tab.ammo"),
                        b -> switchCurrency(ShopCurrency.AMMO))
                .bounds(left + 10, top + 8, 70, 20)
                .build());
        tabOilButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.vppoints.team_shop.tab.oil"),
                        b -> switchCurrency(ShopCurrency.OIL))
                .bounds(left + 84, top + 8, 70, 20)
                .build());

        searchBox = addRenderableWidget(new EditBox(font, left + 10, top + 33, 144, 18, Component.translatable("screen.vppoints.team_shop.search")));
        searchBox.setMaxLength(64);
        searchBox.setResponder(ignored -> rebuildFilteredEntries(true));

        int defaultQuantity = Math.max(1, SquadConfig.SHOP_DEFAULT_PURCHASE_QUANTITY.get());
        quantityBox = addRenderableWidget(new EditBox(font, left + 239, top + 161, 48, 20, Component.translatable("screen.vppoints.team_shop.quantity")));
        quantityBox.setMaxLength(6);
        quantityBox.setValue(Integer.toString(defaultQuantity));

        minusButton = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeQuantity(-1))
                .bounds(left + 214, top + 161, 20, 20)
                .build());
        plusButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeQuantity(1))
                .bounds(left + 292, top + 161, 20, 20)
                .build());

        setDefaultQuantityButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.vppoints.team_shop.set_default_quantity"),
                        b -> setCurrentQuantityAsDefault())
                .bounds(left + 214, top + 186, 98, 20)
                .build());

        buyButton = addRenderableWidget(Button.builder(Component.translatable("screen.vppoints.team_shop.buy"), b -> buySelected())
                .bounds(left + 316, top + 161, 34, 45)
                .build());

        approveButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.vppoints.team_shop.approve"),
                        b -> sendApproval(true))
                .bounds(left + 196, top + 240, 72, 20)
                .build());
        rejectButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.vppoints.team_shop.reject"),
                        b -> sendApproval(false))
                .bounds(left + 274, top + 240, 72, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 274, top + 8, 76, 20)
                .build());

        rebuildFilteredEntries(true);
        keepApprovalSelection();
        refreshButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        hoveredTooltipStack = ItemStack.EMPTY;

        int left = left();
        int top = top();
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
        graphics.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, top + PANEL_HEIGHT - 1, 0xE0262630);

        String titleText = Component.translatable("screen.vppoints.team_shop.header", TeamShopClientState.teamName).getString();
        graphics.drawString(font, titleText, left + 10, top + 58, 0xFFFFFF, false);

        String ammoText = Component.translatable("screen.vppoints.team_shop.ammo_balance", TeamShopClientState.ammoBalance).getString();
        String oilText = Component.translatable("screen.vppoints.team_shop.oil_balance", TeamShopClientState.oilBalance).getString();
        graphics.drawString(font, ammoText, left + 10, top + 71, 0xC8D44A, false);
        graphics.drawString(font, oilText, left + 10, top + 82, 0x7BC2FF, false);

        drawEntryList(graphics, mouseX, mouseY);
        drawDetails(graphics, mouseX, mouseY);
        drawApprovalPanel(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hoveredTooltipStack.isEmpty()) {
            graphics.renderTooltip(font, hoveredTooltipStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (clickEntryList(mouseX, mouseY)) {
            return true;
        }
        if (clickApprovalList(mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (scrollEntryList(mouseX, mouseY, delta)) {
            return true;
        }
        if (scrollApprovalList(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (searchBox != null) {
            searchBox.tick();
        }
        if (quantityBox != null) {
            quantityBox.tick();
        }
        rebuildFilteredEntries(false);
        keepApprovalSelection();
        refreshButtons();
    }

    @Override
    public void onClose() {
        lastCurrency = currentCurrency;
        super.onClose();
    }

    private boolean clickEntryList(double mouseX, double mouseY) {
        int listX = left() + 10;
        int listY = top() + 95;
        int listW = 170;
        int listH = ROW_HEIGHT * VISIBLE_ROWS;
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return false;
        }
        int localRow = (int) ((mouseY - listY) / ROW_HEIGHT);
        int index = scroll + localRow;
        if (index >= 0 && index < filteredEntries.size()) {
            selected = filteredEntries.get(index);
            refreshButtons();
            return true;
        }
        return false;
    }

    private boolean clickApprovalList(double mouseX, double mouseY) {
        if (!TeamShopClientState.canApproveRequests) {
            return false;
        }
        int listX = left() + 196;
        int listY = top() + 186;
        int listW = 152;
        int listH = APPROVAL_ROW_HEIGHT * APPROVAL_VISIBLE_ROWS;
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return false;
        }
        List<TeamShopSyncS2C.ApprovalView> approvals = TeamShopClientState.pendingApprovals;
        int localRow = (int) ((mouseY - listY) / APPROVAL_ROW_HEIGHT);
        int index = approvalScroll + localRow;
        if (index >= 0 && index < approvals.size()) {
            selectedApprovalId = approvals.get(index).requestId();
            refreshButtons();
            return true;
        }
        return false;
    }

    private boolean scrollEntryList(double mouseX, double mouseY, double delta) {
        int listX = left() + 10;
        int listY = top() + 95;
        int listW = 170;
        int listH = ROW_HEIGHT * VISIBLE_ROWS;
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return false;
        }
        int maxScroll = Math.max(0, filteredEntries.size() - VISIBLE_ROWS);
        if (maxScroll <= 0) {
            return false;
        }
        if (delta > 0D) {
            scroll = Math.max(0, scroll - 1);
            return true;
        }
        if (delta < 0D) {
            scroll = Math.min(maxScroll, scroll + 1);
            return true;
        }
        return false;
    }

    private boolean scrollApprovalList(double mouseX, double mouseY, double delta) {
        if (!TeamShopClientState.canApproveRequests) {
            return false;
        }
        int listX = left() + 196;
        int listY = top() + 186;
        int listW = 152;
        int listH = APPROVAL_ROW_HEIGHT * APPROVAL_VISIBLE_ROWS;
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return false;
        }
        int max = Math.max(0, TeamShopClientState.pendingApprovals.size() - APPROVAL_VISIBLE_ROWS);
        if (max <= 0) {
            return false;
        }
        if (delta > 0D) {
            approvalScroll = Math.max(0, approvalScroll - 1);
            return true;
        }
        if (delta < 0D) {
            approvalScroll = Math.min(max, approvalScroll + 1);
            return true;
        }
        return false;
    }

    private void drawEntryList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listX = left() + 10;
        int listY = top() + 95;
        int listW = 170;
        int listH = ROW_HEIGHT * VISIBLE_ROWS;
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x70111118);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scroll + i;
            if (index >= filteredEntries.size()) {
                break;
            }
            TeamShopSyncS2C.EntryView row = filteredEntries.get(index);
            int y = listY + i * ROW_HEIGHT;
            boolean selectedRow = Objects.equals(selected, row);
            int bg = selectedRow ? 0xA03D4A63 : 0x6030303D;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                bg = selectedRow ? 0xB04E5B76 : 0x8040404F;
            }
            graphics.fill(listX + 1, y + 1, listX + listW - 1, y + ROW_HEIGHT - 1, bg);

            ItemStack stack = stackOf(row.itemId());
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, listX + 4, y + 3);
                if (mouseX >= listX + 4 && mouseX < listX + 20 && mouseY >= y + 3 && mouseY < y + 19) {
                    hoveredTooltipStack = stack;
                }
            }

            String name = row.displayName().isBlank() ? row.itemId() : row.displayName();
            String priceText = Component.translatable("screen.vppoints.team_shop.price", row.unitPrice()).getString();
            graphics.drawString(font, ellipsize(name, 16), listX + 24, y + 4, 0xFFFFFF, false);
            graphics.drawString(font, priceText, listX + 24, y + 13, 0xBBBBBB, false);
        }

        int maxScroll = Math.max(0, filteredEntries.size() - VISIBLE_ROWS);
        if (maxScroll > 0) {
            int barX = listX + listW - 4;
            graphics.fill(barX, listY, barX + 2, listY + listH, 0x60555566);
            int knobH = Math.max(12, listH / (maxScroll + 1));
            int free = listH - knobH;
            int knobY = listY + (int) (free * (scroll / (double) maxScroll));
            graphics.fill(barX, knobY, barX + 2, knobY + knobH, 0xFFB5C7E0);
        }
    }

    private void drawDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int detailX = left() + 188;
        int detailY = top() + 33;
        int detailW = 162;
        int detailH = 122;
        graphics.fill(detailX, detailY, detailX + detailW, detailY + detailH, 0x70111118);

        if (selected == null) {
            graphics.drawString(font, Component.translatable("screen.vppoints.team_shop.no_selection"), detailX + 8, detailY + 8, 0xAAAAAA, false);
            return;
        }

        ItemStack stack = stackOf(selected.itemId());
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, detailX + 8, detailY + 8);
            if (mouseX >= detailX + 8 && mouseX < detailX + 24 && mouseY >= detailY + 8 && mouseY < detailY + 24) {
                hoveredTooltipStack = stack;
            }
        }

        String name = selected.displayName().isBlank() ? selected.itemId() : selected.displayName();
        graphics.drawString(font, ellipsize(name, 18), detailX + 30, detailY + 8, 0xFFFFFF, false);

        String countText = Component.translatable("screen.vppoints.team_shop.count_per_purchase", selected.itemCount()).getString();
        graphics.drawString(font, countText, detailX + 30, detailY + 20, 0xCFCFCF, false);

        String priceText = Component.translatable("screen.vppoints.team_shop.price", selected.unitPrice()).getString();
        graphics.drawString(font, priceText, detailX + 30, detailY + 31, 0xCFCFCF, false);

        int y = detailY + 46;
        if (selected.requireCommanderApproval()) {
            graphics.drawString(font, Component.translatable("screen.vppoints.team_shop.need_commander"), detailX + 8, y, 0xF3A85F, false);
            y += 11;
        }
        if (!selected.allowedRoles().isEmpty()) {
            Component roleText = Component.translatable(
                    "screen.vppoints.team_shop.allowed_roles",
                    String.join(", ", selected.allowedRoles())
            );
            for (var line : font.split(roleText, detailW - 16)) {
                graphics.drawString(font, line, detailX + 8, y, 0x8DC8FF, false);
                y += 10;
            }
        }

        if (!selected.description().isBlank()) {
            y += 4;
            for (var line : font.split(Component.literal(selected.description()), detailW - 16)) {
                if (y > detailY + detailH - 10) {
                    break;
                }
                graphics.drawString(font, line, detailX + 8, y, 0xBBBBBB, false);
                y += 10;
            }
        }
    }

    private void drawApprovalPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int boxX = left() + 188;
        int boxY = top() + 186;
        int boxW = 162;
        int boxH = 52;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x70111118);

        if (!TeamShopClientState.canApproveRequests) {
            graphics.drawString(font, Component.translatable("screen.vppoints.team_shop.approval_not_commander"), boxX + 8, boxY + 8, 0x989898, false);
            return;
        }

        List<TeamShopSyncS2C.ApprovalView> approvals = TeamShopClientState.pendingApprovals;
        graphics.drawString(
                font,
                Component.translatable("screen.vppoints.team_shop.approval_pending", approvals.size()),
                boxX + 8,
                boxY + 4,
                0xD8D8D8,
                false
        );

        int listX = boxX + 8;
        int listY = boxY + 14;
        int listW = boxW - 10;
        int listH = APPROVAL_ROW_HEIGHT * APPROVAL_VISIBLE_ROWS;
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x6030303D);

        for (int i = 0; i < APPROVAL_VISIBLE_ROWS; i++) {
            int index = approvalScroll + i;
            if (index >= approvals.size()) {
                break;
            }
            TeamShopSyncS2C.ApprovalView row = approvals.get(index);
            int y = listY + i * APPROVAL_ROW_HEIGHT;
            boolean rowSelected = row.requestId().equals(selectedApprovalId);
            int bg = rowSelected ? 0xA0445C44 : 0x70404050;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + APPROVAL_ROW_HEIGHT) {
                bg = rowSelected ? 0xB0556F55 : 0x90484858;
            }
            graphics.fill(listX + 1, y + 1, listX + listW - 1, y + APPROVAL_ROW_HEIGHT - 1, bg);

            String entryName = row.entryDisplayName().isBlank() ? row.entryId() : row.entryDisplayName();
            String text = row.requesterName() + " x" + row.quantity() + " " + ellipsize(entryName, 10);
            graphics.drawString(font, text, listX + 4, y + 5, 0xFFFFFF, false);
        }
    }

    private void buySelected() {
        if (selected == null) {
            return;
        }
        int quantity = parseQuantity(quantityBox.getValue());
        quantity = Mth.clamp(quantity, 1, Math.max(1, SquadConfig.SHOP_MAX_PURCHASE_QUANTITY.get()));
        quantityBox.setValue(Integer.toString(quantity));
        SquadNetwork.CHANNEL.sendToServer(new BuyTeamShopEntryC2S(currentCurrency, selected.id(), quantity));
    }

    private void sendApproval(boolean approve) {
        if (!TeamShopClientState.canApproveRequests || selectedApprovalId == null || selectedApprovalId.isBlank()) {
            return;
        }
        SquadNetwork.CHANNEL.sendToServer(new TeamShopApprovalDecisionC2S(selectedApprovalId, approve));
    }

    private void setCurrentQuantityAsDefault() {
        int quantity = parseQuantity(quantityBox.getValue());
        quantity = Mth.clamp(quantity, 1, Math.max(1, SquadConfig.SHOP_MAX_PURCHASE_QUANTITY.get()));
        SquadConfig.SHOP_DEFAULT_PURCHASE_QUANTITY.set(quantity);
        quantityBox.setValue(Integer.toString(quantity));
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("message.vppoints.shop.default_quantity_set", quantity),
                    true
            );
        }
    }

    private void changeQuantity(int delta) {
        int quantity = parseQuantity(quantityBox.getValue());
        int max = Math.max(1, SquadConfig.SHOP_MAX_PURCHASE_QUANTITY.get());
        quantity = Mth.clamp(quantity + delta, 1, max);
        quantityBox.setValue(Integer.toString(quantity));
    }

    private void switchCurrency(ShopCurrency currency) {
        currentCurrency = currency;
        rebuildFilteredEntries(true);
        refreshButtons();
    }

    private void refreshButtons() {
        if (tabAmmoButton != null && tabOilButton != null) {
            tabAmmoButton.active = currentCurrency != ShopCurrency.AMMO;
            tabOilButton.active = currentCurrency != ShopCurrency.OIL;
        }
        if (buyButton != null) {
            buyButton.active = selected != null;
        }
        boolean canApprove = TeamShopClientState.canApproveRequests && selectedApprovalId != null && !selectedApprovalId.isBlank();
        if (approveButton != null) {
            approveButton.visible = TeamShopClientState.canApproveRequests;
            approveButton.active = canApprove;
        }
        if (rejectButton != null) {
            rejectButton.visible = TeamShopClientState.canApproveRequests;
            rejectButton.active = canApprove;
        }
    }

    private void rebuildFilteredEntries(boolean resetScroll) {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filteredEntries.clear();
        List<TeamShopSyncS2C.EntryView> source = currentCurrency == ShopCurrency.OIL
                ? TeamShopClientState.oilEntries
                : TeamShopClientState.ammoEntries;
        for (TeamShopSyncS2C.EntryView row : source) {
            if (query.isBlank()) {
                filteredEntries.add(row);
                continue;
            }
            String name = (row.displayName().isBlank() ? row.itemId() : row.displayName()).toLowerCase(Locale.ROOT);
            if (name.contains(query) || row.itemId().toLowerCase(Locale.ROOT).contains(query) || row.id().toLowerCase(Locale.ROOT).contains(query)) {
                filteredEntries.add(row);
            }
        }

        if (resetScroll) {
            scroll = 0;
        } else {
            scroll = Math.min(scroll, Math.max(0, filteredEntries.size() - VISIBLE_ROWS));
        }
        if (selected != null && !filteredEntries.contains(selected)) {
            selected = null;
        }
    }

    private void keepApprovalSelection() {
        List<TeamShopSyncS2C.ApprovalView> approvals = TeamShopClientState.pendingApprovals;
        int max = Math.max(0, approvals.size() - APPROVAL_VISIBLE_ROWS);
        approvalScroll = Math.min(approvalScroll, max);
        if (selectedApprovalId == null || selectedApprovalId.isBlank()) {
            if (!approvals.isEmpty()) {
                selectedApprovalId = approvals.get(0).requestId();
            }
            return;
        }
        for (TeamShopSyncS2C.ApprovalView row : approvals) {
            if (selectedApprovalId.equals(row.requestId())) {
                return;
            }
        }
        selectedApprovalId = approvals.isEmpty() ? null : approvals.get(0).requestId();
    }

    private ItemStack stackOf(String itemId) {
        return stackCache.computeIfAbsent(itemId, key -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) {
                return ItemStack.EMPTY;
            }
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item);
        });
    }

    private int parseQuantity(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return Math.max(1, SquadConfig.SHOP_DEFAULT_PURCHASE_QUANTITY.get());
        }
    }

    private String ellipsize(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, Math.max(0, maxChars));
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private int left() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int top() {
        return (height - PANEL_HEIGHT) / 2;
    }
}

