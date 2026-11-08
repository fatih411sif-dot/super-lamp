package com.simsmp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Gacha with 1, 3 or 5 pulls. Every pull is its own column: items roll down it and land in a box that gets a
 * rarity-coloured frame. Java players with the resource pack see the drawn panels; Bedrock gets plain items.
 */
public final class GachaMenu extends ChestMenu {
   private static final int ROWS = 6;
   private static final int SLOT_BALANCE = 0;
   private static final int SLOT_RATES = 8;
   private static final int[] PULL_OPTIONS = new int[]{1, 3, 5};
   private static final Set<GachaMenu.Session> ACTIVE = new HashSet<>();
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final GachaMenu.Session session;
   private final Gacha gacha;
   private final EconomyManager eco;
   private final SimConfig cfg;
   private final int[] slotPull = new int[ROWS * 9];

   /** the running (or finished) batch; survives the menu being re-opened to redraw the frames */
   private static final class Session {
      final List<Gacha.Prize> prizes;
      final int n;
      final int[] steps;
      final int[] total;
      final int[] wait;
      final boolean[] landed;
      final ItemStack[][] strip;
      boolean done;
      boolean granted;
      boolean switching;
      GachaMenu menu;

      Session(List<Gacha.Prize> prizes, Gacha gacha) {
         this.prizes = prizes;
         this.n = prizes.size();
         this.steps = new int[this.n];
         this.total = new int[this.n];
         this.wait = new int[this.n];
         this.landed = new boolean[this.n];
         this.strip = new ItemStack[this.n][3];

         for (int i = 0; i < this.n; i++) {
            this.total[i] = 22 + 8 * i;

            for (int r = 0; r < 3; r++) {
               this.strip[i][r] = gacha.randomIcon();
            }
         }
      }
   }

