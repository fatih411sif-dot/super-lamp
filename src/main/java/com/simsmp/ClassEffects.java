package com.simsmp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The effects of class skills: attribute perks (mining speed, efficiency, fall damage), Night Vision,
 * and the block-breaking abilities Vein Miner and 3x3 Mining.
 */
final class ClassEffects {
    private ClassEffects() {}

    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath(SimSmp.MOD_ID, "class_mining_speed");
    private static final Identifier EFFICIENCY_ID = Identifier.fromNamespaceAndPath(SimSmp.MOD_ID, "class_mining_efficiency");
    private static final Identifier FALL_ID = Identifier.fromNamespaceAndPath(SimSmp.MOD_ID, "class_fall_damage");

    /** Players who currently have our Night Vision, so we only remove the effect we gave. */
    private static final Set<UUID> nightVision = new HashSet<>();
    /** True while an ability is breaking extra blocks, so those breaks do not trigger the abilities again. */
    private static boolean breakingExtra;
    /** Farmer's Instant Grow: when each player may use it again (millis). */
    private static final Map<UUID, Long> instantGrowReady = new HashMap<>();
    /** Lumberjack's Instant Tree Growth: when each player may use it again (millis). */
    private static final Map<UUID, Long> instantTreeReady = new HashMap<>();
    /** Lumberjack XP for every log of a natural tree. */
    private static final int LOG_XP = 2;

    // ------------------------------------------------------------------ once per second

    /** Keeps the perks and Night Vision of this player up to date. Called once per second. */
    static void update(ServerPlayer p, ClassManager classes) {
        UUID id = p.getUUID();
        String active = classes.active(id);
        boolean miner = "miner".equals(active);
        boolean lumberjack = "lumberjack".equals(active);

        // Miner: mining speed always. Lumberjack: chopping speed only while an axe is in the main hand.
        double speed = 0;
        if (miner || (lumberjack && p.getMainHandItem().is(ItemTags.AXES))) {
            speed = classes.perkTotal(id, ClassDefs.SPEED) / 100.0;
        }
        setModifier(p, Attributes.BLOCK_BREAK_SPEED, SPEED_ID, speed, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        setModifier(p, Attributes.MINING_EFFICIENCY, EFFICIENCY_ID,
                miner ? classes.perkTotal(id, ClassDefs.EFFICIENCY) : 0, AttributeModifier.Operation.ADD_VALUE);
        setModifier(p, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID,
                miner ? -classes.perkTotal(id, ClassDefs.FALL) / 100.0 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);

        if (miner && classes.abilityUsable(id, "nightvision")) {
            // long enough that the vision does not start to blink, refreshed every second
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, false));
            nightVision.add(id);
        } else if (nightVision.remove(id)) {
            p.removeEffect(MobEffects.NIGHT_VISION);
        }

