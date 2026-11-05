package com.simsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /top window: a podium with the 3 richest players.
 * 1st in the middle, 2nd on the right, 3rd on the left. Read-only.
 */
public final class TopMenu extends ChestMenu {
    private static final int SLOT_TITLE = 4;
    private static final int SLOT_FIRST = 13;
    private static final int SLOT_SECOND = 15; // right
    private static final int SLOT_THIRD = 11;  // left
    private static final int SLOT_YOU = 22;

    private TopMenu(int id, Inventory inv, SimpleContainer view) {
        super(MenuType.GENERIC_9x3, id, inv, view, 3);
    }

    public static void open(ServerPlayer player, EconomyManager eco) {
        SimpleContainer view = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) view.setItem(i, Ui.filler());

        List<Map.Entry<UUID, Long>> top = eco.top(3);
        view.setItem(SLOT_TITLE, Ui.named(Items.NETHER_STAR, "Richest Players", ChatFormatting.LIGHT_PURPLE, "Top 3 of SIM SMP"));
        view.setItem(SLOT_FIRST, rank(eco, top, 0, Items.GOLD_BLOCK, ChatFormatting.GOLD));
        view.setItem(SLOT_SECOND, rank(eco, top, 1, Items.IRON_BLOCK, ChatFormatting.WHITE));
        view.setItem(SLOT_THIRD, rank(eco, top, 2, Items.COPPER_BLOCK, ChatFormatting.YELLOW));
        view.setItem(SLOT_YOU, Ui.named(Items.GOLD_INGOT, "Your rank: #" + eco.rankOf(player.getUUID()), ChatFormatting.GREEN,
                "Balance: " + eco.fmt(eco.get(player.getUUID()))));

        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new TopMenu(id, inv, view),
                Component.literal("Top 3 Richest")));
    }

    private static ItemStack rank(EconomyManager eco, List<Map.Entry<UUID, Long>> top, int index, Item item, ChatFormatting color) {
        if (index >= top.size()) {
            return Ui.named(Items.BARRIER, "#" + (index + 1) + " - nobody yet", ChatFormatting.DARK_GRAY);
        }
        Map.Entry<UUID, Long> e = top.get(index);
        return Ui.named(item, "#" + (index + 1) + "  " + eco.nameOf(e.getKey()), color,
                "Balance: " + eco.fmt(e.getValue()));
    }

    /** Read-only: every click is ignored. */
    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
    }
}
