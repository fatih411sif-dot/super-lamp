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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /gacha window. Click the emerald to pay and spin: a row of prizes scrolls past and slows down until
 * the prize stops under the arrow in the middle. Rare prizes have a small chance (see gacha.json).
 *
 * The money is taken when the spin starts and the prize is handed over when it stops - or immediately if
 * the window is closed during the spin, so nothing is ever lost.
 */
public final class GachaMenu extends ChestMenu {
    private static final int SLOT_POINTER = 4;
    private static final int STRIP_START = 9;   // slots 9..17, the prize lands in slot 13
    private static final int SLOT_BALANCE = 18;
    private static final int SLOT_ROLL = 22;
    private static final int SLOT_RATES = 26;

    private static final int TOTAL_STEPS = 36;
    private static final int RESULT_PUSH_STEP = TOTAL_STEPS - 4; // it then moves 4 slots to the middle

    private static final Set<GachaMenu> ACTIVE = new HashSet<>();

    private enum State { IDLE, ROLLING, DONE }

    private final SimpleContainer view;
    private final ServerPlayer viewer;
    private final MinecraftServer server;
    private final Gacha gacha;
    private final EconomyManager eco;
    private final SimConfig cfg;
    private final ItemStack[] strip = new ItemStack[9];

    private State state = State.IDLE;
    private Gacha.Prize prize;
    private boolean granted;
    private int step;
    private int wait;

    private GachaMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, MinecraftServer server,
                      Gacha gacha, EconomyManager eco, SimConfig cfg) {
        super(MenuType.GENERIC_9x3, id, inv, view, 3);
        this.view = view;
        this.viewer = viewer;
        this.server = server;
        this.gacha = gacha;
        this.eco = eco;
        this.cfg = cfg;
    }

    public static void open(ServerPlayer player, MinecraftServer server, Gacha gacha, EconomyManager eco, SimConfig cfg) {
        if (gacha.isEmpty()) {
            Msg.err(player, "The gacha has no prizes right now.");
            return;
        }
        SimpleContainer view = new SimpleContainer(27);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            GachaMenu menu = new GachaMenu(id, inv, view, player, server, gacha, eco, cfg);
            for (int i = 0; i < 9; i++) menu.strip[i] = gacha.randomIcon(); // preview
            menu.refresh();
            return menu;
        }, Component.literal("Gacha - " + eco.fmt(cfg.gachaPrice) + " per pull")));
    }

    /** Called every server tick: advances all spinning windows. */
    public static void tickAll() {
        if (ACTIVE.isEmpty()) return;
        for (GachaMenu menu : new ArrayList<>(ACTIVE)) menu.tick();
    }

    // ------------------------------------------------------------------ display

    private void refresh() {
        for (int i = 0; i < 27; i++) view.setItem(i, Ui.filler());

        view.setItem(SLOT_POINTER, Ui.named(Items.HOPPER, "Your prize", ChatFormatting.GOLD));
        for (int i = 0; i < 9; i++) {
            ItemStack s = strip[i] == null ? ItemStack.EMPTY : strip[i].copy();
            if (state == State.DONE && i == 4 && prize != null) s = shown(prize);
            view.setItem(STRIP_START + i, s);
        }

        view.setItem(SLOT_BALANCE, Ui.named(Items.GOLD_INGOT, "Balance: " + eco.fmt(eco.get(viewer.getUUID())), ChatFormatting.GOLD));
        view.setItem(SLOT_ROLL, state == State.ROLLING
                ? Ui.named(Items.BARRIER, "Rolling...", ChatFormatting.RED)
                : Ui.named(Items.EMERALD, (state == State.DONE ? "Pull again for " : "Pull for ") + eco.fmt(cfg.gachaPrice),
                        ChatFormatting.GREEN, "Click to spin once", "Rare prizes have a small chance"));
        view.setItem(SLOT_RATES, ratesBook());
    }

    private static ItemStack shown(Gacha.Prize p) {
        ItemStack copy = p.stack().copy();
        copy.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(p.rarity().label).withStyle(p.rarity().color).withStyle(st -> st.withItalic(false)))));
        return copy;
    }

    private ItemStack ratesBook() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Gacha.Rarity, Double> e : gacha.rates().entrySet()) {
            if (e.getValue() <= 0) continue;
            lines.add(e.getKey().label + ": " + String.format(Locale.US, "%.2f", e.getValue()) + "%");
        }
        return Ui.named(Items.BOOK, "Drop rates", ChatFormatting.AQUA, lines.toArray(new String[0]));
    }

    // ------------------------------------------------------------------ spinning

    private void startSpin() {
        if (state == State.ROLLING) return;
        if (!eco.take(viewer.getUUID(), cfg.gachaPrice)) {
            Msg.err(viewer, "You need " + eco.fmt(cfg.gachaPrice) + " but only have " + eco.fmt(eco.get(viewer.getUUID())) + ".");
            return;
        }
        Gacha.Prize rolled = gacha.roll();
        if (rolled == null) {
            eco.add(viewer.getUUID(), cfg.gachaPrice); // nothing to give: refund
            Msg.err(viewer, "The gacha has no prizes right now.");
            return;
        }
        prize = rolled;
        granted = false;
        step = 0;
        wait = 0;
        state = State.ROLLING;
        for (int i = 0; i < 9; i++) strip[i] = gacha.randomIcon();
        ACTIVE.add(this);
        refresh();
    }

    private void tick() {
        if (state != State.ROLLING) return;
        if (wait > 0) {
            wait--;
            return;
        }
        step++;
        for (int i = 0; i < 8; i++) strip[i] = strip[i + 1];
        strip[8] = (step == RESULT_PUSH_STEP) ? prize.stack().copy() : gacha.randomIcon();
        wait = step <= 20 ? 1 : (step <= 30 ? 2 : 4); // fast, then slower and slower
        if (step >= TOTAL_STEPS) {
            state = State.DONE;
            ACTIVE.remove(this);
            grant();
        }
        refresh();
    }

    /** Gives the prize (once) and tells the player. Big prizes are announced to everyone. */
    private void grant() {
        if (granted || prize == null) return;
        granted = true;
        ItemGiver.give(viewer, prize.stack().copy());

        String name = prize.stack().getCount() + "x " + prize.stack().getHoverName().getString();
        Msg.colored(viewer, "You got " + name + " (" + prize.rarity().label + ")!", prize.rarity().color);
        if (prize.rarity().ordinal() >= Gacha.Rarity.EPIC.ordinal()) {
            Msg.broadcast(server, Msg.name(viewer) + " pulled " + name + " from the Gacha! (" + prize.rarity().label + ")",
                    prize.rarity().color);
        }
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
        // Only the emerald does anything. Every other click is ignored, so nothing can be moved.
        if (clickType == ContainerInput.PICKUP && slotId == SLOT_ROLL) {
            startSpin();
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        ACTIVE.remove(this);
        if (state == State.ROLLING) { // closed during the spin: hand over the prize right away
            state = State.DONE;
            grant();
        }
    }
}
