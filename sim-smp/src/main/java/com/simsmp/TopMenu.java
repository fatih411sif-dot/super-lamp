package com.simsmp;

import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
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

public final class TopMenu extends ChestMenu {
   private static final int SLOT_TITLE = 4;
   private static final int SLOT_FIRST = 13;
   private static final int SLOT_SECOND = 15;
   private static final int SLOT_THIRD = 11;
   private static final int SLOT_YOU = 22;

   private TopMenu(int id, Inventory inv, SimpleContainer view) {
      super(MenuType.GENERIC_9x4, id, inv, view, 4);
   }

   /** Java players with the resource pack get a drawn podium (glyph U+E600); Bedrock gets the plain title. */
   public static void open(ServerPlayer player, EconomyManager eco) {
      SimpleContainer view = new SimpleContainer(36);
      List<Entry<UUID, Long>> top = eco.top(3);
      // podium: #1 in the middle (row 1), #2 left and #3 right (row 2)
      view.setItem(13, rank(eco, top, 0, Items.GOLD_BLOCK, ChatFormatting.GOLD));
      view.setItem(19, rank(eco, top, 1, Items.IRON_BLOCK, ChatFormatting.WHITE));
      view.setItem(25, rank(eco, top, 2, Items.COPPER_INGOT, ChatFormatting.YELLOW));
      view.setItem(
         8, Ui.named(Items.GOLD_INGOT, "Your rank: #" + eco.rankOf(player.getUUID()), ChatFormatting.GREEN, "Balance: " + eco.fmt(eco.get(player.getUUID())))
      );
      boolean bedrock = player.getUUID().getMostSignificantBits() == 0L;
      if (bedrock) {
         for (int i = 0; i < 36; i++) {
            if (view.getItem(i).isEmpty() && i != 4) {
               view.setItem(i, Ui.filler());
            }
         }
      }

      Component title = bedrock ? Component.literal("Top 3 Richest") : Component.literal("\ue001\ue600").withStyle(ChatFormatting.WHITE);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> new TopMenu(id, inv, view), title));
   }

   private static ItemStack rank(EconomyManager eco, List<Entry<UUID, Long>> top, int index, Item item, ChatFormatting color) {
      if (index >= top.size()) {
         return Ui.named(Items.BARRIER, "#" + (index + 1) + " - nobody yet", ChatFormatting.DARK_GRAY);
      } else {
         Entry<UUID, Long> e = top.get(index);
         return Ui.named(item, "#" + (index + 1) + "  " + eco.nameOf(e.getKey()), color, "Balance: " + eco.fmt(e.getValue()));
      }
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
   }
}
