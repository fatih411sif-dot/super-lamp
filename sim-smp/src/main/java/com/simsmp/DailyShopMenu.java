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
 * /dshop window. Offers sit in a 5-wide block in the middle (up to 25); the bottom row shows
 * the balance and the time until the next refresh. Click an item twice to buy it.
 * Every click is handled here, so nothing can be moved in or out of the window.
 */
public final class DailyShopMenu extends ChestMenu {
    private static final int SLOT_INFO = 49;

    private final SimpleContainer view;
    private final ServerPlayer viewer;
    private final DailyShop shop;
    private final EconomyManager eco;
    private final String[] slotOffer = new String[54];
    private String pending;

    private DailyShopMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, DailyShop shop, EconomyManager eco) {
        super(MenuType.GENERIC_9x6, id, inv, view, 6);
        this.view = view;
        this.viewer = viewer;
        this.shop = shop;
        this.eco = eco;
    }

    public static void open(ServerPlayer player, DailyShop shop, EconomyManager eco) {
        SimpleContainer view = new SimpleContainer(54);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            DailyShopMenu menu = new DailyShopMenu(id, inv, view, player, shop, eco);
            menu.refresh();
            return menu;
        }, Component.literal("Daily Shop")));
    }

    private static int slotFor(int index) {
        return (index / 5) * 9 + 2 + (index % 5);
    }

    static String duration(long millis) {
        long totalMinutes = Math.max(0, millis) / 60_000L;
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private void refresh() {
        for (int s = 0; s < 54; s++) {
            view.setItem(s, ItemStack.EMPTY);
            slotOffer[s] = null;
        }
        ItemStack filler = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);
        for (int c = 45; c < 54; c++) view.setItem(c, filler.copy());

        List<DailyShop.Offer> offers = shop.offersFor(viewer.getUUID());
        for (int i = 0; i < offers.size(); i++) {
            DailyShop.Offer o = offers.get(i);
            int slot = slotFor(i);
            slotOffer[slot] = o.id();
            if (shop.isBought(viewer.getUUID(), o.id())) {
                view.setItem(slot, TradeManager.named(Items.BARRIER, "Sold out", ChatFormatting.RED,
                        o.stack().getHoverName().getString()));
            } else {
                view.setItem(slot, display(o));
            }
        }

        view.setItem(SLOT_INFO, TradeManager.named(Items.CLOCK, "New items in " + duration(shop.millisUntilRefresh()),
                ChatFormatting.AQUA, "Balance: " + eco.fmt(eco.get(viewer.getUUID())) + "  |  Every player has a different shop"));
    }

    private ItemStack display(DailyShop.Offer o) {
        ItemStack copy = o.stack().copy();
        List<Component> lines = new ArrayList<>();
        ItemLore old = copy.get(DataComponents.LORE);
        if (old != null) lines.addAll(old.lines());
        lines.add(Component.literal(" "));
        lines.add(Component.literal("Price: " + eco.fmt(o.price())).withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
        lines.add(Component.literal("Click twice to buy").withStyle(ChatFormatting.GREEN).withStyle(st -> st.withItalic(false)));
        copy.set(DataComponents.LORE, new ItemLore(lines));
        return copy;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
        // Only plain clicks inside the window. Everything else is ignored.
        if (clickType != ContainerInput.PICKUP || slotId < 0 || slotId >= 54) return;
        String id = slotOffer[slotId];
        if (id == null) return;

        if (shop.isBought(viewer.getUUID(), id)) {
            Msg.err(viewer, "You already bought that today.");
            return;
        }
        if (!id.equals(pending)) {
            pending = id;
            for (DailyShop.Offer o : shop.offersFor(viewer.getUUID())) {
                if (o.id().equals(id)) {
                    Msg.info(viewer, "Click again to buy " + o.stack().getCount() + "x "
                            + o.stack().getHoverName().getString() + " for " + eco.fmt(o.price()) + ".");
                    break;
                }
            }
            return;
        }
        pending = null;
        shop.buy(viewer, id);
        refresh();
    }
}
