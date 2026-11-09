package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Casino: Coin Flip and Dice Duel, against the dealer (NPC) or against another player.
 * Settings: config/sim_smp/casino.properties. All money moves through EconomyManager.
 */
public final class CasinoManager {
   public enum Game {
      COIN("Coin Flip"),
      DICE("Dice Duel"),
      ROULETTE("Roulette");

      public final String label;

      private Game(String label) {
         this.label = label;
      }
   }

   /** What one finished game looked like (from player A's point of view; B is the dealer when {@code vsDealer}). */
   public static final class Round {
      public Game game;
      public long bet;
      public boolean vsDealer;
      public UUID a;
      public UUID b;
      public String nameA;
      public String nameB;
      public int sideA;      // coin: 0 heads, 1 tails
      public int coin;       // coin result
      public int number;     // roulette result 0-36 (sideA = the bet type: 0 red, 1 black, 2 even, 3 odd, 4 1-18, 5 19-36, 6 zero)
      public int[] diceA = new int[2];
      public int[] diceB = new int[2];
      /** 0 tie, 1 = A wins, 2 = B wins */
      public int winner;
      /** total money paid out to the winner (or the refund per player on a tie) */
      public long payout;
   }

   public static final class Selection {
      public Game game = Game.COIN;
      public UUID opponent;     // null = dealer
      public long bet;
      public int side;
      public int rbet;
   }

   private static final class Challenge {
      UUID from;
      Game game;
      long bet;
      int side;
      long expires;
   }

   private final Path file;
   private final EconomyManager eco;
   private boolean enabled = true;
   private long minBet = 100L;
   private long maxBet = 100000L;
   private int feePercent = 5;
   private int dealerPercent = 195;
   private int challengeSeconds = 60;
   private long[] presets = new long[]{100L, 500L, 1000L, 5000L, 10000L};
   private final Map<UUID, Selection> selections = new HashMap<>();
   private final Map<UUID, Challenge> challenges = new HashMap<>();

   public CasinoManager(Path dir, EconomyManager eco) {
      this.file = dir.resolve("casino.properties");
      this.eco = eco;
   }

