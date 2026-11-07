package com.simsmp;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

final class ClassEffects {
   private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_mining_speed");
   private static final Identifier EFFICIENCY_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_mining_efficiency");
   private static final Identifier FALL_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_fall_damage");
   private static final Identifier DAMAGE_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_damage");
   private static final Identifier ARMOR_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_armor");
   private static final Identifier MOVE_ID = Identifier.fromNamespaceAndPath("sim_smp", "class_move");
   private static final Set<UUID> nightVision = new HashSet<>();
   private static boolean breakingExtra;
   private static final Map<UUID, Long> instantGrowReady = new HashMap<>();
   private static final Map<UUID, Long> instantTreeReady = new HashMap<>();
   private static final int LOG_XP = 2;

   private ClassEffects() {
   }

   static void update(ServerPlayer p, ClassManager classes) {
      UUID id = p.getUUID();
      String active = classes.active(id);
      boolean miner = "miner".equals(active);
      boolean lumberjack = "lumberjack".equals(active);
      double speed = 0.0;
      if (miner || lumberjack && p.getMainHandItem().is(ItemTags.AXES)) {
         speed = classes.perkTotal(id, "speed") / 100.0;
      }

      setModifier(p, Attributes.BLOCK_BREAK_SPEED, SPEED_ID, speed, Operation.ADD_MULTIPLIED_BASE);
      setModifier(p, Attributes.MINING_EFFICIENCY, EFFICIENCY_ID, miner ? classes.perkTotal(id, "efficiency") : 0.0, Operation.ADD_VALUE);
      setModifier(p, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID, -classes.perkTotal(id, "fall") / 100.0, Operation.ADD_MULTIPLIED_BASE);
      // combat / hunter perks (0 for classes that don't have them, which removes the modifier again)
      setModifier(p, Attributes.ATTACK_DAMAGE, DAMAGE_ID, classes.perkTotal(id, "damage") / 100.0, Operation.ADD_MULTIPLIED_TOTAL);
      setModifier(p, Attributes.ARMOR, ARMOR_ID, classes.perkTotal(id, "armor"), Operation.ADD_VALUE);
      setModifier(p, Attributes.MOVEMENT_SPEED, MOVE_ID, classes.perkTotal(id, "move") / 100.0, Operation.ADD_MULTIPLIED_BASE);
      if (classes.abilityUsable(id, "nightvision")) {
         p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, false));
         nightVision.add(id);
      } else if (nightVision.remove(id)) {
         p.removeEffect(MobEffects.NIGHT_VISION);
      }

      if ("farmer".equals(active)) {
         int growth = classes.perkTotal(id, "growth");
         if (growth > 0) {
            growCrops(p, growth);
         }
      }

      if (lumberjack) {
         int growth = classes.perkTotal(id, "growth");
         if (growth > 0) {
            growSaplings(p, growth);
         }
      }
   }

   private static void growSaplings(ServerPlayer p, int percent) {
      ServerLevel chance = p.level();
      if (chance instanceof ServerLevel) {
         ServerLevel level = chance;
         double var12 = 0.0146 * percent / 100.0;
         RandomSource random = p.getRandom();
         BlockPos origin = p.blockPosition();
         MutableBlockPos pos = new MutableBlockPos();

         for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
               for (int dy = -4; dy <= 4; dy++) {
                  pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                  BlockState s = level.getBlockState(pos);
                  if (s.getBlock() instanceof SaplingBlock && random.nextDouble() < var12) {
                     s.randomTick(level, pos.immutable(), random);
                  }
               }
            }
         }
      }
   }

   private static void growCrops(ServerPlayer p, int percent) {
      ServerLevel chance = p.level();
      if (chance instanceof ServerLevel) {
         ServerLevel level = chance;
         double var14 = 0.0146 * percent / 100.0;
         RandomSource random = p.getRandom();
         BlockPos origin = p.blockPosition();
         MutableBlockPos pos = new MutableBlockPos();

         for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
               for (int dy = -2; dy <= 2; dy++) {
                  pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                  BlockState s = level.getBlockState(pos);
                  if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s) && random.nextDouble() < var14) {
                     s.randomTick(level, pos.immutable(), random);
                  }
               }
            }
         }
      }
   }

   private static void setModifier(ServerPlayer p, Holder<Attribute> attribute, Identifier id, double amount, Operation op) {
      AttributeInstance inst = p.getAttribute(attribute);
      if (inst != null) {
         AttributeModifier existing = inst.getModifier(id);
         if (existing == null || amount == 0.0 || existing.amount() != amount) {
            if (existing != null) {
               inst.removeModifier(id);
            }

            if (amount != 0.0) {
               inst.addTransientModifier(new AttributeModifier(id, amount, op));
            }
         }
      }
   }

   static void forget(ServerPlayer p) {
      nightVision.remove(p.getUUID());
      instantGrowReady.remove(p.getUUID());
      instantTreeReady.remove(p.getUUID());
   }

   static void onBlockBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes, SimConfig cfg) {
      UUID id = p.getUUID();
      String active = classes.active(id);
      if ("farmer".equals(active)) {
         farmerBroken(p, level, pos, state, classes, cfg);
      } else if ("lumberjack".equals(active)) {
         lumberjackBroken(p, level, pos, state, classes);
      } else if ("miner".equals(active)) {
         String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
         classes.addXp(p, ClassManager.mineXp(blockId));
         if (!breakingExtra) {
            if (p.getMainHandItem().is(ItemTags.PICKAXES)) {
               String family = ClassManager.oreFamily(blockId);
               breakingExtra = true;

               try {
                  if (family != null && classes.abilityUsable(id, "veinminer")) {
                     veinMine(p, level, pos, family, cfg.veinMinerMaxBlocks);
                  } else if (classes.abilityUsable(id, "mine3x3")) {
                     mine3x3(p, level, pos);
                  }
               } finally {
                  breakingExtra = false;
               }
            }
         }
      }
   }

   private static void farmerBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes, SimConfig cfg) {
      if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
         classes.addXp(p, 1L);
         if (!breakingExtra && classes.abilityUsable(p.getUUID(), "veinharvester")) {
            breakingExtra = true;

            try {
               veinHarvest(p, level, pos, state.getBlock(), cfg.veinHarvesterMaxBlocks);
            } finally {
               breakingExtra = false;
            }
         }
      }
   }

   private static void veinHarvest(ServerPlayer p, Level level, BlockPos start, Block type, int max) {
      Set<BlockPos> seen = new HashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      seen.add(start);
      queue.add(start);
      int broken = 0;

      while (!queue.isEmpty() && broken < max) {
         BlockPos cur = queue.poll();

         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx != 0 || dz != 0) {
                  BlockPos next = cur.offset(dx, 0, dz);
                  if (seen.add(next)) {
                     BlockState s = level.getBlockState(next);
                     if (s.getBlock() == type && s.getBlock() instanceof CropBlock c && c.isMaxAge(s)) {
                        if (broken >= max) {
                           return;
                        }

                        if (breakBlock(p, level, next)) {
                           broken++;
                        }

                        queue.add(next);
                     }
                  }
               }
            }
         }
      }
   }

   static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit, ClassManager classes, SimConfig cfg) {
      if (player instanceof ServerPlayer p && hand == InteractionHand.MAIN_HAND) {
         String active = classes.active(p.getUUID());
         if ("farmer".equals(active)) {
            return farmerUse(p, level, hit, classes, cfg);
         } else {
            return (InteractionResult)("lumberjack".equals(active) ? lumberjackUse(p, level, hit, classes, cfg) : InteractionResult.PASS);
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   private static InteractionResult farmerUse(ServerPlayer p, Level level, BlockHitResult hit, ClassManager classes, SimConfig cfg) {
      UUID id = p.getUUID();
      if (!classes.abilityUsable(id, "instantgrow")) {
         return InteractionResult.PASS;
      } else if (!p.getMainHandItem().is(ItemTags.HOES)) {
         return InteractionResult.PASS;
      } else {
         BlockPos start = hit.getBlockPos();
         BlockState startState = level.getBlockState(start);
         if (startState.getBlock() instanceof CropBlock startCrop && !startCrop.isMaxAge(startState)) {
            long now = System.currentTimeMillis();
            Long readyAt = instantGrowReady.get(id);
            if (readyAt != null && readyAt > now) {
               Msg.err(p, "Instant Grow is recharging: " + seconds(readyAt - now) + " left.");
               return InteractionResult.SUCCESS;
            } else {
               Set<BlockPos> seen = new HashSet<>();
               ArrayDeque<BlockPos> queue = new ArrayDeque<>();
               seen.add(start);
               queue.add(start);
               int grown = 0;

               while (!queue.isEmpty() && seen.size() <= cfg.instantGrowMaxBlocks) {
                  BlockPos cur = queue.poll();
                  BlockState s = level.getBlockState(cur);
                  if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s)) {
                     level.setBlock(cur, crop.getStateForAge(crop.getMaxAge()), 3);
                     grown++;
                  }

                  for (int dx = -1; dx <= 1; dx++) {
                     for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dz != 0) {
                           BlockPos next = cur.offset(dx, 0, dz);
                           if (!seen.contains(next) && level.getBlockState(next).getBlock() instanceof CropBlock) {
                              seen.add(next);
                              queue.add(next);
                           }
                        }
                     }
                  }
               }

               instantGrowReady.put(id, now + cfg.instantGrowCooldownSeconds * 1000L);
               Msg.ok(p, "Instant Grow! " + grown + " crop(s) grew to full size. Ready again in " + seconds(cfg.instantGrowCooldownSeconds * 1000L) + ".");
               return InteractionResult.SUCCESS;
            }
         } else {
            return InteractionResult.PASS;
         }
      }
   }

   private static String seconds(long millis) {
      long s = (Math.max(0L, millis) + 999L) / 1000L;
      return s >= 60L ? s / 60L + "m " + s % 60L + "s" : s + "s";
   }

   private static void lumberjackBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes) {
      if (state.is(BlockTags.LOGS)) {
         if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_")) {
            if (nearNaturalLeaves(level, pos)) {
               classes.addXp(p, 2L);
            }
         }
      }
   }

   private static boolean nearNaturalLeaves(Level level, BlockPos origin) {
      MutableBlockPos m = new MutableBlockPos();

      for (int dx = -4; dx <= 4; dx++) {
         for (int dz = -4; dz <= 4; dz++) {
            for (int dy = -4; dy <= 24; dy++) {
               m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
               BlockState s = level.getBlockState(m);
               if (s.getBlock() instanceof LeavesBlock && !(Boolean)s.getValue(LeavesBlock.PERSISTENT)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static InteractionResult lumberjackUse(ServerPlayer p, Level level, BlockHitResult hit, ClassManager classes, SimConfig cfg) {
      UUID id = p.getUUID();
      if (!classes.abilityUsable(id, "treegrow")) {
         return InteractionResult.PASS;
      } else if (!p.getMainHandItem().is(ItemTags.AXES)) {
         return InteractionResult.PASS;
      } else if (!(level instanceof ServerLevel serverLevel)) {
         return InteractionResult.PASS;
      } else {
         BlockPos pos = hit.getBlockPos();
         BlockState state = level.getBlockState(pos);
         if (!(state.getBlock() instanceof SaplingBlock)) {
            return InteractionResult.PASS;
         } else {
            long now = System.currentTimeMillis();
            Long readyAt = instantTreeReady.get(id);
            if (readyAt != null && readyAt > now) {
               Msg.err(p, "Instant Tree Growth is recharging: " + seconds(readyAt - now) + " left.");
               return InteractionResult.SUCCESS;
            } else {
               for (int i = 0; i < 2; i++) {
                  BlockState cur = level.getBlockState(pos);
                  if (!(cur.getBlock() instanceof SaplingBlock sapling)) {
                     break;
                  }

                  sapling.advanceTree(serverLevel, pos, cur, p.getRandom());
               }

               if (level.getBlockState(pos).getBlock() instanceof SaplingBlock) {
                  Msg.err(p, "This tree can't grow here (not enough room, or it needs 2x2 saplings).");
                  return InteractionResult.SUCCESS;
               } else {
                  instantTreeReady.put(id, now + cfg.instantTreeCooldownSeconds * 1000L);
                  Msg.ok(p, "The tree grew instantly! Ready again in " + seconds(cfg.instantTreeCooldownSeconds * 1000L) + ".");
                  return InteractionResult.SUCCESS;
               }
            }
         }
      }
   }

   private static void veinMine(ServerPlayer p, Level level, BlockPos start, String family, int max) {
      Set<BlockPos> seen = new HashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      seen.add(start);
      queue.add(start);
      int broken = 0;

      while (!queue.isEmpty() && broken < max) {
         BlockPos cur = queue.poll();

         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx != 0 || dy != 0 || dz != 0) {
                     BlockPos next = cur.offset(dx, dy, dz);
                     if (seen.add(next)) {
                        BlockState s = level.getBlockState(next);
                        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                        if (family.equals(ClassManager.oreFamily(id))) {
                           if (broken >= max || !toolStillGood(p)) {
                              return;
                           }

                           if (breakBlock(p, level, next)) {
                              broken++;
                           }

                           queue.add(next);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void mine3x3(ServerPlayer p, Level level, BlockPos center) {
      float pitch = p.getXRot();
      Axis normal = Math.abs(pitch) > 55.0F ? Axis.Y : p.getDirection().getAxis();

      for (int a = -1; a <= 1; a++) {
         for (int b = -1; b <= 1; b++) {
            if (a != 0 || b != 0) {
               BlockPos target = switch (normal) {
                  case Y -> center.offset(a, 0, b);
                  case X -> center.offset(0, a, b);
                  case Z -> center.offset(a, b, 0);
                  default -> throw new MatchException(null, null);
               };
               BlockState s = level.getBlockState(target);
               if (!s.isAir() && s.is(BlockTags.MINEABLE_WITH_PICKAXE) && !(s.getDestroySpeed(level, target) < 0.0F)) {
                  if (!toolStillGood(p)) {
                     return;
                  }

                  breakBlock(p, level, target);
               }
            }
         }
      }
   }

   private static boolean toolStillGood(ServerPlayer p) {
      return !p.getMainHandItem().isEmpty() && p.getMainHandItem().is(ItemTags.PICKAXES);
   }

   private static boolean breakBlock(ServerPlayer p, Level level, BlockPos pos) {
      return !level.mayInteract(p, pos) ? false : p.gameMode.destroyBlock(pos);
   }
}
