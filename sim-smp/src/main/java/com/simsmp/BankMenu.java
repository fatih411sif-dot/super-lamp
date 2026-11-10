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

/** SIM Bank main screen (glyph U+E902). Left click = small step, right click = big step, shift click = everything. */
public final class BankMenu extends ChestMenu {
   static final int S_PRICE = 4;
   static final int S_MONEY = 10;
   static final int S_GOLD = 12;
   static final int S_WORTH = 14;
   static final int S_WALLET = 16;
   static final int S_DEP_MONEY = 19;
   static final int S_WD_MONEY = 21;
   static final int S_DEP_GOLD = 23;
   static final int S_WD_GOLD = 25;
   static final int S_SELL = 28;
   static final int S_BUY = 30;
   static final int S_LOG = 37;
   static final int S_DESK = 43;
   static final int S_TICKER = 46;
   static final long SMALL = 1000L;
   static final long BIG = 10000L;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final BankManager bank;

   private BankMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, BankManager bank) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.view = view;
      this.viewer = viewer;
      this.bank = bank;
   }

   public static void open(ServerPlayer p, BankManager bank) {
      SimpleContainer view = new SimpleContainer(54);
      boolean bedrock = p.getUUID().getMostSignificantBits() == 0L;
      Component title = bedrock ? Component.literal("SIM Bank") : Component.literal("").withStyle(ChatFormatting.WHITE);
      p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
         BankMenu m = new BankMenu(id, inv, view, p, bank);
         m.refresh();
         return m;
      }, title));
   }

   private void refresh() {
      for (int i = 0; i < 54; i++) {
         this.view.setItem(i, ItemStack.EMPTY);
      }

      BankManager b = this.bank;
      BankManager.Account a = b.account(this.viewer.getUUID());
      long price = b.price();
      long prev = b.prevPrice();
      double pct = prev == 0L ? 0.0 : (price - prev) * 100.0 / prev;
      String arrow = price > prev ? "UP " : price < prev ? "DOWN " : "";
      ChatFormatting pc = price > prev ? ChatFormatting.GREEN : price < prev ? ChatFormatting.RED : ChatFormatting.GOLD;
      this.view.setItem(
         S_PRICE,
         Ui.named(
            Items.GOLD_INGOT, "Gold price: " + b.fmt(price) + " / ingot", pc,
            arrow + String.format(java.util.Locale.US, "%+.1f%%", pct) + " since the last change",
            "Buy " + b.fmt(b.buyPrice()) + "  |  Sell " + b.fmt(b.sellPrice()),
            "Changes every 5 minutes. Next: " + b.secondsToUpdate() + "s"
         )
      );
      this.view.setItem(S_MONEY, Ui.named(Items.EMERALD, "Money in the bank", ChatFormatting.GREEN, b.fmt(a.money)));
      this.view.setItem(S_GOLD, Ui.named(Items.GOLD_INGOT, "Gold in the bank", ChatFormatting.GOLD, BankManager.fmtGold(a.nuggets), "Worth " + b.fmt(a.nuggets * price / BankManager.INGOT)));
      this.view.setItem(S_WORTH, Ui.named(Items.DIAMOND, "Net worth in the bank", ChatFormatting.AQUA, b.fmt(b.netWorth(this.viewer.getUUID()))));
      this.view.setItem(S_WALLET, Ui.named(Items.GOLD_NUGGET, "Your wallet", ChatFormatting.YELLOW, b.fmt(b.wallet(this.viewer.getUUID()))));
      this.view.setItem(S_DEP_MONEY, Ui.named(Items.HOPPER, "Deposit money", ChatFormatting.GREEN, "Left: " + b.fmt(SMALL), "Right: " + b.fmt(BIG), "Shift: everything"));
      this.view.setItem(
         S_WD_MONEY,
         Ui.named(Items.DISPENSER, "Withdraw money", ChatFormatting.RED, "Left: " + b.fmt(SMALL), "Right: " + b.fmt(BIG), "Shift: everything",
            "Above " + b.fmt(b.approvalLimit()) + " a teller must approve")
      );
      this.view.setItem(S_DEP_GOLD, Ui.named(Items.GOLD_BLOCK, "Deposit gold", ChatFormatting.GOLD, "Puts ALL gold ingots, nuggets", "and blocks from your inventory in"));
      this.view.setItem(S_WD_GOLD, Ui.named(Items.GOLD_INGOT, "Withdraw gold", ChatFormatting.GOLD, "Left: 1 ingot", "Right: 1 block (9 ingots)", "Shift: everything"));
      this.view.setItem(S_SELL, Ui.named(Items.REDSTONE, "Sell gold at the market", ChatFormatting.RED, "Gold in the bank -> money in the bank", "Price: " + b.fmt(b.sellPrice()) + " / ingot", "Left: 1 ingot  Right: 10  Shift: all"));
      this.view.setItem(S_BUY, Ui.named(Items.LIME_DYE, "Buy gold at the market", ChatFormatting.GREEN, "Money in the bank -> gold in the bank", "Price: " + b.fmt(b.buyPrice()) + " / ingot", "Left: 1 ingot  Right: 10  Shift: max"));
      List<String> logLines = new ArrayList<>();
      for (int i = Math.max(0, a.log.size() - 6); i < a.log.size(); i++) {
         logLines.add(a.log.get(i));
      }

      if (logLines.isEmpty()) {
         logLines.add("No transactions yet");
      }

      this.view.setItem(S_LOG, Ui.named(Items.BOOK, "Your last transactions", ChatFormatting.WHITE, logLines.toArray(new String[0])));
      if (b.isTeller(this.viewer)) {
         int n = b.pending().size();
         this.view.setItem(S_DESK, Ui.named(Items.WRITABLE_BOOK, "Teller desk", ChatFormatting.GOLD, n + " withdrawal request" + (n == 1 ? "" : "s") + " waiting", "Click to open"));
      } else {
         this.view.setItem(S_DESK, Ui.named(Items.BARRIER, "Teller desk", ChatFormatting.DARK_GRAY, "Staff only"));
      }

      List<Long> h = b.history();
      int start = Math.max(0, h.size() - 7);
      for (int i = start; i < h.size(); i++) {
         int slot = S_TICKER + (7 - (h.size() - start)) + (i - start);
         long v = h.get(i);
         long before = i == 0 ? v : h.get(i - 1);
         Item it = i == h.size() - 1 ? Items.GOLD_INGOT : v > before ? Items.LIME_DYE : v < before ? Items.RED_DYE : Items.GRAY_DYE;
         ChatFormatting c = v > before ? ChatFormatting.GREEN : v < before ? ChatFormatting.RED : ChatFormatting.GRAY;
         this.view.setItem(slot, Ui.named(it, b.fmt(v), c, i == h.size() - 1 ? "Now" : (h.size() - 1 - i) * 5 + " min ago"));
      }
   }

   private void say(boolean ok, String text) {
      if (ok) {
         Msg.ok(this.viewer, text);
      } else {
         Msg.err(this.viewer, text);
      }
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      String ct = clickType.name();
      boolean shift = ct.equals("QUICK_MOVE");
      boolean pickup = ct.equals("PICKUP");
      if (slotId < 0 || slotId >= 54 || !(pickup || shift)) {
         if (slotId >= 0) {
            open(this.viewer, this.bank);
         }

         return;
      }

      boolean right = button == 1;
      BankManager b = this.bank;
      BankManager.Account a = b.account(this.viewer.getUUID());
      switch (slotId) {
         case S_DEP_MONEY -> {
            long amt = shift ? b.wallet(this.viewer.getUUID()) : right ? BIG : SMALL;
            if (amt <= 0L || b.wallet(this.viewer.getUUID()) < amt) {
               this.say(false, "Not enough money in your wallet (" + b.fmt(b.wallet(this.viewer.getUUID())) + ").");
            } else {
               b.depositMoney(this.viewer, amt);
               this.say(true, "Deposited " + b.fmt(amt) + ".");
            }
         }
         case S_WD_MONEY -> {
            long amt = shift ? a.money : right ? BIG : SMALL;
            if (amt <= 0L || a.money < amt) {
               this.say(false, "Not enough money in the bank (" + b.fmt(a.money) + ").");
            } else {
               int r = b.withdrawMoney(this.viewer, amt);
               if (r == 2) {
                  this.say(true, "Your withdrawal of " + b.fmt(amt) + " is waiting for a teller to approve it.");
               } else {
                  this.say(r == 1, "Withdrew " + b.fmt(amt) + ".");
               }
            }
         }
         case S_DEP_GOLD -> {
            long n = b.depositGold(this.viewer);
            if (n == 0L) {
               this.say(false, "You have no gold ingots, nuggets or blocks.");
            } else {
               this.say(true, "Deposited " + BankManager.fmtGold(n) + " of gold.");
            }
         }
         case S_WD_GOLD -> {
            long want = shift ? a.nuggets : right ? BankManager.BLOCK : BankManager.INGOT;
            long n = b.withdrawGold(this.viewer, want);
            if (n == 0L) {
               this.say(false, "You have no gold in the bank.");
            } else {
               this.say(true, "Withdrew " + BankManager.fmtGold(n) + " of gold.");
            }
         }
         case S_SELL -> {
            long want = shift ? a.nuggets : right ? 10L * BankManager.INGOT : BankManager.INGOT;
            long got = b.sellGold(this.viewer, want);
            if (got == 0L) {
               this.say(false, "You have no gold in the bank to sell.");
            } else {
               this.say(true, "Sold gold for " + b.fmt(got) + ".");
            }
         }
         case S_BUY -> {
            long want = shift ? b.affordableNuggets(a.money) : right ? 10L * BankManager.INGOT : BankManager.INGOT;
            long got = b.buyGold(this.viewer, want);
            if (got == 0L) {
               this.say(false, "Not enough money in the bank for that.");
            } else {
               this.say(true, "Bought " + BankManager.fmtGold(got) + " of gold.");
            }
         }
         case S_DESK -> {
            if (b.isTeller(this.viewer)) {
               TellerMenu.open(this.viewer, b);
               return;
            }
         }
         default -> {
         }
      }

      this.refresh();
   }
}