   public void load() {
      try {
         Files.createDirectories(this.file.getParent());
         Properties p = new Properties();
         if (Files.exists(this.file)) {
            try (Reader r = Files.newBufferedReader(this.file)) {
               p.load(r);
            }

            this.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            this.minBet = Long.parseLong(p.getProperty("minBet", "100").trim());
            this.maxBet = Long.parseLong(p.getProperty("maxBet", "100000").trim());
            this.feePercent = Integer.parseInt(p.getProperty("feePercent", "5").trim());
            this.dealerPercent = Integer.parseInt(p.getProperty("dealerPayoutPercent", "195").trim());
            this.challengeSeconds = Integer.parseInt(p.getProperty("challengeSeconds", "60").trim());
            String[] parts = p.getProperty("bets", "100,500,1000,5000,10000").split(",");
            long[] out = new long[Math.min(5, parts.length)];

            for (int i = 0; i < out.length; i++) {
               out[i] = Long.parseLong(parts[i].trim());
            }

            this.presets = out;
         } else {
            p.setProperty("enabled", "true");
            p.setProperty("minBet", "100");
            p.setProperty("maxBet", "100000");
            p.setProperty("bets", "100,500,1000,5000,10000");
            p.setProperty("feePercent", "5");
            p.setProperty("dealerPayoutPercent", "195");
            p.setProperty("challengeSeconds", "60");
            try (Writer w = Files.newBufferedWriter(this.file)) {
               p.store(w, "SIM SMP casino: bets = up to 5 quick bets in the menu, feePercent = cut of the pot in player-vs-player, dealerPayoutPercent = total returned when you beat the dealer (195 = 1.95x)");
            }
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read casino.properties, using defaults", e);
      }
   }

   public long[] presets() {
      return this.presets;
   }

   public long minBet() {
      return this.minBet;
   }

   public long maxBet() {
      return this.maxBet;
   }

   public int dealerPercent() {
      return this.dealerPercent;
   }

   public int feePercent() {
      return this.feePercent;
   }

   public EconomyManager eco() {
      return this.eco;
   }

   public boolean enabled() {
      return this.enabled;
   }

   public Selection selection(UUID id) {
      return this.selections.computeIfAbsent(id, k -> {
         Selection s = new Selection();
         s.bet = this.presets.length > 0 ? this.presets[0] : this.minBet;
         return s;
      });
   }

   private String validateBet(ServerPlayer p, long bet) {
      if (bet < this.minBet || bet > this.maxBet) {
         return "Bets must be between " + this.eco.fmt(this.minBet) + " and " + this.eco.fmt(this.maxBet) + ".";
      } else if (this.eco.get(p.getUUID()) < bet) {
         return "You only have " + this.eco.fmt(this.eco.get(p.getUUID())) + ".";
      } else {
         return null;
      }
   }

   private static int[] roll2() {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      return new int[]{1 + r.nextInt(6), 1 + r.nextInt(6)};
   }

   private static int sum(int[] d) {
      return d[0] + d[1];
   }

   public static boolean isRed(int n) {
      return switch (n) {
         case 1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36 -> true;
         default -> false;
      };
   }

   public static boolean roulettePays(int bet, int n) {
      if (n == 0) {
         return bet == 6;
      }

      return switch (bet) {
         case 0 -> isRed(n);
         case 1 -> !isRed(n);
         case 2 -> n % 2 == 0;
         case 3 -> n % 2 == 1;
         case 4 -> n <= 18;
         case 5 -> n >= 19;
         default -> false;
      };
   }

   private Round roll(Game game, long bet, int sideA) {
      Round r = new Round();
      r.game = game;
      r.bet = bet;
      r.sideA = sideA;
      if (game == Game.ROULETTE) {
         r.number = ThreadLocalRandom.current().nextInt(37);
         r.winner = roulettePays(sideA, r.number) ? 1 : 2;
      } else if (game == Game.COIN) {
         r.coin = ThreadLocalRandom.current().nextInt(2);
         r.winner = r.coin == sideA ? 1 : 2;
      } else {
         r.diceA = roll2();
         r.diceB = roll2();
         r.winner = Integer.compare(sum(r.diceA), sum(r.diceB)) == 0 ? 0 : (sum(r.diceA) > sum(r.diceB) ? 1 : 2);
      }

      return r;
   }

   /** Plays against the dealer. Returns the round, or null after telling the player why not. */
   public Round playDealer(ServerPlayer p, Game game, long bet, int side) {
      String problem = this.validateBet(p, bet);
      if (!this.enabled) {
         problem = "The casino is closed.";
      }

      if (problem != null) {
         Msg.err(p, problem);
         return null;
      } else if (!this.eco.take(p.getUUID(), bet)) {
         Msg.err(p, "You can't afford that bet.");
         return null;
      } else {
         Round r = this.roll(game, bet, side);
         r.vsDealer = true;
         r.a = p.getUUID();
         r.nameA = Msg.name(p);
         r.nameB = "Dealer";
         if (r.winner == 1) {
            r.payout = game == Game.ROULETTE ? bet * (side == 6 ? 36L : 2L) : bet * this.dealerPercent / 100L;
            this.eco.add(p.getUUID(), r.payout);
         } else if (r.winner == 0) {
            r.payout = bet;
            this.eco.add(p.getUUID(), bet);
         }

         return r;
      }
   }

   /** Sends a challenge to another player (the challenger's bet is held until it is accepted, declined or expires). */
   public void challenge(ServerPlayer from, ServerPlayer to, Game game, long bet, int side) {
      String problem = this.validateBet(from, bet);
      if (!this.enabled) {
         problem = "The casino is closed.";
      } else if (from.getUUID().equals(to.getUUID())) {
         problem = "You can't challenge yourself.";
      } else if (this.challenges.containsKey(to.getUUID())) {
         problem = Msg.name(to) + " already has a challenge waiting.";
      } else if (this.challenges.values().stream().anyMatch(c -> c.from.equals(from.getUUID()))) {
         problem = "You already have a challenge waiting.";
      }

      if (problem != null) {
         Msg.err(from, problem);
      } else if (!this.eco.take(from.getUUID(), bet)) {
         Msg.err(from, "You can't afford that bet.");
      } else {
         Challenge c = new Challenge();
         c.from = from.getUUID();
         c.game = game;
         c.bet = bet;
         c.side = side;
         c.expires = System.currentTimeMillis() + this.challengeSeconds * 1000L;
         this.challenges.put(to.getUUID(), c);
         Msg.ok(from, "Challenge sent to " + Msg.name(to) + " (" + game.label + ", " + this.eco.fmt(bet) + "). Your bet is held for " + this.challengeSeconds + "s.");
         String sideText = game == Game.COIN ? " You get " + (side == 0 ? "Tails" : "Heads") + "." : "";
         Msg.colored(
            to,
            Msg.name(from) + " challenges you to " + game.label + " for " + this.eco.fmt(bet) + "!" + sideText + " Type /casino accept or /casino decline.",
            ChatFormatting.GOLD
         );
      }
   }

   public void accept(ServerPlayer to) {
      Challenge c = this.challenges.get(to.getUUID());
      MinecraftServer server = to.level().getServer();
      ServerPlayer from = c == null ? null : server.getPlayerList().getPlayer(c.from);
      if (c == null) {
         Msg.err(to, "Nobody has challenged you.");
      } else if (from == null) {
         this.challenges.remove(to.getUUID());
         this.eco.add(c.from, c.bet);
         Msg.err(to, "That player is no longer online.");
      } else if (!this.eco.take(to.getUUID(), c.bet)) {
         Msg.err(to, "You need " + this.eco.fmt(c.bet) + " to accept.");
      } else {
         this.challenges.remove(to.getUUID());
         Round r = this.roll(c.game, c.bet, c.side);
         r.vsDealer = false;
         r.a = from.getUUID();
         r.b = to.getUUID();
         r.nameA = Msg.name(from);
         r.nameB = Msg.name(to);
         long pot = c.bet * 2L;
         if (r.winner == 0) {
            r.payout = c.bet;
            this.eco.add(r.a, c.bet);
            this.eco.add(r.b, c.bet);
         } else {
            r.payout = pot - pot * this.feePercent / 100L;
            this.eco.add(r.winner == 1 ? r.a : r.b, r.payout);
         }

         CasinoMenu.openResult(from, this, r, 1);
         CasinoMenu.openResult(to, this, r, 2);
      }
   }

   public void decline(ServerPlayer to) {
      Challenge c = this.challenges.remove(to.getUUID());
      if (c == null) {
         Msg.err(to, "Nobody has challenged you.");
      } else {
         this.eco.add(c.from, c.bet);
         Msg.info(to, "Challenge declined.");
         ServerPlayer from = to.level().getServer().getPlayerList().getPlayer(c.from);
         if (from != null) {
            Msg.info(from, Msg.name(to) + " declined your challenge. Your bet was returned.");
         }
      }
   }

   /** once per second: expire old challenges */
   public void tick(MinecraftServer server) {
      if (!this.challenges.isEmpty()) {
         long now = System.currentTimeMillis();
         Iterator<Map.Entry<UUID, Challenge>> it = this.challenges.entrySet().iterator();

         while (it.hasNext()) {
            Map.Entry<UUID, Challenge> e = it.next();
            if (now >= e.getValue().expires) {
               it.remove();
               this.eco.add(e.getValue().from, e.getValue().bet);
               ServerPlayer from = server.getPlayerList().getPlayer(e.getValue().from);
               if (from != null) {
                  Msg.info(from, "Your challenge expired. Your bet was returned.");
               }
            }
         }
      }
   }

   public void playerLeft(ServerPlayer p) {
      Challenge own = this.challenges.remove(p.getUUID());
      if (own != null) {
         this.eco.add(own.from, own.bet);
      }

      List<UUID> toRemove = new ArrayList<>();
      for (Map.Entry<UUID, Challenge> e : this.challenges.entrySet()) {
         if (e.getValue().from.equals(p.getUUID())) {
            toRemove.add(e.getKey());
            this.eco.add(p.getUUID(), e.getValue().bet);
         }
      }

      toRemove.forEach(this.challenges::remove);
   }

   public void register(CommandDispatcher<CommandSourceStack> d) {
      LiteralArgumentBuilder<CommandSourceStack> casino = Commands.literal("casino");
      casino.executes(ctx -> {
         CasinoMenu.openLobby(ctx.getSource().getPlayerOrException(), this);
         return 1;
      });
      casino.then(Commands.literal("accept").executes(ctx -> {
         this.accept(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      casino.then(Commands.literal("decline").executes(ctx -> {
         this.decline(ctx.getSource().getPlayerOrException());
         return 1;
      }));
      // /casino challenge <player> <coin|dice> <bet>
      casino.then(
         Commands.literal("challenge")
            .then(
               Commands.argument("player", EntityArgument.player())
                  .then(
                     Commands.argument("game", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("coin", "dice"), b))
                        .then(Commands.argument("bet", LongArgumentType.longArg(1L)).executes(ctx -> {
                           ServerPlayer from = ctx.getSource().getPlayerOrException();
                           ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
                           String g = StringArgumentType.getString(ctx, "game").toLowerCase();
                           Game game = g.equals("dice") ? Game.DICE : Game.COIN;
                           this.challenge(from, to, game, LongArgumentType.getLong(ctx, "bet"), ThreadLocalRandom.current().nextInt(2));
                           return 1;
                        }))
                  )
            )
      );
      // /casino roulette <red|black|even|odd|low|high|zero> <bet>
      casino.then(
         Commands.literal("roulette")
            .then(
               Commands.argument("bet", StringArgumentType.word())
                  .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("red", "black", "even", "odd", "low", "high", "zero"), b))
                  .then(Commands.argument("amount", LongArgumentType.longArg(1L)).executes(ctx -> {
                     ServerPlayer p = ctx.getSource().getPlayerOrException();
                     int type = List.of("red", "black", "even", "odd", "low", "high", "zero").indexOf(StringArgumentType.getString(ctx, "bet").toLowerCase());
                     if (type < 0) {
                        Msg.err(p, "Bet on red, black, even, odd, low, high or zero.");
                        return 0;
                     }

                     Round r = this.playDealer(p, Game.ROULETTE, LongArgumentType.getLong(ctx, "amount"), type);
                     if (r != null) {
                        CasinoMenu.openResult(p, this, r, 1);
                     }

                     return 1;
                  }))
            )
      );
      d.register(casino);
   }
}
