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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
    private static final Identifier RUN_ID = Identifier.fromNamespaceAndPath(SimSmp.MOD_ID, "class_run_speed");

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

        // Berserker: running speed (extra percent of the normal walking speed)
        setModifier(p, Attributes.MOVEMENT_SPEED, RUN_ID,
                "berserker".equals(active) ? classes.perkTotal(id, ClassDefs.RUNSPEED) / 100.0 : 0,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);

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
        parryReady.remove(p.getUUID());
        healthBefore.remove(p.getUUID());
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

    // ------------------------------------------------------------------ Hunter

    /** Animals that give Hunter XP (by entity id path). */
    private static final Set<String> HUNT_ANIMALS = Set.of("cow", "mooshroom", "pig", "sheep", "chicken", "rabbit");
    private static final int HUNT_XP = 3;
    /** raw meat -> cooked meat */
    private static final Map<String, String> COOKED = Map.of(
            "minecraft:beef", "minecraft:cooked_beef",
            "minecraft:porkchop", "minecraft:cooked_porkchop",
            "minecraft:mutton", "minecraft:cooked_mutton",
            "minecraft:chicken", "minecraft:cooked_chicken",
            "minecraft:rabbit", "minecraft:cooked_rabbit");

    /**
     * Called after any living thing died (see ServerLivingEntityEvents.AFTER_DEATH). When a Hunter kills a farm animal
     * they get Class XP, and the meat that just dropped can be increased (Lucky Drops) and cooked (Auto Cook).
     * Only items that spawned this very moment next to the animal are touched, so items lying around are safe.
     */
    static void onEntityDeath(LivingEntity entity, DamageSource source, ClassManager classes, SimConfig cfg) {
        if (!(source.getEntity() instanceof ServerPlayer p)) return;
        UUID id = p.getUUID();
        String activeClass = classes.active(id);
        if (!"hunter".equals(activeClass)) {
            combatKill(p, entity, source, activeClass, classes, cfg);
            return;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        if (!HUNT_ANIMALS.contains(type)) return;
        classes.addXp(p, HUNT_XP);

        boolean luck = classes.abilityUsable(id, "meatluck");
        boolean cook = classes.abilityUsable(id, "autocook");
        if (!luck && !cook) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        RandomSource random = p.getRandom();
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(1.0))) {
            if (drop.getAge() > 2) continue; // not one of the drops of this kill
            ItemStack stack = drop.getItem();
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean raw = COOKED.containsKey(key);
            if (!raw && !COOKED.containsValue(key)) continue; // not meat
            int count = stack.getCount();
            if (luck) count += 1 + random.nextInt(2);
            Item result = ItemIds.find(cook && raw ? COOKED.get(key) : key);
            if (result == null) continue;
            drop.setItem(new ItemStack(result, Math.min(count, result.getDefaultMaxStackSize())));
        }
    }

    // ------------------------------------------------------------------ Combat XP (all combat classes)

    private static final Set<String> COMBAT_CLASSES = Set.of("archer", "berserker", "warrior", "mage");
    /** Class XP for killing another player (only once per victim per playerKillXpCooldownMinutes). */
    private static final int PLAYER_KILL_XP = 10;
    private static final Map<String, Long> playerKillSeen = new HashMap<>();

    /**
     * Combat Class XP: for killing a hostile mob (half of its max health in XP, at least 1, at most 100) or another
     * player (flat PLAYER_KILL_XP, with a cooldown per victim so friends cannot farm each other). The kill must be
     * made with the class's own weapon.
     */
    private static void combatKill(ServerPlayer p, LivingEntity victim, DamageSource source, String active,
                                   ClassManager classes, SimConfig cfg) {
        if (!COMBAT_CLASSES.contains(active) || victim == p) return;
        if (!weaponMatches(active, p, source)) return;

        long xp;
        if (victim instanceof ServerPlayer other) {
            if (cfg.playerKillXpCooldownMinutes > 0) {
                long now = System.currentTimeMillis();
                String key = p.getUUID() + ":" + other.getUUID();
                Long last = playerKillSeen.get(key);
                if (last != null && now - last < cfg.playerKillXpCooldownMinutes * 60_000L) return;
                playerKillSeen.put(key, now);
            }
            xp = PLAYER_KILL_XP;
        } else if (victim instanceof Enemy) {
            xp = Math.max(1, Math.min(100, Math.round(victim.getMaxHealth() / 2f)));
        } else {
            return;
        }
        classes.addXp(p, xp);
    }

    /** True when the kill was made with the weapon of this combat class. */
    private static boolean weaponMatches(String active, ServerPlayer p, DamageSource source) {
        if ("archer".equals(active)) return isArrow(source.getDirectEntity());
        if ("berserker".equals(active)) return source.getDirectEntity() == p && p.getMainHandItem().is(ItemTags.AXES);
        return false; // the other combat classes come later
    }

    private static boolean isArrow(Entity e) {
        return e != null && (e.getType() == EntityType.ARROW || e.getType() == EntityType.SPECTRAL_ARROW);
    }

    // ------------------------------------------------------------------ Archer

    private static final String BOOST_TAG = "sim_smp_arrow";
    private static final String POISON_TAG = "sim_smp_poison";
    /** Arrows that are currently steered by Tracker Arrow. */
    private static final List<AbstractArrow> trackerArrows = new ArrayList<>();

    /**
     * Called for every entity that appears in the world (see ServerEntityEvents.ENTITY_LOAD). When an Archer shoots an
     * arrow it gets: more damage (perk), more speed = more range (Ultimate Range), poison (Poisonous Arrow) and
     * steering (Tracker Arrow). The arrow is tagged so it is never boosted twice (arrows reload from disk too).
     */
    static void onEntityLoad(Entity entity, ClassManager classes, SimConfig cfg) {
        if (!isArrow(entity) || !(entity instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof ServerPlayer p)) return;
        if (entity.getTags().contains(BOOST_TAG)) return;
        UUID id = p.getUUID();
        if (!"archer".equals(classes.active(id))) return;
        entity.addTag(BOOST_TAG);

        double damage = 1.0 + classes.perkTotal(id, ClassDefs.DAMAGE) / 100.0;
        if (classes.abilityUsable(id, "ultrange")) {
            // arrow damage grows with speed, so take the extra speed out again: only the perk changes the damage
            double range = Math.max(1.0, cfg.archerRangeMultiplier);
            arrow.setDeltaMovement(arrow.getDeltaMovement().scale(range));
            damage /= range;
        }
        arrow.setBaseDamage(arrow.getBaseDamage() * damage);

        if (classes.abilityUsable(id, "poisonarrow")) entity.addTag(POISON_TAG);
        if (classes.abilityUsable(id, "tracker")) trackerArrows.add(arrow);
    }

    /** Once per tick: bends every tracked arrow a little towards where its owner is looking. */
    static void tickArrows() {
        Iterator<AbstractArrow> it = trackerArrows.iterator();
        while (it.hasNext()) {
            AbstractArrow arrow = it.next();
            Vec3 motion = arrow.getDeltaMovement();
            double speed = motion.length();
            // gone, too old, or stuck in a block (its speed drops to almost nothing)
            if (arrow.isRemoved() || arrow.tickCount > 120 || speed < 0.1 || !(arrow.getOwner() instanceof ServerPlayer owner) || !owner.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 steered = motion.normalize().scale(0.85).add(owner.getLookAngle().scale(0.15)).normalize();
            arrow.setDeltaMovement(steered.scale(speed));
        }
    }

    // ------------------------------------------------------------------ damage events (resistance, poison, Berserker)

    /** True while we apply extra damage ourselves, so that extra hit does not trigger the perks again. */
    private static boolean applyingExtra;
    /** Berserker's Parry: when each player may use it again (millis). */
    private static final Map<UUID, Long> parryReady = new HashMap<>();
    /** Health + absorption of a resistant player just before a hit, to work out how much the hit really took. */
    private static final Map<UUID, Float> healthBefore = new HashMap<>();

    /**
     * Only records the health of Archers and Berserkers before a hit (see ServerLivingEntityEvents.ALLOW_DAMAGE). It never
     * cancels anything, so knockback, weapon durability and arrows behave exactly like vanilla.
     */
    static boolean allowDamage(LivingEntity entity, DamageSource source, float amount, ClassManager classes) {
        if (entity instanceof ServerPlayer p) {
            String active = classes.active(p.getUUID());
            if ("berserker".equals(active) || "archer".equals(active)) {
                healthBefore.put(p.getUUID(), p.getHealth() + p.getAbsorptionAmount());
            }
        }
        return true;
    }

    /**
     * Runs after a living thing took damage and survived (see ServerLivingEntityEvents.AFTER_DAMAGE):
     * Poisonous Arrow, Archer/Berserker resistance, and the Berserker's axe perks.
     */
    static void afterDamage(LivingEntity entity, DamageSource source, float damageTaken, boolean blocked,
                            ClassManager classes, SimConfig cfg) {
        Entity direct = source.getDirectEntity();
        if (!blocked && direct != null && direct.getTags().contains(POISON_TAG)) {
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
        }
        if (entity instanceof ServerPlayer victim) resist(victim, source, classes);
        if (!blocked && !applyingExtra && source.getEntity() instanceof ServerPlayer attacker && direct == attacker) {
            berserkerHit(attacker, entity, source, damageTaken, classes, cfg);
        }
    }

    /**
     * Resistance: after the hit, gives back that percentage of the health it really took. (A hit that kills outright
     * cannot be reduced this way, because no "after damage" event happens for it.)
     */
    private static void resist(ServerPlayer victim, DamageSource source, ClassManager classes) {
        Float before = healthBefore.remove(victim.getUUID());
        if (before == null) return;
        UUID id = victim.getUUID();
        String active = classes.active(id);
        int resist = 0;
        if ("berserker".equals(active)) resist = classes.perkTotal(id, ClassDefs.RESIST);
        else if ("archer".equals(active) && isArrow(source.getDirectEntity())) resist = classes.perkTotal(id, ClassDefs.ARROW_RESIST);
        resist = Math.min(90, resist);
        if (resist <= 0) return;
        float lost = before - (victim.getHealth() + victim.getAbsorptionAmount());
        if (lost > 0) victim.heal(lost * resist / 100f);
    }

    // ------------------------------------------------------------------ Berserker

    /**
     * Berserker axe hit: damage perks and Double Axes (extra damage on top of the vanilla hit), the Sharpness VI perk
     * (scaled by how charged the swing was, like the real enchant), Fire Aspect, and Parry.
     */
    private static void berserkerHit(ServerPlayer attacker, LivingEntity target, DamageSource source, float damageTaken,
                                     ClassManager classes, SimConfig cfg) {
        UUID id = attacker.getUUID();
        if (!"berserker".equals(classes.active(id)) || target == attacker) return;
        if (!attacker.getMainHandItem().is(ItemTags.AXES)) return;
        if (!(attacker.level() instanceof ServerLevel level)) return;

        int sharpLevel = classes.perkTotal(id, ClassDefs.SHARP);
        float sharp = sharpLevel > 0 ? (0.5f * sharpLevel + 0.5f) * attacker.getAttackStrengthScale(0.5f) : 0f;
        double mult = (1.0 + classes.perkTotal(id, ClassDefs.DAMAGE) / 100.0) * (classes.abilityUsable(id, "doubleaxes") ? 2.0 : 1.0);
        float extra = (float) ((damageTaken + sharp) * mult - damageTaken);

        applyingExtra = true;
        try {
            if (extra > 0.01f) {
                target.invulnerableTime = 0; // otherwise vanilla ignores a second hit in the same moment
                target.hurtServer(level, source, extra); // same source: knockback and kill credit stay with the player
            }
            if (!target.isAlive()) return;

            int fire = classes.perkTotal(id, ClassDefs.FIRE);
            if (fire > 0) target.igniteForSeconds(fire);

            if (classes.abilityUsable(id, "parry") && (target instanceof Enemy || target instanceof ServerPlayer)) {
                long now = System.currentTimeMillis();
                Long ready = parryReady.get(id);
                if (ready == null || ready <= now) {
                    float half = Math.min(target.getHealth() / 2f, cfg.parryMaxDamage);
                    if (half >= 0.5f) {
                        target.setHealth(target.getHealth() - half); // a "bleed": ignores armor, can never kill by itself
                        parryReady.put(id, now + cfg.parryCooldownSeconds * 1000L);
                        Msg.ok(attacker, "Parry! The enemy lost " + Math.round(half) + " health. Ready again in "
                                + seconds(cfg.parryCooldownSeconds * 1000L) + ".");
                    }
                }
            }
        } finally {
            applyingExtra = false;
        }
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
