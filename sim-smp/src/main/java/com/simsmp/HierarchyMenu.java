package com.simsmp;

import java.util.ArrayList;
import java.util.List;
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

/** /hierarchy: the medieval ladder as a pyramid (glyph U+E900), showing which online players hold each rank. */
public final class HierarchyMenu extends ChestMenu {
   private static final String[] IDS = {"king", "queen", "prince", "duke", "paladin", "lord", "count", "baron", "knight", "squire", "merchant", "peasant", "bandit"};
   private static final String[] NAMES = {"King", "Queen", "Prince", "Duke", "Paladin", "Lord", "Count", "Baron", "Knight", "Squire", "Merchant", "Peasant", "Bandit"};
   private static final ChatFormatting[] COLORS = {
      ChatFormatting.GOLD, ChatFormatting.LIGHT_PURPLE, ChatFormatting.AQUA, ChatFormatting.RED, ChatFormatting.AQUA, ChatFormatting.GREEN,
      ChatFormatting.BLUE, ChatFormatting.DARK_AQUA, ChatFormatting.WHITE, ChatFormatting.DARK_GREEN, ChatFormatting.YELLOW, ChatFormatting.GRAY, ChatFormatting.DARK_RED
   };
   private static final Item[] ICONS = {
      Items.GOLDEN_HELMET, Items.DIAMOND, Items.AMETHYST_SHARD, Items.REDSTONE, Items.NETHER_STAR, Items.EMERALD,
      Items.LAPIS_LAZULI, Items.GOLD_INGOT, Items.IRON_SWORD, Items.IRON_INGOT, Items.GOLD_NUGGET, Items.WHEAT, Items.CROSSBOW
   };
   /** two columns of 5 ranks (King..Baron left, Knight..Peasant right), matches the drawing */
   static final int[] SLOTS = {0, 9, 18, 27, 36, 45, 5, 14, 23, 32, 41, 50, 49};
   private final ServerPlayer viewer;
   private final RankManager ranks;

   private static String isOpHint(ServerPlayer p) {
      return isOp(p) ? "OP: click a rank to give it to a player" : "Ranks are given by the staff";
   }

   private HierarchyMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, RankManager ranks) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.viewer = viewer;
      this.ranks = ranks;
   }

   public static void open(ServerPlayer player, RankManager ranks) {
      SimpleContainer view = new SimpleContainer(54);
      List<List<String>> holders = new ArrayList<>();
      for (int i = 0; i < IDS.length; i++) {
         holders.add(new ArrayList<>());
      }

      for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
         String preset = ranks.presetOf(p.getUUID());
         for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(preset)) {
               holders.get(i).add(Msg.name(p));
            }
         }
      }

      for (int i = 0; i < IDS.length; i++) {
         List<String> h = holders.get(i);
         String lore1 = h.isEmpty() ? "Nobody online" : String.join(", ", h.subList(0, Math.min(h.size(), 6))) + (h.size() > 6 ? " +" + (h.size() - 6) : "");
         view.setItem(SLOTS[i], Ui.named(ICONS[i], NAMES[i], COLORS[i], "Level " + (IDS.length - i) + " of " + IDS.length, lore1));
      }

      String mine = ranks.rankName(player.getUUID());
      Msg.info(player, "Your rank: " + (mine.isEmpty() ? "none" : mine) + (isOp(player) ? "  (OP: click a rank to give it)" : "") + "  - tree view: /hierarchy tree");
      boolean bedrock = player.getUUID().getMostSignificantBits() == 0L;
      if (bedrock) {
         for (int i = 0; i < 54; i++) {
            if (view.getItem(i).isEmpty()) {
               view.setItem(i, Ui.filler());
            }
         }
      }

      Component title = bedrock ? Component.literal("Medieval Hierarchy") : Component.literal("").withStyle(ChatFormatting.WHITE);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> new HierarchyMenu(id, inv, view, player, ranks), title));
   }

   static String nameOf(int i) {
      return NAMES[i];
   }

   static String idOf(int i) {
      return IDS[i];
   }

   static ChatFormatting colorOf(int i) {
      return COLORS[i];
   }

   static boolean isOp(ServerPlayer p) {
      return net.minecraft.commands.Commands.hasPermission(new net.minecraft.server.permissions.PermissionCheck.Require(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).test(p.createCommandSourceStack());
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      // everyone can look; OPs click a rank to give it to a player
      if (slotId >= 0 && slotId < 54 && clickType.name().equals("PICKUP") && isOp(this.viewer)) {
         for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slotId) {
               HierarchyPickMenu.open(this.viewer, this.ranks, i);
               return;
            }
         }
      }

      // if the client moved an item in its own copy, re-open so everything matches again
      if (slotId >= 0 && (slotId >= 54 || !clickType.name().startsWith("PICKUP"))) {
         open(this.viewer, this.ranks);
      }
   }
}
