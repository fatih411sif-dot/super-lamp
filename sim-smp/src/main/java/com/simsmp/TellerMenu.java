package com.simsmp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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

/** Teller desk: pending withdrawals. Left click approves, right click denies. */
public final class TellerMenu extends ChestMenu {
   private static final int SLOT_BACK = 49;
   private final ServerPlayer viewer;
   private final BankManager bank;
   private final List<BankManager.Pending> shown;

   private TellerMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, BankManager bank, List<BankManager.Pending> shown) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.viewer = viewer;
      this.bank = bank;
      this.shown = shown;
   }

   public static void open(ServerPlayer p, BankManager bank) {
      SimpleContainer view = new SimpleContainer(54);
      List<BankManager.Pending> list = bank.pending();
      if (list.size() > 45) {
         list = list.subList(0, 45);
      }

      SimpleDateFormat f = new SimpleDateFormat("HH:mm");
      for (int i = 0; i < list.size(); i++) {
         BankManager.Pending pd = list.get(i);
         String name = bank.nameOf(UUID.fromString(pd.player));
         view.setItem(i, Ui.named(Items.PAPER, name + " - " + bank.fmt(pd.amount), ChatFormatting.GOLD, "Requested at " + f.format(new Date(pd.time)), "Left click: APPROVE", "Right click: DENY (money goes back to the account)"));
      }

      view.setItem(SLOT_BACK, Ui.named(Items.ARROW, "Back to the bank", ChatFormatting.GRAY, list.isEmpty() ? "No requests waiting" : list.size() + " request(s)"));
      List<BankManager.Pending> fin = list;
      p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new TellerMenu(id, inv, view, p, bank, fin), Component.literal("Teller desk")));
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (slotId < 0 || slotId >= 54 || !clickType.name().equals("PICKUP")) {
         if (slotId >= 0) {
            open(this.viewer, this.bank);
         }

         return;
      }

      if (slotId == SLOT_BACK) {
         BankMenu.open(this.viewer, this.bank);
      } else if (slotId < this.shown.size()) {
         BankManager.Pending pd = this.shown.get(slotId);
         boolean approve = button == 0;
         if (this.bank.decide(this.viewer, pd.id, approve)) {
            Msg.ok(this.viewer, (approve ? "Approved " : "Denied ") + this.bank.fmt(pd.amount) + " for " + this.bank.nameOf(UUID.fromString(pd.player)) + ".");
         } else {
            Msg.err(this.viewer, "That request is already handled.");
         }

         open(this.viewer, this.bank);
      } else {
         open(this.viewer, this.bank);
      }
   }
}
