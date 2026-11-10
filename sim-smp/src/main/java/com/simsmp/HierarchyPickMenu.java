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
import net.minecraft.world.item.Items;

/** OP only: after clicking a rank in /hierarchy, pick the online player who gets it (or remove a rank). */
public final class HierarchyPickMenu extends ChestMenu {
   private static final int SLOT_BACK = 45;
   private static final int SLOT_REMOVE = 49;
   private final ServerPlayer viewer;
   private final RankManager ranks;
   private final int rankIndex;
   private final List<ServerPlayer> shown;

   private HierarchyPickMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, RankManager ranks, int rankIndex, List<ServerPlayer> shown) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.viewer = viewer;
      this.ranks = ranks;
      this.rankIndex = rankIndex;
      this.shown = shown;
   }

   public static void open(ServerPlayer viewer, RankManager ranks, int rankIndex) {
      SimpleContainer view = new SimpleContainer(54);
      List<ServerPlayer> shown = new ArrayList<>(viewer.level().getServer().getPlayerList().getPlayers());
      if (shown.size() > 45) {
         shown = new ArrayList<>(shown.subList(0, 45));
      }

      String name = HierarchyMenu.nameOf(rankIndex);
      for (int i = 0; i < shown.size(); i++) {
         ServerPlayer p = shown.get(i);
         String cur = ranks.rankName(p.getUUID());
         view.setItem(i, Ui.named(Items.PLAYER_HEAD, Msg.name(p), HierarchyMenu.colorOf(rankIndex), "Current rank: " + (cur.isEmpty() ? "none" : cur), "Click to make " + name));
      }

      view.setItem(SLOT_BACK, Ui.named(Items.ARROW, "Back", ChatFormatting.GRAY));
      view.setItem(SLOT_REMOVE, Ui.named(Items.BARRIER, "Remove a rank", ChatFormatting.RED, "Use /rank clear <player>"));
      List<ServerPlayer> fin = shown;
      viewer.openMenu(new SimpleMenuProvider((id, inv, pl) -> new HierarchyPickMenu(id, inv, view, viewer, ranks, rankIndex, fin), Component.literal("Give the rank: " + name)));
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (slotId < 0 || slotId >= 54 || !clickType.name().equals("PICKUP")) {
         if (slotId >= 0) {
            open(this.viewer, this.ranks, this.rankIndex);
         }

         return;
      }

      if (slotId == SLOT_BACK) {
         HierarchyMenu.open(this.viewer, this.ranks);
      } else if (slotId == SLOT_REMOVE) {
         Msg.info(this.viewer, "To remove a rank use /rank clear <player>.");
      } else if (slotId < this.shown.size()) {
         ServerPlayer target = this.shown.get(slotId);
         if (!HierarchyMenu.isOp(this.viewer)) {
            Msg.err(this.viewer, "Only OPs can give ranks.");
            return;
         }

         if (this.ranks.setPreset(this.viewer.level().getServer(), target, HierarchyMenu.idOf(this.rankIndex))) {
            this.ranks.save();
            Msg.ok(this.viewer, "Gave " + Msg.name(target) + " the rank " + HierarchyMenu.nameOf(this.rankIndex) + ".");
            Msg.info(target, "You now have the rank " + this.ranks.rankName(target.getUUID()) + "!");
         }

         open(this.viewer, this.ranks, this.rankIndex);
      } else {
         open(this.viewer, this.ranks, this.rankIndex);
      }
   }
}