   private GachaMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, GachaMenu.Session session, Gacha gacha, EconomyManager eco, SimConfig cfg) {
      super(MenuType.GENERIC_9x6, id, inv, view, ROWS);
      this.view = view;
      this.viewer = viewer;
      this.session = session;
      this.gacha = gacha;
      this.eco = eco;
      this.cfg = cfg;
   }

   static boolean isBedrock(ServerPlayer p) {
      return p.getUUID().getMostSignificantBits() == 0L;
   }

   /** lattice column (0..8) of the first column for a batch of n: 1 -> 4, 3 -> 2, 5 -> 0 */
   private static int firstCol(int n) {
      return 4 - (n - 1);
   }

   /**
    * Resource-pack title: U+E500 = pull choice screen, U+E501 = result screen, then for every landed column a rarity
    * frame U+E520 + latticeColumn*8 + rarity (make_pack.py numbers them the same way).
    */
   private static Component titleFor(ServerPlayer p, EconomyManager eco, SimConfig cfg, GachaMenu.Session s) {
      if (isBedrock(p)) {
         return Component.literal("Gacha - " + eco.fmt(cfg.gachaPrice) + " per pull");
      }

      StringBuilder sb = new StringBuilder();
      sb.append('');
      if (s == null) {
         sb.append('');
      } else {
         sb.append('');
         int first = firstCol(s.n);

         for (int i = 0; i < s.n; i++) {
            if (s.landed[i]) {
               int lattice = (first + 2 * i) / 2;
               sb.append('').append((char)(0xE520 + lattice * 8 + s.prizes.get(i).rarity().ordinal()));
            }
         }
      }

      return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE);
   }

   public static void open(ServerPlayer player, MinecraftServer server, Gacha gacha, EconomyManager eco, SimConfig cfg) {
      if (gacha.isEmpty()) {
         Msg.err(player, "The gacha has no prizes right now.");
      } else {
         openWith(player, gacha, eco, cfg, null);
      }
   }

   private static void openWith(ServerPlayer player, Gacha gacha, EconomyManager eco, SimConfig cfg, GachaMenu.Session session) {
      SimpleContainer view = new SimpleContainer(ROWS * 9);
      if (session != null) {
         session.switching = true;
      }

      player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
         GachaMenu menu = new GachaMenu(id, inv, view, player, session, gacha, eco, cfg);
         if (session != null) {
            session.menu = menu;
         }

         menu.refresh();
         return menu;
      }, titleFor(player, eco, cfg, session)));
      if (session != null) {
         session.switching = false;
      }
   }

   public static void tickAll() {
      if (!ACTIVE.isEmpty()) {
         for (GachaMenu.Session s : new ArrayList<>(ACTIVE)) {
            s.menu.tick(s);
         }
      }
   }

   /** Plays a vanilla sound only for the viewer (via /playsound, so no version-specific sound classes are needed). */
   private void sound(String id, float volume, float pitch) {
      MinecraftServer server = this.viewer.level().getServer();
      String cmd = "execute as " + this.viewer.getUUID() + " at @s run playsound " + id + " master @s ~ ~ ~ " + volume + " " + pitch;
      server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
   }

   private void landSound(Gacha.Rarity rarity) {
      switch (rarity) {
         case COMMON -> this.sound("block.note_block.pling", 0.8F, 1.0F);
         case UNCOMMON -> this.sound("block.note_block.pling", 0.9F, 1.4F);
         case RARE -> {
            this.sound("entity.player.levelup", 0.8F, 1.2F);
         }
         case EPIC -> {
            this.sound("ui.toast.challenge_complete", 0.9F, 1.0F);
            this.sound("entity.firework_rocket.twinkle", 1.0F, 1.0F);
         }
         case LEGENDARY -> {
            this.sound("ui.toast.challenge_complete", 1.0F, 0.8F);
            this.sound("entity.firework_rocket.launch", 1.0F, 1.0F);
            this.sound("entity.firework_rocket.twinkle", 1.0F, 0.8F);
            this.sound("block.amethyst_block.chime", 1.0F, 0.6F);
         }
      }
   }

   private void refresh() {
      for (int i = 0; i < ROWS * 9; i++) {
         this.view.setItem(i, ItemStack.EMPTY);
         this.slotPull[i] = 0;
      }

      this.view.setItem(SLOT_BALANCE, Ui.named(Items.GOLD_INGOT, "Balance: " + this.eco.fmt(this.eco.get(this.viewer.getUUID())), ChatFormatting.GOLD));
      this.view.setItem(SLOT_RATES, this.ratesBook());
      Session s = this.session;
      if (s == null) {
         for (int i = 0; i < 3; i++) {
            int n = PULL_OPTIONS[i];

            for (int r = 2; r <= 3; r++) {
               for (int c = 0; c < 3; c++) {
                  this.slotPull[r * 9 + i * 3 + c] = n;
               }
            }

            this.view.setItem(18 + i * 3 + 1, this.pullButton(n));
         }

         return;
      }

      int first = firstCol(s.n);
      for (int i = 0; i < s.n; i++) {
         int col = first + 2 * i;
         for (int r = 0; r < 3; r++) {
            ItemStack stack = s.strip[i][r];
            if (r == 2 && s.landed[i]) {
               stack = shown(s.prizes.get(i));
            }

            this.view.setItem((1 + r) * 9 + col, stack.copy());
         }
      }

      if (s.done) {
         for (int i = 0; i < 3; i++) {
            int slot = 45 + 2 + 2 * i;
            this.slotPull[slot] = PULL_OPTIONS[i];
            this.view.setItem(slot, this.pullButton(PULL_OPTIONS[i]));
         }
      } else {
         this.view.setItem(49, Ui.named(Items.BARRIER, "Rolling...", ChatFormatting.RED));
      }
   }

   private ItemStack pullButton(int n) {
      ItemStack s = Ui.named(
         Items.EMERALD,
         "Pull x" + n + " - " + this.eco.fmt(this.cfg.gachaPrice * n),
         ChatFormatting.GREEN,
         "Click to pull " + n + (n == 1 ? " time" : " times"),
         "Rare prizes have a small chance"
      );
      s.setCount(n);
      return s;
   }

   private static ItemStack shown(Gacha.Prize p) {
      ItemStack copy = p.stack().copy();
      copy.set(
         DataComponents.LORE, new ItemLore(List.of(Component.literal(p.rarity().label).withStyle(p.rarity().color).withStyle(st -> st.withItalic(false))))
      );
      return copy;
   }

   private ItemStack ratesBook() {
      List<String> lines = new ArrayList<>();

      for (Entry<Gacha.Rarity, Double> e : this.gacha.rates().entrySet()) {
         if (!(e.getValue() <= 0.0)) {
            lines.add(e.getKey().label + ": " + String.format(Locale.US, "%.2f", e.getValue()) + "%");
         }
      }

      return Ui.named(Items.BOOK, "Drop rates", ChatFormatting.AQUA, lines.toArray(new String[0]));
   }

   private void startPull(int n) {
      if (this.session != null && !this.session.done) {
         return;
      }

      long cost = this.cfg.gachaPrice * n;
      if (!this.eco.take(this.viewer.getUUID(), cost)) {
         Msg.err(this.viewer, "You need " + this.eco.fmt(cost) + " but only have " + this.eco.fmt(this.eco.get(this.viewer.getUUID())) + ".");
         return;
      }

      List<Gacha.Prize> rolled = new ArrayList<>();
      for (int i = 0; i < n; i++) {
         Gacha.Prize p = this.gacha.roll();
         if (p != null) {
            rolled.add(p);
         }
      }

      if (rolled.size() < n) {
         this.eco.add(this.viewer.getUUID(), this.cfg.gachaPrice * (n - rolled.size()));
      }

      if (rolled.isEmpty()) {
         Msg.err(this.viewer, "The gacha has no prizes right now.");
         return;
      }

      this.sound("entity.experience_orb.pickup", 0.8F, 0.8F);
      Session next = new Session(rolled, this.gacha);
      ACTIVE.add(next);
      // always a fresh menu: the result screen has a different background
      openWith(this.viewer, this.gacha, this.eco, this.cfg, next);
   }

   private void tick(Session s) {
      boolean newlyLanded = false;
      boolean stepped = false;
      int fastest = 0;

      for (int i = 0; i < s.n; i++) {
         if (s.landed[i]) {
            continue;
         }

         if (s.wait[i] > 0) {
            s.wait[i]--;
            continue;
         }

         s.steps[i]++;
         stepped = true;
         fastest = Math.max(fastest, s.steps[i]);
         s.strip[i][2] = s.strip[i][1];
         s.strip[i][1] = s.strip[i][0];
         int left = s.total[i] - s.steps[i];
         s.strip[i][0] = left == 1 ? s.prizes.get(i).stack().copy() : this.gacha.randomIcon();
         s.wait[i] = left > 10 ? 0 : (left > 5 ? 1 : (left > 2 ? 2 : 4));
         if (left <= 0) {
            s.landed[i] = true;
            newlyLanded = true;
            this.landSound(s.prizes.get(i).rarity());
         }
      }

      if (stepped && fastest % 2 == 0 && !newlyLanded) {
         this.sound("block.note_block.hat", 0.5F, 0.7F + Math.min(1.2F, fastest / 30.0F));
      }

      boolean allLanded = true;
      for (boolean b : s.landed) {
         allLanded &= b;
      }

      if (allLanded) {
         s.done = true;
         ACTIVE.remove(s);
         this.grant(s);
      }

      if (newlyLanded) {
         if (isBedrock(this.viewer)) {
            this.refresh();
         } else {
            openWith(this.viewer, this.gacha, this.eco, this.cfg, s);
         }
      } else {
         this.refresh();
      }
   }

   private void grant(Session s) {
      if (!s.granted) {
         s.granted = true;

         for (Gacha.Prize prize : s.prizes) {
            ItemGiver.give(this.viewer, prize.stack().copy());
            String name = prize.stack().getCount() + "x " + prize.stack().getHoverName().getString();
            Msg.colored(this.viewer, "You got " + name + " (" + prize.rarity().label + ")!", prize.rarity().color);
            if (prize.rarity().ordinal() >= Gacha.Rarity.EPIC.ordinal()) {
               MinecraftServer server = this.viewer.level().getServer();
               Msg.broadcast(server, Msg.name(this.viewer) + " pulled " + name + " from the Gacha! (" + prize.rarity().label + ")", prize.rarity().color);
            }
         }
      }
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      if (clickType == ContainerInput.PICKUP && slotId >= 0 && slotId < ROWS * 9 && this.slotPull[slotId] > 0) {
         this.startPull(this.slotPull[slotId]);
      }
   }

   public void removed(Player player) {
      super.removed(player);
      Session s = this.session;
      if (s != null && !s.switching && s.menu == this) {
         // closed during the roll: finish at once and give everything
         ACTIVE.remove(s);
         s.done = true;
         this.grant(s);
      }
   }
}
