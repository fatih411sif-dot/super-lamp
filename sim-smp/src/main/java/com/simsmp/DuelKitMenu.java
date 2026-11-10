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

/** Kit selection for the two fighters before a duel: click a kit, then Ready. */
public final class DuelKitMenu extends ChestMenu {
   private static final int SLOT_READY = 22;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final DuelManager duel;

   private DuelKitMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, DuelManager duel) {
      super(MenuType.GENERIC_9x3, id, inv, view, 3);
      this.view = view;
      this.viewer = viewer;
      this.duel = duel;
   }

   public static void open(ServerPlayer p, DuelManager duel) {
      SimpleContainer view = new SimpleContainer(27);
      p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
         DuelKitMenu m = new DuelKitMenu(id, inv, view, p, duel);
         m.refresh();
         return m;
      }, Component.literal("Choose your kit")));
   }

   private void refresh() {
      for (int i = 0; i < 27; i++) {
         this.view.setItem(i, ItemStack.EMPTY);
      }

      int chosen = this.duel.kitOf(this.viewer.getUUID());
      boolean ready = this.duel.readyOf(this.viewer.getUUID());
      for (int i = 0; i < this.duel.kits().size() && i < 7; i++) {
         DuelManager.Kit k = this.duel.kits().get(i);
         Item icon = ItemIds.find(k.icon());
         boolean on = i == chosen;
         this.view.setItem(
            9 + 1 + i,
            Ui.named(icon == null ? Items.IRON_SWORD : icon, (on ? "> " : "") + k.name(), on ? ChatFormatting.GREEN : ChatFormatting.GOLD, k.description(), on ? "SELECTED" : "Click to choose")
         );
      }

      this.view.setItem(
         SLOT_READY,
         ready
            ? Ui.named(Items.CLOCK, "Ready - waiting for the other fighter", ChatFormatting.YELLOW)
            : Ui.named(Items.EMERALD, "READY", ChatFormatting.GREEN, chosen < 0 ? "No kit chosen: the first kit is used" : "Click when you are ready")
      );
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (clickType.name().equals("PICKUP") && slotId >= 0 && slotId < 27) {
         if (slotId == SLOT_READY) {
            this.duel.setReady(this.viewer);
         } else if (slotId >= 10 && slotId < 17) {
            this.duel.chooseKit(this.viewer, slotId - 10);
         }

         if (this.duel.choosing(this.viewer.getUUID())) {
            this.refresh();
         }
      } else if (slotId >= 0 && this.duel.choosing(this.viewer.getUUID())) {
         // the client moved an item in its own copy of the menu: re-open so everything matches again
         open(this.viewer, this.duel);
      }
   }
}
