package com.simsmp;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /sell opens a chest. Put items in, close it, and everything with a price is sold.
 * Items without a price are handed back.
 */
public final class SellMenu extends ChestMenu {
    private final SimpleContainer box;
    private final SimConfig cfg;
    private final EconomyManager eco;
    private boolean settled;

    private SellMenu(int id, Inventory inv, SimpleContainer box, SimConfig cfg, EconomyManager eco) {
        super(MenuType.GENERIC_9x6, id, inv, box, 6);
        this.box = box;
        this.cfg = cfg;
        this.eco = eco;
    }

    public static void open(ServerPlayer player, SimConfig cfg, EconomyManager eco) {
        SimpleContainer box = new SimpleContainer(54);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new SellMenu(id, inv, box, cfg, eco),
                Component.literal("Sell Items (close to sell)")));
    }

    @Override
    public void removed(Player player) {
        super.removed(player); // returns the cursor item
        if (settled || !(player instanceof ServerPlayer sp)) return;
        settled = true;

        long total = 0;
        Map<String, Integer> sold = new LinkedHashMap<>();
        List<ItemStack> unsellable = new ArrayList<>();

        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stack = box.getItem(i);
            if (stack.isEmpty()) continue;
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Long price = cfg.prices.get(key);
            if (price != null && price > 0) {
                total += price * stack.getCount();
                sold.merge(key, stack.getCount(), Integer::sum);
            } else {
                unsellable.add(stack.copy());
            }
            box.setItem(i, ItemStack.EMPTY);
        }

        for (ItemStack s : unsellable) {
            ItemGiver.give(sp, s);
        }

        if (total > 0) {
            eco.add(sp.getUUID(), total);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : sold.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getValue()).append("x ").append(Msg.pretty(e.getKey()));
            }
            Msg.ok(sp, "Sold " + sb + ".");
            Msg.ok(sp, "You earned " + eco.fmt(total) + ". Balance: " + eco.fmt(eco.get(sp.getUUID())));
        } else if (!unsellable.isEmpty()) {
            Msg.err(sp, "None of those items can be sold. They were returned to you.");
        }
        if (total > 0 && !unsellable.isEmpty()) {
            Msg.info(sp, "Items that can't be sold were returned to you.");
        }
    }
}
