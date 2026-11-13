package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Game-only "drug farms" (made-up crops, no real recipes): weed from ferns, cocaine from ripe sweet berry bushes,
 * opium from poppies, meth from amethyst clusters. The items are named vanilla items tagged with custom data,
 * and /sell buys them from the system (price follows the economy index).
 */
public final class DrugManager {
   public static final class Drug {
      final String id;
      final String name;
      final ChatFormatting color;
      final Item item;
      long price;
      double chance;

      Drug(String id, String name, ChatFormatting color, Item item, long price, double chance) {
         this.id = id;
         this.name = name;
         this.color = color;
         this.item = item;
         this.price = price;
         this.chance = chance;
      }

      String key() {
         return "sim_drug_" + this.id;
      }
   }

   private static final Drug[] DRUGS = {
      new Drug("weed", "Weed", ChatFormatting.GREEN, Items.DRIED_KELP, 120L, 0.35),
      new Drug("cocaine", "Cocaine", ChatFormatting.WHITE, Items.SUGAR, 400L, 0.60),
      new Drug("opium", "Opium", ChatFormatting.DARK_RED, Items.CLAY_BALL, 250L, 0.50),
      new Drug("meth", "Meth", ChatFormatting.AQUA, Items.PRISMARINE_CRYSTALS, 600L, 0.70)
   };

   private DrugManager() {
   }

   public static void load(Path dir) {
      Path f = dir.resolve("drugs.properties");
      try {
         Properties p = new Properties();
         if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
               p.load(r);
            }
         } else {
            for (Drug d : DRUGS) {
               p.setProperty(d.id + ".price", Long.toString(d.price));
               p.setProperty(d.id + ".chance", Double.toString(d.chance));
            }

            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(f)) {
               p.store(w, "Drug farms. price = base /sell price per item, chance = drop chance (0-1) when harvesting the plant.");
            }
         }

         for (Drug d : DRUGS) {
            d.price = Long.parseLong(p.getProperty(d.id + ".price", Long.toString(d.price)).trim());
            d.chance = Double.parseDouble(p.getProperty(d.id + ".chance", Double.toString(d.chance)).trim());
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read drugs.properties (keeping defaults)", e);
      }
   }

   public static ItemStack make(Drug d, int count) {
      ItemStack st = new ItemStack(d.item, count);
      st.set(DataComponents.CUSTOM_NAME, Component.literal(d.name).withStyle(d.color).withStyle(s -> s.withItalic(false)));
      CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.putBoolean(d.key(), true));
      return st;
   }

   public static Drug of(ItemStack st) {
      CustomData data = (CustomData)st.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      for (Drug d : DRUGS) {
         if (data.copyTag().contains(d.key())) {
            return d;
         }
      }

      return null;
   }

   /** the key used for the economy engine and messages */
   public static String keyOf(Drug d) {
      return "sim_smp:" + d.id;
   }

   public static long basePrice(Drug d) {
      return d.price;
   }

   private static Drug forBlock(BlockState s) {
      if (s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)) {
         return DRUGS[0];
      }

      if (s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) >= 3) {
         return DRUGS[1];
      }

      if (s.is(Blocks.POPPY)) {
         return DRUGS[2];
      }

      if (s.is(Blocks.AMETHYST_CLUSTER)) {
         return DRUGS[3];
      }

      return null;
   }

   /** called after a block was broken */
   public static void onBreak(ServerPlayer p, Level level, BlockPos pos, BlockState state) {
      if (p.isCreative()) {
         return;
      }

      Drug d = forBlock(state);
      if (d == null || p.getRandom().nextDouble() >= d.chance) {
         return;
      }

      int n = 1 + p.getRandom().nextInt(2);
      ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, make(d, n));
      level.addFreshEntity(e);
   }

   public static void register(CommandDispatcher<CommandSourceStack> dsp, EconomyManager eco) {
      dsp.register(Commands.literal("drugs").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, "Farms (game items, sell them with /sell):");
         String[] src = {"break ferns", "break ripe sweet berry bushes", "break poppies", "break amethyst clusters"};
         for (int i = 0; i < DRUGS.length; i++) {
            Drug d = DRUGS[i];
            PriceEngine pe = PriceEngine.get();
            long now = pe == null ? d.price : pe.effective(keyOf(d), d.price);
            Msg.info(p, d.name + ": " + src[i] + " - sells for " + eco.fmt(now) + " each.");
         }

         return 1;
      }));
   }
}