        if ("farmer".equals(active)) {
            int growth = classes.perkTotal(id, ClassDefs.GROWTH);
            if (growth > 0) growCrops(p, growth);
        }
        if (lumberjack) {
            int growth = classes.perkTotal(id, ClassDefs.GROWTH);
            if (growth > 0) growSaplings(p, growth);
        }
    }

    /** Fast Growth for saplings: same idea as growCrops, within 10 blocks of the lumberjack. */
    private static void growSaplings(ServerPlayer p, int percent) {
        if (!(p.level() instanceof ServerLevel level)) return;
        double chance = 0.0146 * percent / 100.0;
        RandomSource random = p.getRandom();
        BlockPos origin = p.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState s = level.getBlockState(pos);
                    if (!(s.getBlock() instanceof SaplingBlock)) continue;
                    if (random.nextDouble() < chance) s.randomTick(level, pos.immutable(), random);
                }
            }
        }
    }

    /**
     * Fast Growth: gives crops within 10 blocks of the farmer extra random ticks. Vanilla gives every block
     * 3 random ticks per 4096 ticks (about 1.46% per second), so +100% here means exactly twice as many
     * growth chances, and the normal growth rules (water, light) still apply.
     */
    private static void growCrops(ServerPlayer p, int percent) {
        if (!(p.level() instanceof ServerLevel level)) return;
        double chance = 0.0146 * percent / 100.0;
        RandomSource random = p.getRandom();
        BlockPos origin = p.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState s = level.getBlockState(pos);
                    if (!(s.getBlock() instanceof CropBlock crop) || crop.isMaxAge(s)) continue;
                    if (random.nextDouble() < chance) s.randomTick(level, pos.immutable(), random);
                }
            }
        }
    }

    /** Sets (or removes, when amount is 0) one of our modifiers. They are transient: never written to the player file. */
    private static void setModifier(ServerPlayer p, Holder<Attribute> attribute, Identifier id, double amount,
                                    AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && amount != 0 && existing.amount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    static void forget(ServerPlayer p) {
        nightVision.remove(p.getUUID());
        instantGrowReady.remove(p.getUUID());
        instantTreeReady.remove(p.getUUID());
    }

    // ------------------------------------------------------------------ block breaking

    /** Called after a player broke a block (see PlayerBlockBreakEvents.AFTER). Gives XP and runs the Miner abilities. */
    static void onBlockBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes, SimConfig cfg) {
        UUID id = p.getUUID();
        String active = classes.active(id);
        if ("farmer".equals(active)) {
            farmerBroken(p, level, pos, state, classes, cfg);
            return;
        }
        if ("lumberjack".equals(active)) {
            lumberjackBroken(p, level, pos, state, classes);
            return;
        }
        if (!"miner".equals(active)) return;

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        classes.addXp(p, ClassManager.mineXp(blockId)); // ores only, also for blocks broken by an ability

        if (breakingExtra) return;
        if (!p.getMainHandItem().is(ItemTags.PICKAXES)) return;

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

    // ------------------------------------------------------------------ Farmer

    /** Farmer XP: 1 per fully grown crop harvested (young crops give nothing). Vein Harvester breaks the connected ripe crops. */
    private static void farmerBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes, SimConfig cfg) {
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return;
        classes.addXp(p, 1); // also for crops broken by the ability

        if (breakingExtra || !classes.abilityUsable(p.getUUID(), "veinharvester")) return;
        breakingExtra = true;
        try {
            veinHarvest(p, level, pos, state.getBlock(), cfg.veinHarvesterMaxBlocks);
        } finally {
            breakingExtra = false;
        }
    }

    /** Breaks every connected ripe crop of the same kind on the same level (diagonals count). */
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
                    if (dx == 0 && dz == 0) continue;
                    BlockPos next = cur.offset(dx, 0, dz);
                    if (!seen.add(next)) continue;
                    BlockState s = level.getBlockState(next);
                    if (s.getBlock() != type || !(s.getBlock() instanceof CropBlock c) || !c.isMaxAge(s)) continue;
                    if (broken >= max) return;
                    if (breakBlock(p, level, next)) broken++;
                    queue.add(next);
                }
            }
        }
    }

    /**
     * Instant Grow: right-click a young crop with a hoe and it, plus every crop connected to it on the same
     * level, grows to full size. Has a cooldown (instantGrowCooldownSeconds). Returns PASS when it is not our click.
     */
    static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit,
                                        ClassManager classes, SimConfig cfg) {
        if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        String active = classes.active(p.getUUID());
        if ("farmer".equals(active)) return farmerUse(p, level, hit, classes, cfg);
        if ("lumberjack".equals(active)) return lumberjackUse(p, level, hit, classes, cfg);
        return InteractionResult.PASS;
    }

    private static InteractionResult farmerUse(ServerPlayer p, Level level, BlockHitResult hit,
                                               ClassManager classes, SimConfig cfg) {
        UUID id = p.getUUID();
        if (!classes.abilityUsable(id, "instantgrow")) return InteractionResult.PASS;
        if (!p.getMainHandItem().is(ItemTags.HOES)) return InteractionResult.PASS;

        BlockPos start = hit.getBlockPos();
        BlockState startState = level.getBlockState(start);
        if (!(startState.getBlock() instanceof CropBlock startCrop) || startCrop.isMaxAge(startState)) return InteractionResult.PASS;

        long now = System.currentTimeMillis();
        Long readyAt = instantGrowReady.get(id);
        if (readyAt != null && readyAt > now) {
            Msg.err(p, "Instant Grow is recharging: " + seconds(readyAt - now) + " left.");
            return InteractionResult.SUCCESS;
        }

        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        int grown = 0;
        while (!queue.isEmpty() && seen.size() <= cfg.instantGrowMaxBlocks) {
            BlockPos cur = queue.poll();
            BlockState s = level.getBlockState(cur);
            if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s)) {
                level.setBlock(cur, crop.getStateForAge(crop.getMaxAge()), Block.UPDATE_ALL);
                grown++;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos next = cur.offset(dx, 0, dz);
                    if (seen.contains(next)) continue;
                    if (level.getBlockState(next).getBlock() instanceof CropBlock) {
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
        }

        instantGrowReady.put(id, now + cfg.instantGrowCooldownSeconds * 1000L);
        Msg.ok(p, "Instant Grow! " + grown + " crop(s) grew to full size. Ready again in "
                + seconds(cfg.instantGrowCooldownSeconds * 1000L) + ".");
        return InteractionResult.SUCCESS;
    }

    private static String seconds(long millis) {
        long s = (Math.max(0, millis) + 999) / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    // ------------------------------------------------------------------ Lumberjack

    /**
     * Lumberjack XP: LOG_XP for every log of a natural tree. Stripped logs never count, and a log only counts when
     * natural leaves (not "persistent", i.e. not placed by a player) are close by, so placing and
     * re-breaking logs cannot be farmed.
     */
    private static void lumberjackBroken(ServerPlayer p, Level level, BlockPos pos, BlockState state, ClassManager classes) {
        if (!state.is(BlockTags.LOGS)) return;
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_")) return;
        if (!nearNaturalLeaves(level, pos)) return;
        classes.addXp(p, LOG_XP);
    }

    private static boolean nearNaturalLeaves(Level level, BlockPos origin) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -4; dy <= 8; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Instant Tree Growth: right-click a sapling with an axe and it grows into a full tree right away
     * (cooldown instantTreeCooldownSeconds). It follows the vanilla growing rules, so a tree that has no room
     * (or a dark oak without its 2x2 saplings) does not grow and the cooldown is not used.
     */
    private static InteractionResult lumberjackUse(ServerPlayer p, Level level, BlockHitResult hit,
                                                   ClassManager classes, SimConfig cfg) {
        UUID id = p.getUUID();
        if (!classes.abilityUsable(id, "treegrow")) return InteractionResult.PASS;
        if (!p.getMainHandItem().is(ItemTags.AXES)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SaplingBlock)) return InteractionResult.PASS;

        long now = System.currentTimeMillis();
        Long readyAt = instantTreeReady.get(id);
        if (readyAt != null && readyAt > now) {
            Msg.err(p, "Instant Tree Growth is recharging: " + seconds(readyAt - now) + " left.");
            return InteractionResult.SUCCESS;
        }

        // a sapling needs two advances: first its stage, then the tree itself
        for (int i = 0; i < 2; i++) {
            BlockState cur = level.getBlockState(pos);
            if (!(cur.getBlock() instanceof SaplingBlock sapling)) break;
            sapling.advanceTree(serverLevel, pos, cur, p.getRandom());
        }

        if (level.getBlockState(pos).getBlock() instanceof SaplingBlock) {
            Msg.err(p, "This tree can't grow here (not enough room, or it needs 2x2 saplings).");
            return InteractionResult.SUCCESS;
        }
        instantTreeReady.put(id, now + cfg.instantTreeCooldownSeconds * 1000L);
        Msg.ok(p, "The tree grew instantly! Ready again in " + seconds(cfg.instantTreeCooldownSeconds * 1000L) + ".");
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ Miner abilities

    /** Breaks every connected block of the same ore (diagonals count, deepslate and normal ore count as the same). */
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
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = cur.offset(dx, dy, dz);
                        if (!seen.add(next)) continue;
                        BlockState s = level.getBlockState(next);
                        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                        if (!family.equals(ClassManager.oreFamily(id))) continue;
                        if (broken >= max || !toolStillGood(p)) return;
                        if (breakBlock(p, level, next)) broken++;
                        queue.add(next); // keep searching from here even if this one could not be broken
                    }
                }
            }
        }
    }

    /** Breaks the 3x3 area around the block, in the plane you are facing (looking up or down = flat plane). */
    private static void mine3x3(ServerPlayer p, Level level, BlockPos center) {
        float pitch = p.getXRot();
        Direction.Axis normal = Math.abs(pitch) > 55 ? Direction.Axis.Y : p.getDirection().getAxis();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                BlockPos target = switch (normal) {
                    case Y -> center.offset(a, 0, b);
                    case X -> center.offset(0, a, b);
                    case Z -> center.offset(a, b, 0);
                };
                BlockState s = level.getBlockState(target);
                if (s.isAir() || !s.is(BlockTags.MINEABLE_WITH_PICKAXE)) continue;
                if (s.getDestroySpeed(level, target) < 0) continue; // bedrock and similar
                if (!toolStillGood(p)) return;
                breakBlock(p, level, target);
            }
        }
    }

    /** The pickaxe must still be in the hand (it vanishes when it breaks). */
    private static boolean toolStillGood(ServerPlayer p) {
        return !p.getMainHandItem().isEmpty() && p.getMainHandItem().is(ItemTags.PICKAXES);
    }

    /** Breaks one block the way the player would: drops, tool damage and protection plugins all apply. */
    private static boolean breakBlock(ServerPlayer p, Level level, BlockPos pos) {
        if (!level.mayInteract(p, pos)) return false; // spawn protection / world border
        return p.gameMode.destroyBlock(pos);
    }
}
