package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Game-only "drug farms" (made-up crops, no real recipes): weed from ferns, cocaine from ripe sweet berry bushes,
 * opium from poppies, meth from amethyst clusters. Harvesting gives a RAW item (cannot be sold); the raw item must be
 * smelted in a furnace / blast furnace / smoker (right-click it holding the raw item, costs 1 coal) into the processed item, and only the processed item can be sold with /sell.
 * Raw and processed items use different vanilla base items, names and lore so they look different.
 */
public final class DrugManager {
   public static final class Drug {
      final String id;
      final String name;
      final ChatFormatting color;
      final Item item;
      final Item rawItem;
      long price;
      double chance;
      int ratio;
      int seconds;

      Drug(String id, String name, ChatFormatting color, Item item, Item rawItem, long price, double chance) {
         this.id = id;
         this.name = name;
         this.color = color;
         this.item = item;
         this.rawItem = rawItem;
         this.price = price;
         this.chance = chance;
         this.ratio = 2;
         this.seconds = 20;
      }

      String key() {
         return "sim_drug_" + this.id;
      }

      String rawKey() {
         return "sim_drug_raw_" + this.id;
      }
   }

   private static final Drug[] DRUGS = {
      new Drug("weed", "Weed", ChatFormatting.GREEN, Items.DRIED_KELP, Items.GREEN_DYE, 120L, 0.35),
      new Drug("cocaine", "Cocaine", ChatFormatting.WHITE, Items.SUGAR, Items.WHITE_DYE, 400L, 0.60),
      new Drug("opium", "Opium", ChatFormatting.DARK_RED, Items.CLAY_BALL, Items.RED_DYE, 250L, 0.50),
      new Drug("meth", "Meth", ChatFormatting.AQUA, Items.PRISMARINE_CRYSTALS, Items.AMETHYST_SHARD, 600L, 0.70)
   };

   private DrugManager() {
   }

   private static final class Job {
      String dim;
      int x;
      int y;
      int z;
      UUID owner;
      String drugId;
      int out;
      long endAt;
      boolean litSet;
   }

   private static final Map<String, Job> JOBS = new HashMap<>();
   private static Path jobsFile;

   private static Drug byId(String id) {
      for (Drug d : DRUGS) {
         if (d.id.equals(id)) {
            return d;
         }
      }

      return null;
   }

   private static String jobKey(ServerLevel level, BlockPos pos) {
      return level.dimension() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
   }

   private static void saveJobs() {
      if (jobsFile == null) {
         return;
      }

      Properties p = new Properties();
      int i = 0;
      for (Job j : JOBS.values()) {
         p.setProperty("job." + i++, String.join("|", j.dim, Integer.toString(j.x), Integer.toString(j.y), Integer.toString(j.z),
            j.owner.toString(), j.drugId, Integer.toString(j.out), Long.toString(j.endAt), Boolean.toString(j.litSet)));
      }

      try {
         Files.createDirectories(jobsFile.getParent());
         try (Writer w = Files.newBufferedWriter(jobsFile)) {
            p.store(w, "Drugs being processed in furnaces (survive restarts).");
         }
      } catch (IOException e) {
         SimSmp.LOGGER.error("Could not save drug_jobs.properties", e);
      }
   }

   private static void loadJobs() {
      JOBS.clear();
      if (jobsFile == null || !Files.exists(jobsFile)) {
         return;
      }

      try (Reader r = Files.newBufferedReader(jobsFile)) {
         Properties p = new Properties();
         p.load(r);
         for (String k : p.stringPropertyNames()) {
            try {
               String[] f = p.getProperty(k).split("\\|");
               // dim contains no '|', so the layout is fixed: dim|x|y|z|owner|drug|out|endAt|lit
               Job j = new Job();
               j.dim = f[0];
               j.x = Integer.parseInt(f[1]);
               j.y = Integer.parseInt(f[2]);
               j.z = Integer.parseInt(f[3]);
               j.owner = UUID.fromString(f[4]);
               j.drugId = f[5];
               j.out = Integer.parseInt(f[6]);
               j.endAt = Long.parseLong(f[7]);
               j.litSet = Boolean.parseBoolean(f[8]);
               JOBS.put(j.dim + "|" + j.x + "|" + j.y + "|" + j.z, j);
            } catch (RuntimeException e) {
               SimSmp.LOGGER.warn("Skipping bad drug job entry {}", k);
            }
         }
      } catch (IOException e) {
         SimSmp.LOGGER.error("Could not read drug_jobs.properties", e);
      }
   }

