package com.simsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Safe player-to-player trading.
 *
 * Layout of the 6-row window:
 *   columns 0-3 (rows 0-4): your offer      column 4: divider      columns 5-8: partner's offer
 *   bottom row: cancel, your rupee offer, accept button, partner status, partner rupee offer
 *
 * Any change to either offer resets both accepts, so nobody can swap items after the other accepted.
 */
public final class TradeManager {
    private static final int SLOT_CANCEL = 45;
    private static final int SLOT_MY_MONEY = 47;
    private static final int SLOT_MY_ACCEPT = 48;
    private static final int SLOT_THEIR_ACCEPT = 50;
    private static final int SLOT_THEIR_MONEY = 51;

    private final SimConfig cfg;
    private final EconomyManager eco;
    private final Map<UUID, Request> requests = new HashMap<>(); // key = player being asked
    private final Map<UUID, Session> sessions = new HashMap<>();

    private record Request(UUID from, long expiresAt) {}

    public TradeManager(SimConfig cfg, EconomyManager eco) {
        this.cfg = cfg;
        this.eco = eco;
    }

    // ------------------------------------------------------------------ commands

    public void request(ServerPlayer from, ServerPlayer to) {
        if (from.getUUID().equals(to.getUUID())) {
            Msg.err(from, "You can't trade with yourself.");
            return;
        }
        if (sessions.containsKey(from.getUUID())) {
            Msg.err(from, "You are already in a trade.");
            return;
        }
        if (sessions.containsKey(to.getUUID())) {
            Msg.err(from, Msg.name(to) + " is already trading with someone.");
            return;
        }
        requests.put(to.getUUID(), new Request(from.getUUID(), System.currentTimeMillis() + cfg.tradeRequestSeconds * 1000L));
        Msg.ok(from, "Trade request sent to " + Msg.name(to) + ".");
        Msg.info(to, Msg.name(from) + " wants to trade with you. Type /trade accept " + Msg.name(from)
                + " (expires in " + cfg.tradeRequestSeconds + "s) or /trade deny.");
    }

    public void accept(ServerPlayer me, ServerPlayer from) {
        Request r = requests.get(me.getUUID());
        if (r == null || !r.from().equals(from.getUUID()) || System.currentTimeMillis() > r.expiresAt()) {
            Msg.err(me, "You have no pending trade request from " + Msg.name(from) + ".");
            return;
        }
        requests.remove(me.getUUID());
        if (sessions.containsKey(me.getUUID()) || sessions.containsKey(from.getUUID())) {
            Msg.err(me, "One of you is already in a trade.");
            return;
        }
        Session s = new Session(from, me);
        sessions.put(from.getUUID(), s);
        sessions.put(me.getUUID(), s);
        s.open();
    }

    public void deny(ServerPlayer me, MinecraftServer server) {
        Request r = requests.remove(me.getUUID());
        if (r == null) {
            Msg.err(me, "You have no pending trade request.");
            return;
        }
        Msg.info(me, "Trade request declined.");
        ServerPlayer other = server.getPlayerList().getPlayer(r.from());
        if (other != null) Msg.err(other, Msg.name(me) + " declined your trade request.");
    }

    public void cancel(ServerPlayer me) {
        Session s = sessions.get(me.getUUID());
        if (s == null) {
            Msg.err(me, "You are not in a trade.");
            return;
        }
        s.cancel(Msg.name(me) + " cancelled the trade.");
    }

    public void setMoney(ServerPlayer me, long amount) {
        Session s = sessions.get(me.getUUID());
        if (s == null) {
            Msg.err(me, "You are not in a trade.");
            return;
        }
        if (!eco.has(me.getUUID(), amount)) {
            Msg.err(me, "You only have " + eco.fmt(eco.get(me.getUUID())) + ".");
            return;
        }
        s.setMoney(me.getUUID(), amount);
    }

    public void playerLeft(ServerPlayer p) {
        requests.remove(p.getUUID());
        requests.values().removeIf(r -> r.from().equals(p.getUUID()));
        Session s = sessions.get(p.getUUID());
        if (s != null) s.cancel(Msg.name(p) + " left the game.");
    }

