package com.simsmp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Casino screens (resource-pack glyphs, Bedrock gets plain items):
 * U+E800 lobby, U+E801 coin flip setup, U+E802 dice setup, U+E803 result.
 */
public final class CasinoMenu extends ChestMenu {
   private enum Screen {
      LOBBY,
      SETUP,
      RESULT
   }

   private static final Set<CasinoMenu> ACTIVE = new HashSet<>();
   private static final int SPIN_TICKS = 52;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final CasinoManager casino;
   private final Screen screen;
   private final CasinoManager.Round round;
   private final int who;
   private final int[] action = new int[54];
   private int ticks;
   private boolean announced;

   private CasinoMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, CasinoManager casino, Screen screen, CasinoManager.Round round, int who) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.view = view;
      this.viewer = viewer;
      this.casino = casino;
      this.screen = screen;
      this.round = round;
      this.who = who;
   }

   // action codes
   private static final int A_COIN = 1;
   private static final int A_DICE = 2;
   private static final int A_DEALER = 3;
   private static final int A_PLAYER = 100;   // + index in the online list
   private static final int A_BET = 200;      // + index of the preset
   private static final int A_HEADS = 300;
   private static final int A_TAILS = 301;
   private static final int A_START = 400;
   private static final int A_BACK = 401;
   private static final int A_AGAIN = 402;
   private static final int A_ROUL = 4;
   private static final int A_RBET = 500;     // + bet type 0..6

   private static boolean bedrock(ServerPlayer p) {
      return p.getUUID().getMostSignificantBits() == 0L;
   }

   private static Component title(ServerPlayer p, char glyph, String plain) {
      return bedrock(p) ? Component.literal(plain) : Component.literal("" + glyph).withStyle(ChatFormatting.WHITE);
   }

   public static void tickAll() {
      if (!ACTIVE.isEmpty()) {
         for (CasinoMenu m : new ArrayList<>(ACTIVE)) {
            m.tick();
         }
      }
   }

   private static void show(ServerPlayer p, CasinoManager casino, Screen screen, CasinoManager.Round round, int who, Component title) {
      SimpleContainer view = new SimpleContainer(54);
      p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
         CasinoMenu m = new CasinoMenu(id, inv, view, p, casino, screen, round, who);
         if (screen == Screen.RESULT) {
            ACTIVE.add(m);
         }

         m.refresh();
         return m;
      }, title));
   }

   public static void openLobby(ServerPlayer p, CasinoManager casino) {
      show(p, casino, Screen.LOBBY, null, 0, title(p, '', "Casino"));
   }

   private static void openSetup(ServerPlayer p, CasinoManager casino) {
      CasinoManager.Selection sel = casino.selection(p.getUUID());
      boolean coin = sel.game == CasinoManager.Game.COIN;
      show(p, casino, Screen.SETUP, null, 0, title(p, coin ? '' : '', "Casino - " + sel.game.label));
   }

   public static void openResult(ServerPlayer p, CasinoManager casino, CasinoManager.Round round, int who) {
      show(p, casino, Screen.RESULT, round, who, title(p, '', "Casino - " + round.game.label));
   }

   private void put(int slot, ItemStack stack, int act) {
      this.view.setItem(slot, stack);
      this.action[slot] = act;
   }

   private void refresh() {
      for (int i = 0; i < 54; i++) {
         this.view.setItem(i, ItemStack.EMPTY);
         this.action[i] = 0;
      }

      EconomyManager eco = this.casino.eco();
      this.view.setItem(0, Ui.named(Items.GOLD_INGOT, "Balance: " + eco.fmt(eco.get(this.viewer.getUUID())), ChatFormatting.GOLD));
      switch (this.screen) {
         case LOBBY -> this.drawLobby();
         case SETUP -> this.drawSetup();
         case RESULT -> this.drawResult();
      }

      if (bedrock(this.viewer)) {
         for (int i = 0; i < 54; i++) {
            if (this.view.getItem(i).isEmpty()) {
               this.view.setItem(i, Ui.filler());
            }
         }
      }
   }

   private void drawLobby() {
      this.view
         .setItem(
            8,
            Ui.named(
               Items.BOOK,
               "How it works",
               ChatFormatting.AQUA,
               "Play against the Dealer (NPC) or another player.",
               "Dealer: win and you get " + this.casino.dealerPercent() / 100.0 + "x your bet back.",
               "Player vs player: winner takes the pot (" + this.casino.feePercent() + "% fee).",
               "Bets: " + eco().fmt(this.casino.minBet()) + " - " + eco().fmt(this.casino.maxBet())
            )
         );
      ItemStack coin = Ui.named(Items.GOLD_INGOT, "Coin Flip", ChatFormatting.GOLD, "Pick heads or tails, 50/50.", "Dealer or another player");
      ItemStack dice = Ui.named(Items.BONE_MEAL, "Dice Duel", ChatFormatting.RED, "Both roll 2 dice, the higher total wins.", "Dealer or another player");
      ItemStack roul = Ui.named(Items.REDSTONE, "Roulette", ChatFormatting.GREEN, "Bet on red, black, even, odd, low, high or zero.", "Against the Dealer");

      for (int r = 1; r <= 4; r++) {
         for (int c = 0; c < 3; c++) {
            this.action[r * 9 + c] = A_COIN;
            this.action[r * 9 + 3 + c] = A_DICE;
            this.action[r * 9 + 6 + c] = A_ROUL;
         }
      }

      this.view.setItem(2 * 9 + 1, coin);
      this.view.setItem(2 * 9 + 4, dice);
      this.view.setItem(2 * 9 + 7, roul);
   }

   private EconomyManager eco() {
      return this.casino.eco();
   }

   private List<ServerPlayer> others() {
      List<ServerPlayer> out = new ArrayList<>();

      for (ServerPlayer p : this.viewer.level().getServer().getPlayerList().getPlayers()) {
         if (!p.getUUID().equals(this.viewer.getUUID()) && out.size() < 6) {
            out.add(p);
         }
      }

      return out;
   }

   private void drawSetup() {
      CasinoManager.Selection sel = this.casino.selection(this.viewer.getUUID());
      this.put(8, Ui.named(Items.ARROW, "Back", ChatFormatting.YELLOW, "Return to the casino."), A_BACK);
      if (sel.game == CasinoManager.Game.ROULETTE) {
         this.drawSetupRoulette(sel);
         return;
      }

      boolean dealer = sel.opponent == null;
      this.put(
         9 + 1,
         Ui.named(Items.CHEST, (dealer ? "> " : "") + "Dealer (NPC)", dealer ? ChatFormatting.GREEN : ChatFormatting.GRAY, "Play against the house.", dealer ? "SELECTED" : "Click to select"),
         A_DEALER
      );
      List<ServerPlayer> list = this.others();
      for (int i = 0; i < list.size(); i++) {
         ServerPlayer o = list.get(i);
         boolean on = o.getUUID().equals(sel.opponent);
         this.put(
            9 + 3 + i,
            Ui.named(Items.PAPER, (on ? "> " : "") + Msg.name(o), on ? ChatFormatting.GREEN : ChatFormatting.GRAY, "Challenge this player.", on ? "SELECTED" : "Click to select"),
            A_PLAYER + i
         );
      }

      long[] bets = this.casino.presets();
      for (int i = 0; i < bets.length; i++) {
         boolean on = bets[i] == sel.bet;
         this.put(
            27 + 2 + i,
            Ui.named(Items.GOLD_NUGGET, (on ? "> " : "") + eco().fmt(bets[i]), on ? ChatFormatting.GREEN : ChatFormatting.GOLD, on ? "SELECTED" : "Click to bet this"),
            A_BET + i
         );
      }

      if (sel.game == CasinoManager.Game.COIN) {
         boolean h = sel.side == 0;
         this.put(36 + 3, Ui.named(Items.GOLD_INGOT, (h ? "> " : "") + "Heads", h ? ChatFormatting.GREEN : ChatFormatting.GOLD, h ? "SELECTED" : "Click to pick"), A_HEADS);
         this.put(36 + 5, Ui.named(Items.COPPER_INGOT, (!h ? "> " : "") + "Tails", !h ? ChatFormatting.GREEN : ChatFormatting.GOLD, !h ? "SELECTED" : "Click to pick"), A_TAILS);
      }

      String vs = dealer ? "the Dealer" : this.nameOf(sel.opponent);
      this.put(
         45 + 4,
         Ui.named(Items.EMERALD, "START - " + sel.game.label + " vs " + vs, ChatFormatting.GREEN, "Bet: " + eco().fmt(sel.bet), dealer ? "Win: " + eco().fmt(sel.bet * this.casino.dealerPercent() / 100L) + " back" : "The other player must accept."),
         A_START
      );
   }

   private static final String[] RBET_NAMES = {"Red", "Black", "Even", "Odd", "1-18 (Low)", "19-36 (High)", "Zero (0)"};

   private void drawSetupRoulette(CasinoManager.Selection sel) {
      net.minecraft.world.item.Item[] icons = {Items.REDSTONE, Items.COAL, Items.IRON_BLOCK, Items.COPPER_INGOT, Items.FEATHER, Items.GOLD_INGOT, Items.EMERALD};
      for (int i = 0; i < 7; i++) {
         boolean on = sel.rbet == i;
         this.put(
            9 + 1 + i,
            Ui.named(icons[i], (on ? "> " : "") + RBET_NAMES[i], on ? ChatFormatting.GREEN : ChatFormatting.GOLD, i == 6 ? "Pays 36x" : "Pays 2x", on ? "SELECTED" : "Click to pick"),
            A_RBET + i
         );
      }

      long[] bets = this.casino.presets();
      for (int i = 0; i < bets.length; i++) {
         boolean on = bets[i] == sel.bet;
         this.put(27 + 2 + i, Ui.named(Items.GOLD_NUGGET, (on ? "> " : "") + eco().fmt(bets[i]), on ? ChatFormatting.GREEN : ChatFormatting.GOLD, on ? "SELECTED" : "Click to bet this"), A_BET + i);
      }

      this.put(
         45 + 4,
         Ui.named(Items.EMERALD, "SPIN - " + RBET_NAMES[sel.rbet], ChatFormatting.GREEN, "Bet: " + eco().fmt(sel.bet), "Win: " + eco().fmt(sel.bet * (sel.rbet == 6 ? 36L : 2L)) + " back"),
         A_START
      );
   }

   private String nameOf(UUID id) {
      ServerPlayer p = id == null ? null : this.viewer.level().getServer().getPlayerList().getPlayer(id);
      return p == null ? "?" : Msg.name(p);
   }

   // ---- result ----
   private int[] mine() {
      return this.who == 1 ? this.round.diceA : this.round.diceB;
   }

   private int[] theirs() {
      return this.who == 1 ? this.round.diceB : this.round.diceA;
   }

   private int mySide() {
      return this.who == 1 ? this.round.sideA : 1 - this.round.sideA;
   }

   private String rivalName() {
      return this.who == 1 ? this.round.nameB : this.round.nameA;
   }

   private static ItemStack face(int side) {
      return side == 0 ? Ui.named(Items.GOLD_INGOT, "Heads", ChatFormatting.GOLD) : Ui.named(Items.COPPER_INGOT, "Tails", ChatFormatting.GOLD);
   }

   private static ItemStack die(int value) {
      ItemStack s = Ui.named(Items.BONE_MEAL, "Die: " + value, ChatFormatting.WHITE);
      s.setCount(value);
      return s;
   }

   private boolean finished() {
      return this.ticks >= SPIN_TICKS + 8;
   }

   // European wheel order
   private static final int[] WHEEL = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
   private static final int STEPS = 46;

   /** tick at which wheel step k (1..STEPS) happens: fast first, then slower */
   private static int stepTick(int k) {
      int t = 0;
      for (int i = 1; i <= k; i++) {
         t += i <= 26 ? 1 : (i <= 36 ? 2 : (i <= 42 ? 3 : 5));
      }

      return t;
   }

   private static ItemStack pocket(int n) {
      boolean red = CasinoManager.isRed(n);
      ItemStack s = Ui.named(n == 0 ? Items.EMERALD : (red ? Items.REDSTONE : Items.COAL), (n == 0 ? "Green " : (red ? "Red " : "Black ")) + n, n == 0 ? ChatFormatting.GREEN : (red ? ChatFormatting.RED : ChatFormatting.GRAY));
      s.setCount(Math.max(1, n));
      return s;
   }

   private int wheelIndexOf(int n) {
      for (int i = 0; i < WHEEL.length; i++) {
         if (WHEEL[i] == n) {
            return i;
         }
      }

      return 0;
   }

   private void drawRoulette() {
      int steps = 0;
      while (steps < STEPS && stepTick(steps + 1) <= this.ticks) {
         steps++;
      }

      int start = Math.floorMod(this.wheelIndexOf(this.round.number) - STEPS, WHEEL.length);
      int centerIdx = (start + steps) % WHEEL.length;
      for (int c = 0; c < 9; c++) {
         this.view.setItem(2 * 9 + c, pocket(WHEEL[Math.floorMod(centerIdx + c - 4, WHEEL.length)]));
      }

      this.view.setItem(1 * 9 + 4, Ui.named(Items.ARROW, "Winning number", ChatFormatting.GOLD));
      this.view.setItem(3 * 9 + 4, Ui.named(Items.ARROW, "Winning number", ChatFormatting.GOLD));
   }

   private void drawResult() {
      CasinoManager.Round r = this.round;
      if (r.game == CasinoManager.Game.ROULETTE) {
         this.drawRoulette();
         if (this.ticks >= stepTick(STEPS) + 10) {
            this.finishBanner(r);
         }

         return;
      }

      boolean coin = r.game == CasinoManager.Game.COIN;
      ThreadLocalRandom rnd = ThreadLocalRandom.current();
      if (coin) {
         this.view.setItem(2 * 9 + 2, Ui.named(face(this.mySide()).getItem(), "You: " + (this.mySide() == 0 ? "Heads" : "Tails"), ChatFormatting.GREEN));
         this.view.setItem(2 * 9 + 6, Ui.named(face(1 - this.mySide()).getItem(), this.rivalName() + ": " + (this.mySide() == 0 ? "Tails" : "Heads"), ChatFormatting.RED));
         ItemStack center = this.ticks >= SPIN_TICKS ? face(r.coin) : face((this.ticks / (this.ticks < 30 ? 3 : 6)) % 2);
         this.view.setItem(2 * 9 + 4, center);
      } else {
         for (int k = 0; k < 2; k++) {
            boolean stopped = this.ticks >= 28 + k * 8;
            this.view.setItem(2 * 9 + 1 + k, stopped ? die(this.mine()[k]) : die(1 + rnd.nextInt(6)));
            boolean stoppedR = this.ticks >= 36 + k * 8;
            this.view.setItem(2 * 9 + 6 + k, stoppedR ? die(this.theirs()[k]) : die(1 + rnd.nextInt(6)));
         }

         if (this.ticks >= 28) {
            int a = this.ticks >= 36 ? this.mine()[0] + this.mine()[1] : 0;
            this.view.setItem(3 * 9 + 1, Ui.named(Items.PAPER, "Your total" + (this.ticks >= 36 ? ": " + a : ""), ChatFormatting.GREEN));
         }
      }

      if (this.finished()) {
         this.finishBanner(r);
      }
   }

   private void finishBanner(CasinoManager.Round r) {
         boolean win = r.winner == this.who;
         long net = r.payout - r.bet;
         if (r.winner == 0) {
            this.put(4 * 9 + 4, Ui.named(Items.CLOCK, "TIE - bet returned", ChatFormatting.YELLOW), 0);
         } else if (win) {
            this.put(4 * 9 + 4, Ui.named(Items.EMERALD, "YOU WIN!  +" + eco().fmt(net), ChatFormatting.GREEN, "Payout: " + eco().fmt(r.payout)), 0);
         } else {
            this.put(4 * 9 + 4, Ui.named(Items.BARRIER, "YOU LOSE  -" + eco().fmt(r.bet), ChatFormatting.RED), 0);
         }

         this.put(45 + 3, Ui.named(Items.EMERALD, "Play again", ChatFormatting.GREEN, "Same game, opponent and bet"), A_AGAIN);
         this.put(45 + 5, Ui.named(Items.BOOK, "Casino lobby", ChatFormatting.AQUA), A_BACK);
         this.announce();
   }

   private void sound(String id, float vol, float pitch) {
      MinecraftServer server = this.viewer.level().getServer();
      String cmd = "execute as " + this.viewer.getUUID() + " at @s run playsound " + id + " master @s ~ ~ ~ " + vol + " " + pitch;
      server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
   }

   private void announce() {
      if (!this.announced) {
         this.announced = true;
         CasinoManager.Round r = this.round;
         if (r.winner == 0) {
            Msg.colored(this.viewer, "Casino: tie, your bet was returned.", ChatFormatting.YELLOW);
            this.sound("block.note_block.bell", 0.8F, 0.8F);
         } else if (r.winner == this.who) {
            Msg.colored(this.viewer, "Casino: you won " + eco().fmt(r.payout - r.bet) + " (" + r.game.label + " vs " + this.rivalName() + ")!", ChatFormatting.GREEN);
            this.sound("entity.player.levelup", 1.0F, 1.0F);
            this.sound("entity.experience_orb.pickup", 1.0F, 1.2F);
            if (r.payout - r.bet >= 50000L) {
               Msg.broadcast(this.viewer.level().getServer(), Msg.name(this.viewer) + " won " + eco().fmt(r.payout - r.bet) + " at the casino!", ChatFormatting.GOLD);
            }
         } else {
            Msg.colored(this.viewer, "Casino: you lost " + eco().fmt(r.bet) + " (" + r.game.label + " vs " + this.rivalName() + ").", ChatFormatting.RED);
            this.sound("entity.villager.no", 1.0F, 1.0F);
         }
      }
   }

   private void tick() {
      if (this.screen == Screen.RESULT && !this.announced) {
         this.ticks++;
         boolean roulette = this.round.game == CasinoManager.Game.ROULETTE;
         if (roulette ? (this.ticks < stepTick(STEPS) && this.ticks % 2 == 0) : (this.ticks < SPIN_TICKS && this.ticks % 3 == 0)) {
            this.sound("block.note_block.hat", 0.5F, 0.7F + this.ticks / 60.0F);
         }

         this.refresh();
         if (this.announced) {
            ACTIVE.remove(this);
         }
      }
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      boolean pick = clickType.name().equals("PICKUP");
      if (!pick || slotId >= 54) {
         // the client moved an item in its own copy of the menu: re-open so everything matches again
         if (slotId >= 0 && this.screen != Screen.RESULT) {
            show(this.viewer, this.casino, this.screen, this.round, this.who, this.screen == Screen.LOBBY ? title(this.viewer, '', "Casino") : this.titleOfSetup());
         }

         return;
      }

      if (slotId >= 0 && this.action[slotId] != 0) {
         this.onAction(this.action[slotId]);
      }
   }

   private Component titleOfSetup() {
      CasinoManager.Selection sel = this.casino.selection(this.viewer.getUUID());
      return title(this.viewer, sel.game == CasinoManager.Game.COIN ? '' : '', "Casino - " + sel.game.label);
   }

   private void onAction(int a) {
      CasinoManager.Selection sel = this.casino.selection(this.viewer.getUUID());
      if (a == A_COIN || a == A_DICE || a == A_ROUL) {
         sel.game = a == A_COIN ? CasinoManager.Game.COIN : (a == A_DICE ? CasinoManager.Game.DICE : CasinoManager.Game.ROULETTE);
         openSetup(this.viewer, this.casino);
      } else if (a >= A_RBET && a < A_RBET + 7) {
         sel.rbet = a - A_RBET;
         this.refresh();
      } else if (a == A_BACK) {
         openLobby(this.viewer, this.casino);
      } else if (a == A_DEALER) {
         sel.opponent = null;
         this.refresh();
      } else if (a >= A_PLAYER && a < A_BET) {
         List<ServerPlayer> list = this.others();
         int i = a - A_PLAYER;
         if (i < list.size()) {
            sel.opponent = list.get(i).getUUID();
         }

         this.refresh();
      } else if (a >= A_BET && a < A_HEADS) {
         long[] bets = this.casino.presets();
         int i = a - A_BET;
         if (i < bets.length) {
            sel.bet = bets[i];
         }

         this.refresh();
      } else if (a == A_HEADS || a == A_TAILS) {
         sel.side = a == A_HEADS ? 0 : 1;
         this.refresh();
      } else if (a == A_START) {
         this.start(sel);
      } else if (a == A_AGAIN) {
         CasinoManager.Round r = this.round;
         sel.game = r.game;
         sel.bet = r.bet;
         sel.side = r.game == CasinoManager.Game.ROULETTE ? r.sideA : this.mySide();
         sel.rbet = r.sideA;
         sel.opponent = r.vsDealer ? null : (this.who == 1 ? r.b : r.a);
         this.start(sel);
      }
   }

   private void start(CasinoManager.Selection sel) {
      if (sel.game == CasinoManager.Game.ROULETTE) {
         sel.opponent = null;
      }

      if (sel.opponent == null) {
         CasinoManager.Round r = this.casino.playDealer(this.viewer, sel.game, sel.bet, sel.game == CasinoManager.Game.ROULETTE ? sel.rbet : sel.side);
         if (r != null) {
            openResult(this.viewer, this.casino, r, 1);
         }
      } else {
         ServerPlayer other = this.viewer.level().getServer().getPlayerList().getPlayer(sel.opponent);
         if (other == null) {
            Msg.err(this.viewer, "That player is no longer online.");
            sel.opponent = null;
         } else {
            this.casino.challenge(this.viewer, other, sel.game, sel.bet, sel.side);
            this.viewer.closeContainer();
         }
      }
   }

   public void removed(Player player) {
      super.removed(player);
      ACTIVE.remove(this);
      if (this.screen == Screen.RESULT && !this.announced) {
         this.ticks = 1000;
         this.announce();
      }
   }
}