   public static void save() {
      saveJobs();
   }

   private static boolean isFurnace(BlockState s) {
      return s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER);
   }

   /** right-click a furnace / blast furnace / smoker holding raw drugs (sneak to open the furnace normally) */
   public static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
      if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown() || player.isSpectator()) {
         return InteractionResult.PASS;
      }

      ItemStack held = player.getItemInHand(hand);
      Drug d = rawOf(held);
      if (d == null) {
         return InteractionResult.PASS;
      }

      BlockPos pos = hit.getBlockPos();
      BlockState st = level.getBlockState(pos);
      if (!isFurnace(st)) {
         return InteractionResult.PASS;
      }

      if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
         return InteractionResult.SUCCESS; // client side: just swallow the click so the furnace screen doesn't open
      }

      String key = jobKey(sl, pos);
      Job busy = JOBS.get(key);
      if (busy != null) {
         long left = Math.max(0L, (busy.endAt - System.currentTimeMillis() + 999L) / 1000L);
         Msg.err(sp, "This furnace is busy" + (left > 0L ? " (" + left + "s left)." : ", waiting for its owner to collect."));
         return InteractionResult.SUCCESS;
      }

      int use = Math.min(held.getCount(), 64);
      use -= use % d.ratio;
      if (use <= 0) {
         Msg.err(sp, "You need at least " + d.ratio + " Raw " + d.name + " for one batch.");
         return InteractionResult.SUCCESS;
      }

      ItemStack fuel = null;
      for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
         ItemStack s = sp.getInventory().getItem(i);
         if (!s.isEmpty() && (s.is(Items.COAL) || s.is(Items.CHARCOAL))) {
            fuel = s;
            break;
         }
      }

      if (fuel == null) {
         Msg.err(sp, "You need 1 coal or charcoal as fuel in your inventory.");
         return InteractionResult.SUCCESS;
      }

      fuel.shrink(1);
      held.shrink(use);
      Job j = new Job();
      j.dim = sl.dimension().toString();
      j.x = pos.getX();
      j.y = pos.getY();
      j.z = pos.getZ();
      j.owner = sp.getUUID();
      j.drugId = d.id;
      j.out = use / d.ratio;
      j.endAt = System.currentTimeMillis() + d.seconds * 1000L;
      if (st.hasProperty(BlockStateProperties.LIT) && !st.getValue(BlockStateProperties.LIT)) {
         sl.setBlock(pos, st.setValue(BlockStateProperties.LIT, true), 3);
         j.litSet = true;
      }

      JOBS.put(key, j);
      saveJobs();
      Msg.ok(sp, "Processing " + use + "x Raw " + d.name + " -> " + j.out + "x " + d.name + ". Ready in " + d.seconds + "s, it will be delivered to you.");
      return InteractionResult.SUCCESS;
   }

   /** called once per second */
   public static void tick(MinecraftServer server) {
      if (JOBS.isEmpty()) {
         return;
      }

      long now = System.currentTimeMillis();
      boolean dirty = false;
      for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext(); ) {
         Job j = it.next();
         ServerLevel sl = null;
         for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().toString().equals(j.dim)) {
               sl = l;
               break;
            }
         }

         BlockPos pos = new BlockPos(j.x, j.y, j.z);
         boolean loaded = sl != null && sl.isLoaded(pos);
         if (now < j.endAt) {
            if (loaded) {
               sl.sendParticles(ParticleTypes.SMOKE, j.x + 0.5, j.y + 1.1, j.z + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
               sl.playSound(null, pos, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.0F);
            }

            continue;
         }

         ServerPlayer owner = server.getPlayerList().getPlayer(j.owner);
         Drug d = byId(j.drugId);
         if (d == null) {
            it.remove();
            dirty = true;
            continue;
         }

         if (owner == null) {
            continue; // done, but wait until the owner is online
         }

         for (int left = j.out; left > 0; ) {
            int n = Math.min(64, left);
            ItemGiver.give(owner, make(d, n));
            left -= n;
         }

         Msg.ok(owner, "Your " + j.out + "x " + d.name + " finished processing. Sell it with /sell.");
         if (loaded && j.litSet) {
            BlockState st = sl.getBlockState(pos);
            if (isFurnace(st) && st.hasProperty(BlockStateProperties.LIT)) {
               sl.setBlock(pos, st.setValue(BlockStateProperties.LIT, false), 3);
            }
         }

         it.remove();
         dirty = true;
      }

      if (dirty) {
         saveJobs();
      }
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
               p.setProperty(d.id + ".ratio", Integer.toString(d.ratio));
               p.setProperty(d.id + ".seconds", Integer.toString(d.seconds));
            }

            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(f)) {
               p.store(w, "Drug farms. price = base /sell price per item, chance = drop chance (0-1) when harvesting the plant, ratio = raw items needed for 1 processed item, seconds = furnace processing time per batch.");
            }
         }

         for (Drug d : DRUGS) {
            d.price = Long.parseLong(p.getProperty(d.id + ".price", Long.toString(d.price)).trim());
            d.chance = Double.parseDouble(p.getProperty(d.id + ".chance", Double.toString(d.chance)).trim());
            d.ratio = Math.max(1, Integer.parseInt(p.getProperty(d.id + ".ratio", Integer.toString(d.ratio)).trim()));
            d.seconds = Math.max(1, Integer.parseInt(p.getProperty(d.id + ".seconds", Integer.toString(d.seconds)).trim()));
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read drugs.properties (keeping defaults)", e);
      }

      jobsFile = dir.resolve("drug_jobs.properties");
      loadJobs();
   }

   public static ItemStack make(Drug d, int count) {
      ItemStack st = new ItemStack(d.item, count);
      st.set(DataComponents.CUSTOM_NAME, Component.literal(d.name).withStyle(d.color).withStyle(s -> s.withItalic(false)));
      ItemLook.apply(st, d.id);
      st.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("Processed. Sell it with /sell.").withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false)))));
      CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.putBoolean(d.key(), true));
      return st;
   }

   /** the unprocessed harvest: different base item and name, cannot be sold until smelted in a furnace */
   public static ItemStack makeRaw(Drug d, int count) {
      ItemStack st = new ItemStack(d.rawItem, count);
      st.set(DataComponents.CUSTOM_NAME, Component.literal("Raw " + d.name).withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false)));
      ItemLook.apply(st, "raw_" + d.id);
      st.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("Unprocessed - can't be sold.").withStyle(ChatFormatting.DARK_GRAY).withStyle(s -> s.withItalic(false)),
         Component.literal("Smelt it in a furnace (right-click it).").withStyle(ChatFormatting.DARK_GRAY).withStyle(s -> s.withItalic(false)))));
      CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.putBoolean(d.rawKey(), true));
      return st;
   }

   /** the raw drug this stack is, or null */
   public static Drug rawOf(ItemStack st) {
      CustomData data = (CustomData)st.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      for (Drug d : DRUGS) {
         if (data.copyTag().contains(d.rawKey())) {
            return d;
         }
      }

      return null;
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
      ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, makeRaw(d, n));
      level.addFreshEntity(e);
   }

   public static void register(CommandDispatcher<CommandSourceStack> dsp, EconomyManager eco) {
      dsp.register(Commands.literal("drugs").executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, "Harvest gives RAW items. Right-click a furnace/blast furnace/smoker holding them (needs 1 coal), wait, then /sell the result.");
         String[] src = {"break ferns", "break ripe sweet berry bushes", "break poppies", "break amethyst clusters"};
         for (int i = 0; i < DRUGS.length; i++) {
            Drug d = DRUGS[i];
            PriceEngine pe = PriceEngine.get();
            long now = pe == null ? d.price : pe.effective(keyOf(d), d.price);
            Msg.info(p, d.name + ": " + src[i] + " - " + d.ratio + " raw = 1 " + d.name + " (" + d.seconds + "s), sells for " + eco.fmt(now) + " each.");
         }

         return 1;
      }));
   }
}