    // ------------------------------------------------------------------ item helpers

    private static ItemStack named(Item item, String name, ChatFormatting color, String lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(color).withStyle(st -> st.withItalic(false)));
        if (lore != null) {
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal(lore).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)))));
        }
        return stack;
    }

    private static boolean isMine(int slot) {
        return slot >= 0 && slot < 45 && slot % 9 < 4;
    }

    // ------------------------------------------------------------------ session

    private final class Session {
        final ServerPlayer a; // initiator
        final ServerPlayer b; // accepted the request
        final SimpleContainer viewA = new SimpleContainer(54);
        final SimpleContainer viewB = new SimpleContainer(54);
        TradeMenu menuA;
        TradeMenu menuB;
        long moneyA;
        long moneyB;
        boolean acceptA;
        boolean acceptB;
        boolean finished;

        Session(ServerPlayer a, ServerPlayer b) {
            this.a = a;
            this.b = b;
        }

        void open() {
            refresh();
            a.openMenu(new SimpleMenuProvider((id, inv, p) -> {
                menuA = new TradeMenu(id, inv, viewA, this, true);
                return menuA;
            }, Component.literal("Trade with " + Msg.name(b))));
            b.openMenu(new SimpleMenuProvider((id, inv, p) -> {
                menuB = new TradeMenu(id, inv, viewB, this, false);
                return menuB;
            }, Component.literal("Trade with " + Msg.name(a))));
            Msg.info(a, "Place items on the left. Use /trade money <amount> to add rupees, then click the green button.");
            Msg.info(b, "Place items on the left. Use /trade money <amount> to add rupees, then click the green button.");
        }

        /** The offering player changed their items: mirror to the partner and reset accepts. */
        void offerChanged(boolean fromA) {
            SimpleContainer src = fromA ? viewA : viewB;
            SimpleContainer dst = fromA ? viewB : viewA;
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 4; c++) {
                    dst.setItem(r * 9 + 5 + c, src.getItem(r * 9 + c).copy());
                }
            }
            acceptA = false;
            acceptB = false;
            refresh();
        }

        void setMoney(UUID who, long amount) {
            if (who.equals(a.getUUID())) moneyA = amount;
            else moneyB = amount;
            acceptA = false;
            acceptB = false;
            refresh();
            String who1 = who.equals(a.getUUID()) ? Msg.name(a) : Msg.name(b);
            Msg.info(a, who1 + " now offers " + eco.fmt(amount) + ".");
            Msg.info(b, who1 + " now offers " + eco.fmt(amount) + ".");
        }

        void toggleAccept(boolean isA) {
            if (finished) return;
            if (isA) acceptA = !acceptA;
            else acceptB = !acceptB;
            refresh();
            if (acceptA && acceptB) complete();
        }

        void refresh() {
            refreshView(viewA, moneyA, acceptA, moneyB, acceptB);
            refreshView(viewB, moneyB, acceptB, moneyA, acceptA);
        }

        private void refreshView(SimpleContainer view, long myMoney, boolean myAccept, long theirMoney, boolean theirAccept) {
            ItemStack divider = named(Items.GRAY_STAINED_GLASS_PANE, " ", ChatFormatting.GRAY, null);
            for (int r = 0; r < 5; r++) view.setItem(r * 9 + 4, divider.copy());
            ItemStack filler = named(Items.BLACK_STAINED_GLASS_PANE, " ", ChatFormatting.GRAY, null);
            for (int c = 0; c < 9; c++) view.setItem(45 + c, filler.copy());

            view.setItem(SLOT_CANCEL, named(Items.RED_CONCRETE, "Cancel trade", ChatFormatting.RED,
                    "Everything goes back to its owner"));
            view.setItem(SLOT_MY_MONEY, named(Items.GOLD_INGOT, "Your offer: " + eco.fmt(myMoney), ChatFormatting.GOLD,
                    "Use /trade money <amount>"));
            view.setItem(SLOT_MY_ACCEPT, myAccept
                    ? named(Items.YELLOW_CONCRETE, "Accepted - click to undo", ChatFormatting.YELLOW, "Waiting for your partner")
                    : named(Items.LIME_CONCRETE, "Click to ACCEPT", ChatFormatting.GREEN, "Check the trade first!"));
            view.setItem(SLOT_THEIR_ACCEPT, theirAccept
                    ? named(Items.LIME_CONCRETE, "Partner accepted", ChatFormatting.GREEN, null)
                    : named(Items.GRAY_CONCRETE, "Partner has not accepted", ChatFormatting.GRAY, null));
            view.setItem(SLOT_THEIR_MONEY, named(Items.GOLD_NUGGET, "Their offer: " + eco.fmt(theirMoney), ChatFormatting.GOLD, null));
        }

        private List<ItemStack> take(SimpleContainer view) {
            List<ItemStack> out = new ArrayList<>();
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 4; c++) {
                    int idx = r * 9 + c;
                    ItemStack st = view.getItem(idx);
                    if (!st.isEmpty()) out.add(st.copy());
                    view.setItem(idx, ItemStack.EMPTY);
                }
            }
            return out;
        }

        private void give(ServerPlayer p, List<ItemStack> items) {
            for (ItemStack s : items) p.getInventory().placeItemBackInInventory(s);
        }

        private void closeIfOpen(TradeMenu menu, ServerPlayer p) {
            if (menu != null && !menu.closed && p.containerMenu == menu) p.closeContainer();
        }

        void complete() {
            if (finished) return;
            if (!eco.has(a.getUUID(), moneyA) || !eco.has(b.getUUID(), moneyB)) {
                cancel("Someone no longer has enough money.");
                return;
            }
            finished = true;
            sessions.remove(a.getUUID());
            sessions.remove(b.getUUID());

            List<ItemStack> fromA = take(viewA);
            List<ItemStack> fromB = take(viewB);

            eco.take(a.getUUID(), moneyA);
            eco.add(b.getUUID(), moneyA);
            eco.take(b.getUUID(), moneyB);
            eco.add(a.getUUID(), moneyB);

            closeIfOpen(menuA, a);
            closeIfOpen(menuB, b);

            give(b, fromA);
            give(a, fromB);

            Msg.ok(a, "Trade with " + Msg.name(b) + " complete!");
            Msg.ok(b, "Trade with " + Msg.name(a) + " complete!");
        }

        void cancel(String reason) {
            if (finished) return;
            finished = true;
            sessions.remove(a.getUUID());
            sessions.remove(b.getUUID());

            List<ItemStack> fromA = take(viewA);
            List<ItemStack> fromB = take(viewB);

            closeIfOpen(menuA, a);
            closeIfOpen(menuB, b);

            give(a, fromA);
            give(b, fromB);

            Msg.err(a, "Trade cancelled: " + reason);
            Msg.err(b, "Trade cancelled: " + reason);
        }

        void menuClosed(TradeMenu menu) {
            if (finished) return;
            ServerPlayer closer = menu.isA ? a : b;
            cancel(Msg.name(closer) + " closed the trade window.");
        }
    }

    // ------------------------------------------------------------------ menu

    private final class TradeMenu extends ChestMenu {
        final Session session;
        final boolean isA;
        boolean closed;

        TradeMenu(int id, Inventory inv, SimpleContainer view, Session session, boolean isA) {
            super(MenuType.GENERIC_9x6, id, inv, view, 6);
            this.session = session;
            this.isA = isA;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (session.finished) return;

            if (slotId >= 0 && slotId < 54) {
                if (slotId == SLOT_CANCEL) {
                    session.cancel(Msg.name((ServerPlayer) player) + " cancelled the trade.");
                    return;
                }
                if (slotId == SLOT_MY_ACCEPT) {
                    session.toggleAccept(isA);
                    return;
                }
                if (!isMine(slotId)) return; // partner's items, dividers and info slots are read-only
            }
            // No shift-click, double-click or drag: they could touch read-only slots.
            if (clickType == ClickType.QUICK_MOVE || clickType == ClickType.PICKUP_ALL || clickType == ClickType.QUICK_CRAFT) {
                return;
            }

            super.clicked(slotId, button, clickType, player);
            if (slotId >= 0 && slotId < 54) {
                session.offerChanged(isA);
            }
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            closed = true;
            session.menuClosed(this);
        }
    }
}
