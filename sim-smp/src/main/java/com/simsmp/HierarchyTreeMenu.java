package com.simsmp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
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

/** /hierarchy tree: the ranks drawn as an org chart (glyph U+E901). Read-only; Bedrock gets named items in the same layout. */
public final class HierarchyTreeMenu extends ChestMenu {
   /** row, column of every rank (King..Peasant) for the Bedrock fallback */
   private static final int[][] POS = {{0, 4}, {1, 2}, {1, 6}, {2, 2}, {2, 6}, {3, 1}, {3, 4}, {3, 7}, {4, 2}, {4, 6}, {5, 1}, {5, 4}, {5, 7}};
   /** slot next to each badge (same order as the ranks), matches tools/make_pack.py TREE_SLOTS */
   private static final int[] SLOTS = {6, 14, 17, 22, 26, 30, 33, 35, 41, 44, 48, 51, 53};
   private final ServerPlayer viewer;
   private final RankManager ranks;

   private HierarchyTreeMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, RankManager ranks) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.viewer = viewer;
      this.ranks = ranks;
   }

   public static void open(ServerPlayer player, RankManager ranks) {
      SimpleContainer view = new SimpleContainer(54);
      boolean bedrock = player.getUUID().getMostSignificantBits() == 0L;
      if (bedrock) {
         for (int i = 0; i < 54; i++) {
            view.setItem(i, Ui.filler());
         }

      }

      List<List<String>> holders = new ArrayList<>();
      for (int i = 0; i < POS.length; i++) {
         holders.add(new ArrayList<>());
      }

      for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
         String preset = ranks.presetOf(p.getUUID());
         for (int i = 0; i < POS.length; i++) {
            if (HierarchyMenu.idOf(i).equals(preset)) {
               holders.get(i).add(Msg.name(p));
            }
         }
      }

      for (int i = 0; i < POS.length; i++) {
         List<String> h = holders.get(i);
         String online = h.size() + " online";
         String names = h.isEmpty() ? "Nobody online" : String.join(", ", h.subList(0, Math.min(h.size(), 8))) + (h.size() > 8 ? " +" + (h.size() - 8) : "");
         if (bedrock) {
            view.setItem(POS[i][0] * 9 + POS[i][1], Ui.named(Items.PAPER, HierarchyMenu.nameOf(i), HierarchyMenu.colorOf(i), "Level " + (POS.length - i) + " of " + POS.length, online, names));
         } else if (h.isEmpty()) {
            ItemStack blank = Ui.named(Items.PAPER, HierarchyMenu.nameOf(i), HierarchyMenu.colorOf(i), "Nobody online");
            blank.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("sim_smp", "blank"));       // invisible, only the hover text
            view.setItem(SLOTS[i], blank);
         } else {
            ItemStack head = Ui.named(Items.PLAYER_HEAD, HierarchyMenu.nameOf(i) + " - " + online, HierarchyMenu.colorOf(i), names);
            head.setCount(Math.min(h.size(), 64));
            view.setItem(SLOTS[i], head);
         }
      }

      Component title = bedrock ? Component.literal("Hierarchy Tree") : Component.literal("").withStyle(ChatFormatting.WHITE);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> new HierarchyTreeMenu(id, inv, view, player, ranks), title));
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      // read-only: re-open so the client's own copy matches again
      if (slotId >= 0) {
         open(this.viewer, this.ranks);
      }
   }
}
