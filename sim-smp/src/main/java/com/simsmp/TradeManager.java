package com.simsmp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public final class TradeManager {
   private static final int SLOT_CANCEL = 45;
   private static final int SLOT_MY_MONEY = 47;
   private static final int SLOT_MY_ACCEPT = 48;
   private static final int SLOT_THEIR_ACCEPT = 50;
   private static final int SLOT_THEIR_MONEY = 51;
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final Map<UUID, TradeManager.Request> requests = new HashMap<>();
   private final Map<UUID, TradeManager.Session> sessions = new HashMap<>();

   public TradeManager(SimConfig cfg, EconomyManager eco) {
      this.cfg = cfg;
      this.eco = eco;
   }

   public void request(ServerPlayer from, ServerPlayer to) {
      if (from.getUUID().equals(to.getUUID())) {
         Msg.err(from, "You can't trade with yourself.");
      } else if (this.sessions.containsKey(from.getUUID())) {
         Msg.err(from, "You are already in a trade.");
      } else if (this.sessions.containsKey(to.getUUID())) {
         Msg.err(from, Msg.name(to) + " is already trading with someone.");
      } else {
         this.requests.put(to.getUUID(), new TradeManager.Request(from.getUUID(), System.currentTimeMillis() + this.cfg.tradeRequestSeconds * 1000L));
         Msg.ok(from, "Trade request sent to " + Msg.name(to) + ".");
         Msg.info(
            to,
            Msg.name(from)
               + " wants to trade with you. Type /trade accept "
               + Msg.name(from)
               + " (expires in "
               + this.cfg.tradeRequestSeconds
               + "s) or /trade deny."
         );
      }
   }

   public void accept(ServerPlayer me, ServerPlayer from) {
      TradeManager.Request r = this.requests.get(me.getUUID());
      if (r != null && r.from().equals(from.getUUID()) && System.currentTimeMillis() <= r.expiresAt()) {
         this.requests.remove(me.getUUID());
         if (!this.sessions.containsKey(me.getUUID()) && !this.sessions.containsKey(from.getUUID())) {
            TradeManager.Session s = new TradeManager.Session(from, me);
            this.sessions.put(from.getUUID(), s);
            this.sessions.put(me.getUUID(), s);
            s.open();
         } else {
            Msg.err(me, "One of you is already in a trade.");
         }
      } else {
         Msg.err(me, "You have no pending trade request from " + Msg.name(from) + ".");
      }
   }

   public void deny(ServerPlayer me, MinecraftServer server) {
      TradeManager.Request r = this.requests.remove(me.getUUID());
      if (r == null) {
         Msg.err(me, "You have no pending trade request.");
      } else {
         Msg.info(me, "Trade request declined.");
         ServerPlayer other = server.getPlayerList().getPlayer(r.from());
         if (other != null) {
            Msg.err(other, Msg.name(me) + " declined your trade request.");
         }
      }
   }

   public void cancel(ServerPlayer me) {
      TradeManager.Session s = this.sessions.get(me.getUUID());
      if (s == null) {
         Msg.err(me, "You are not in a trade.");
      } else {
         s.cancel(Msg.name(me) + " cancelled the trade.");
      }
   }

   public void setMoney(ServerPlayer me, long amount) {
      TradeManager.Session s = this.sessions.get(me.getUUID());
      if (s == null) {
         Msg.err(me, "You are not in a trade.");
      } else if (!this.eco.has(me.getUUID(), amount)) {
         Msg.err(me, "You only have " + this.eco.fmt(this.eco.get(me.getUUID())) + ".");
      } else {
         s.setMoney(me.getUUID(), amount);
      }
   }

   public void playerLeft(ServerPlayer p) {
      this.requests.remove(p.getUUID());
      this.requests.values().removeIf(r -> r.from().equals(p.getUUID()));
      TradeManager.Session s = this.sessions.get(p.getUUID());
      if (s != null) {
         s.cancel(Msg.name(p) + " left the game.");
      }
   }

   static ItemStack named(Item item, String name, ChatFormatting color, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color).withStyle(st -> st.withItalic(false)));
      if (lore != null) {
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)))));
      }

      return stack;
   }

   private static boolean isMine(int slot) {
      return slot >= 0 && slot < 45 && slot % 9 < 4;
   }

   private record Request(UUID from, long expiresAt) {
   }

   private final class Session {
      final ServerPlayer a;
      final ServerPlayer b;
      final SimpleContainer viewA;
      final SimpleContainer viewB;
      TradeManager.TradeMenu menuA;
      TradeManager.TradeMenu menuB;
      long moneyA;
      long moneyB;
      boolean acceptA;
      boolean acceptB;
      boolean finished;

      Session(ServerPlayer a, ServerPlayer b) {
         Objects.requireNonNull(TradeManager.this);
         super();
         this.viewA = new SimpleContainer(54);
         this.viewB = new SimpleContainer(54);
         this.a = a;
         this.b = b;
      }

      void open() {
         this.refresh();
         this.a.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            this.menuA = TradeManager.this.new TradeMenu(id, inv, this.viewA, this, true);
            return this.menuA;
         }, Component.literal("Trade with " + Msg.name(this.b))));
         this.b.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            this.menuB = TradeManager.this.new TradeMenu(id, inv, this.viewB, this, false);
            return this.menuB;
         }, Component.literal("Trade with " + Msg.name(this.a))));
         Msg.info(this.a, "Place items on the left. Use /trade money <amount> to add rupees, then click the emerald.");
         Msg.info(this.b, "Place items on the left. Use /trade money <amount> to add rupees, then click the emerald.");
      }

      void offerChanged(boolean fromA) {
         SimpleContainer src = fromA ? this.viewA : this.viewB;
         SimpleContainer dst = fromA ? this.viewB : this.viewA;

         for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 4; c++) {
               dst.setItem(r * 9 + 5 + c, src.getItem(r * 9 + c).copy());
            }
         }

         this.acceptA = false;
         this.acceptB = false;
         this.refresh();
      }

      void setMoney(UUID who, long amount) {
         if (who.equals(this.a.getUUID())) {
            this.moneyA = amount;
         } else {
            this.moneyB = amount;
         }

         this.acceptA = false;
         this.acceptB = false;
         this.refresh();
         String who1 = who.equals(this.a.getUUID()) ? Msg.name(this.a) : Msg.name(this.b);
         Msg.info(this.a, who1 + " now offers " + TradeManager.this.eco.fmt(amount) + ".");
         Msg.info(this.b, who1 + " now offers " + TradeManager.this.eco.fmt(amount) + ".");
      }

      void toggleAccept(boolean isA) {
         if (!this.finished) {
            if (isA) {
               this.acceptA = !this.acceptA;
            } else {
               this.acceptB = !this.acceptB;
            }

            this.refresh();
            if (this.acceptA && this.acceptB) {
               this.complete();
            }
         }
      }

      void refresh() {
         this.refreshView(this.viewA, this.moneyA, this.acceptA, this.moneyB, this.acceptB);
         this.refreshView(this.viewB, this.moneyB, this.acceptB, this.moneyA, this.acceptA);
      }

      private void refreshView(SimpleContainer view, long myMoney, boolean myAccept, long theirMoney, boolean theirAccept) {
         ItemStack divider = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);

         for (int r = 0; r < 5; r++) {
            view.setItem(r * 9 + 4, divider.copy());
         }

         ItemStack filler = TradeManager.named(Items.PAPER, " ", ChatFormatting.GRAY, null);

         for (int c = 0; c < 9; c++) {
            view.setItem(45 + c, filler.copy());
         }

         view.setItem(45, TradeManager.named(Items.BARRIER, "Cancel trade", ChatFormatting.RED, "Everything goes back to its owner"));
         view.setItem(
            47, TradeManager.named(Items.GOLD_INGOT, "Your offer: " + TradeManager.this.eco.fmt(myMoney), ChatFormatting.GOLD, "Use /trade money <amount>")
         );
         view.setItem(
            48,
            myAccept
               ? TradeManager.named(Items.REDSTONE, "Accepted - click to undo", ChatFormatting.YELLOW, "Waiting for your partner")
               : TradeManager.named(Items.EMERALD, "Click to ACCEPT", ChatFormatting.GREEN, "Check the trade first!")
         );
         view.setItem(
            50,
            theirAccept
               ? TradeManager.named(Items.EMERALD, "Partner accepted", ChatFormatting.GREEN, null)
               : TradeManager.named(Items.COAL, "Partner has not accepted", ChatFormatting.GRAY, null)
         );
         view.setItem(51, TradeManager.named(Items.GOLD_NUGGET, "Their offer: " + TradeManager.this.eco.fmt(theirMoney), ChatFormatting.GOLD, null));
      }

      private List<ItemStack> take(SimpleContainer view) {
         List<ItemStack> out = new ArrayList<>();

         for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 4; c++) {
               int idx = r * 9 + c;
               ItemStack st = view.getItem(idx);
               if (!st.isEmpty()) {
                  out.add(st.copy());
               }

               view.setItem(idx, ItemStack.EMPTY);
            }
         }

         return out;
      }

      private void give(ServerPlayer p, List<ItemStack> items) {
         for (ItemStack s : items) {
            ItemGiver.give(p, s);
         }
      }

      private void closeIfOpen(TradeManager.TradeMenu menu, ServerPlayer p) {
         if (menu != null && !menu.closed && p.containerMenu == menu) {
            p.closeContainer();
         }
      }

      void complete() {
         if (!this.finished) {
            if (TradeManager.this.eco.has(this.a.getUUID(), this.moneyA) && TradeManager.this.eco.has(this.b.getUUID(), this.moneyB)) {
               this.finished = true;
               TradeManager.this.sessions.remove(this.a.getUUID());
               TradeManager.this.sessions.remove(this.b.getUUID());
               List<ItemStack> fromA = this.take(this.viewA);
               List<ItemStack> fromB = this.take(this.viewB);
               TradeManager.this.eco.take(this.a.getUUID(), this.moneyA);
               TradeManager.this.eco.add(this.b.getUUID(), this.moneyA);
               TradeManager.this.eco.take(this.b.getUUID(), this.moneyB);
               TradeManager.this.eco.add(this.a.getUUID(), this.moneyB);
               this.closeIfOpen(this.menuA, this.a);
               this.closeIfOpen(this.menuB, this.b);
               this.give(this.b, fromA);
               this.give(this.a, fromB);
               Msg.ok(this.a, "Trade with " + Msg.name(this.b) + " complete!");
               Msg.ok(this.b, "Trade with " + Msg.name(this.a) + " complete!");
            } else {
               this.cancel("Someone no longer has enough money.");
            }
         }
      }

      void cancel(String reason) {
         if (!this.finished) {
            this.finished = true;
            TradeManager.this.sessions.remove(this.a.getUUID());
            TradeManager.this.sessions.remove(this.b.getUUID());
            List<ItemStack> fromA = this.take(this.viewA);
            List<ItemStack> fromB = this.take(this.viewB);
            this.closeIfOpen(this.menuA, this.a);
            this.closeIfOpen(this.menuB, this.b);
            this.give(this.a, fromA);
            this.give(this.b, fromB);
            Msg.err(this.a, "Trade cancelled: " + reason);
            Msg.err(this.b, "Trade cancelled: " + reason);
         }
      }

      void menuClosed(TradeManager.TradeMenu menu) {
         if (!this.finished) {
            ServerPlayer closer = menu.isA ? this.a : this.b;
            this.cancel(Msg.name(closer) + " closed the trade window.");
         }
      }
   }

   private final class TradeMenu extends ChestMenu {
      final TradeManager.Session session;
      final boolean isA;
      boolean closed;

      TradeMenu(int id, Inventory inv, SimpleContainer view, TradeManager.Session session, boolean isA) {
         Objects.requireNonNull(TradeManager.this);
         super(MenuType.GENERIC_9x6, id, inv, view, 6);
         this.session = session;
         this.isA = isA;
      }

      public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
         if (!this.session.finished) {
            if (slotId >= 0 && slotId < 54) {
               if (slotId == 45) {
                  this.session.cancel(Msg.name((ServerPlayer)player) + " cancelled the trade.");
                  return;
               }

               if (slotId == 48) {
                  this.session.toggleAccept(this.isA);
                  return;
               }

               if (!TradeManager.isMine(slotId)) {
                  return;
               }
            }

            if (clickType != ContainerInput.QUICK_MOVE && clickType != ContainerInput.PICKUP_ALL && clickType != ContainerInput.QUICK_CRAFT) {
               super.clicked(slotId, button, clickType, player);
               if (slotId >= 0 && slotId < 54) {
                  this.session.offerChanged(this.isA);
               }
            }
         }
      }

      public void removed(Player player) {
         super.removed(player);
         this.closed = true;
         this.session.menuClosed(this);
      }
   }
}
