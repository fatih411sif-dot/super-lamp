package com.simsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * /market GUI. Slots 0-44 show listings (45 per page); the bottom row has paging, the
 * "all / my listings" switch and your balance. Click a listing twice to buy it.
 * Every click is handled here - nothing can be moved in or out of the window.
 */
public final class MarketMenu extends ChestMenu {
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_TOGGLE = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private final SimpleContainer view;
    private final ServerPlayer viewer;
    private final MarketManager market;
    private final EconomyManager eco;
    private final long[] slotListing = new long[PER_PAGE];
    private boolean mine;
    private int page;
    private long pending = -1;

    private MarketMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, MarketManager market, EconomyManager eco) {
        super(MenuType.GENERIC_9x6, id, inv, view, 6);
        this.view = view;
        this.viewer = viewer;
        this.market = market;
        this.eco = eco;
    }

    public static void open(ServerPlayer player, MarketManager market, EconomyManager eco) {
        SimpleContainer view = new SimpleContainer(54);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            MarketMenu menu = new MarketMenu(id, inv, view, player, market, eco);
            menu.refresh();
            return menu;
        }, Component.literal("Player Market")));
    }

    private void refresh() {
        List<MarketManager.Listing> all = market.view(mine, viewer.getUUID());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx < all.size()) {
                MarketManager.Listing l = all.get(idx);
                view.setItem(i, display(l));
                slotListing[i] = l.id();
            } else {
                view.setItem(i, ItemStack.EMPTY);
                slotListing[i] = -1;
            }
        }

        ItemStack filler = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);
        for (int c = 45; c < 54; c++) view.setItem(c, filler.copy());

        if (page > 0) {
            view.setItem(SLOT_PREV, TradeManager.named(Items.ARROW, "Previous page", ChatFormatting.YELLOW, "Page " + (page + 1) + "/" + pages));
        }
        if (page < pages - 1) {
            view.setItem(SLOT_NEXT, TradeManager.named(Items.ARROW, "Next page", ChatFormatting.YELLOW, "Page " + (page + 1) + "/" + pages));
        }
        view.setItem(SLOT_TOGGLE, mine
                ? TradeManager.named(Items.ENDER_CHEST, "Showing: My listings", ChatFormatting.GOLD, "Click an item to take it back. Click here for all listings.")
                : TradeManager.named(Items.CHEST, "Showing: All listings", ChatFormatting.GOLD, "Click here to see only your listings."));
        view.setItem(SLOT_INFO, TradeManager.named(Items.GOLD_INGOT, "Balance: " + eco.fmt(eco.get(viewer.getUUID())), ChatFormatting.GOLD,
                "Sell: hold an item, then /market sell <price>"));
    }

    /** The listed item plus price / seller / hint lines (a copy; the real stack is untouched). */
    private ItemStack display(MarketManager.Listing l) {
        ItemStack copy = l.stack().copy();
        List<Component> lines = new ArrayList<>();
        ItemLore old = copy.get(DataComponents.LORE);
        if (old != null) lines.addAll(old.lines());
        lines.add(Component.literal(" "));
        lines.add(Component.literal("Price: " + eco.fmt(l.price())).withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
        lines.add(Component.literal("Seller: " + l.sellerName()).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)));
        lines.add(Component.literal(mine ? "Click to take it back" : "Click twice to buy")
                .withStyle(ChatFormatting.GREEN).withStyle(st -> st.withItalic(false)));
        copy.set(DataComponents.LORE, new ItemLore(lines));
        return copy;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
        // Only plain clicks inside the window. Everything else is ignored so nothing can be taken or inserted.
        if (clickType != ContainerInput.PICKUP || slotId < 0 || slotId >= 54) return;

        if (slotId < PER_PAGE) {
            long id = slotListing[slotId];
            if (id < 0) return;
            onListingClicked(id);
        } else if (slotId == SLOT_PREV) {
            page--;
            pending = -1;
            refresh();
        } else if (slotId == SLOT_NEXT) {
            page++;
            pending = -1;
            refresh();
        } else if (slotId == SLOT_TOGGLE) {
            mine = !mine;
            page = 0;
            pending = -1;
            refresh();
        }
    }

    private void onListingClicked(long id) {
        MarketManager.Listing l = market.find(id);
        if (l == null) {
            Msg.err(viewer, "That listing is no longer available.");
            refresh();
            return;
        }
        if (mine) {
            market.cancel(viewer, id);
            refresh();
            return;
        }
        if (pending != id) {
            pending = id;
            Msg.info(viewer, "Click again to buy " + l.stack().getCount() + "x " + l.stack().getHoverName().getString()
                    + " for " + eco.fmt(l.price()) + ".");
            return;
        }
        pending = -1;
        market.buy(viewer, id);
        refresh();
    }
}
