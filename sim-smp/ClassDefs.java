package com.simsmp;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The 8 classes and their skill trees. A skill can only be unlocked when its parent is unlocked
 * (the tree grows downwards), the player's class level is high enough, and they pay its Class XP cost.
 *
 * Built lazily (not in a static initializer) because the item registry must be ready first.
 *
 * Every skill has one effect kind ("perk") and a number ("value"). Perks of the same kind add up:
 *   SELL       +value% money when selling the class's items with /sell
 *   SPEED      +value% block breaking speed
 *   FALL       -value% fall damage
 *   EFFICIENCY +value flat mining efficiency (works like extra Efficiency enchantment levels)
 *   ABILITY    an ability the player switches on and off with /ability (the skill id is the ability name)
 */
final class ClassDefs {
    private ClassDefs() {}

    static final String SELL = "sell";
    static final String SPEED = "speed";
    static final String FALL = "fall";
    static final String EFFICIENCY = "efficiency";
    /** +value% faster crop growth around the player (extra random ticks). */
    static final String GROWTH = "growth";
    /** +value% damage with the class's main weapon (Archer: arrows). */
    static final String DAMAGE = "damage";
    /** -value% damage taken from arrows. */
    static final String ARROW_RESIST = "arrowresist";
    /** -value% damage taken from everything (melee classes). */
    static final String RESIST = "resist";
    /** +value% running speed. */
    static final String RUNSPEED = "runspeed";
    /** Server-side "Sharpness" perk: value = the level, extra damage 0.5 * level + 0.5 (Sharpness 6 = +3.5). Adds to a real Sharpness enchant. */
    static final String SHARP = "sharp";
    /** Server-side "Fire Aspect" perk: value = seconds the victim burns. */
    static final String FIRE = "fire";
    static final String ABILITY = "ability";

    enum Category {
        ECONOMY("Economy"), COMBAT("Combat");
        final String label;
        Category(String label) { this.label = label; }
    }

    /** row 0-4 and col 0-8 are the position inside the tree window. */
    record Skill(String id, String name, String desc, Item icon, String parent, long cost, int minLevel,
                 int row, int col, boolean active, String perk, int value) {}

    record ClassDef(String id, String name, Category category, Item icon, List<Skill> skills, Set<String> sellItems) {
        Skill skill(String skillId) {
            for (Skill s : skills) if (s.id().equals(skillId)) return s;
            return null;
        }
    }

    private static List<ClassDef> all;

    static synchronized List<ClassDef> all() {
        if (all == null) all = build();
        return all;
    }

    static ClassDef get(String id) {
        if (id == null) return null;
        for (ClassDef c : all()) if (c.id().equals(id)) return c;
        return null;
    }

    private static Skill s(String id, String name, String desc, Item icon, String parent, long cost, int minLevel,
                           int row, int col, String perk, int value) {
        return new Skill(id, name, desc, icon, parent, cost, minLevel, row, col, true, perk, value);
    }

    private static List<ClassDef> build() {
        List<ClassDef> out = new ArrayList<>();

        // ---- Miner. Total cost of all skills = 10,250 Class XP.
        List<Skill> miner = List.of(
                s("sell1", "Ore Value I", "+10% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, null, 100, 1, 0, 4, SELL, 10),
                s("eff1", "Mining Speed I", "+10% mining speed (adds up)", Items.GOLDEN_PICKAXE, "sell1", 150, 3, 1, 2, SPEED, 10),
                s("sell2", "Ore Value II", "+20% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 1, 6, SELL, 20),
                s("eff2", "Mining Speed II", "+20% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff1", 300, 6, 2, 1, SPEED, 20),
                s("veinminer", "Vein Miner", "Ability: mining an ore breaks the whole connected vein. Needs a pickaxe. Use /ability veinminer true", Items.RAW_IRON, "eff1", 600, 6, 2, 3, ABILITY, 0),
                s("sell3", "Ore Value III", "+30% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 450, 6, 2, 6, SELL, 30),
                s("eff3", "Mining Speed III", "+40% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff2", 600, 9, 3, 1, SPEED, 40),
                s("nightvision", "Night Vision", "Ability: night vision with no time limit. Use /ability nightvision true", Items.GOLDEN_CARROT, "veinminer", 900, 9, 3, 3, ABILITY, 0),
                s("fall", "Light Landing", "50% less fall damage", Items.FEATHER, "sell3", 800, 9, 3, 5, FALL, 50),
                s("sell4", "Ore Value IV", "+50% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 900, 9, 3, 7, SELL, 50),
                s("eff4", "Mining Speed IV", "+60% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff3", 1000, 12, 4, 1, SPEED, 60),
                s("mine3x3", "3x3 Mining", "Ability: breaks a 3x3 area at once. Needs a pickaxe. Use /ability mine3x3 true", Items.DIAMOND_PICKAXE, "nightvision", 1500, 12, 4, 3, ABILITY, 0),
                s("supereff", "Super Efficiency", "Like an extra Efficiency III enchantment on your tool (stacks with everything)", Items.NETHERITE_PICKAXE, "fall", 900, 12, 4, 5, EFFICIENCY, 10),
                s("sell5", "Ore Value V", "+100% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 1800, 12, 4, 7, SELL, 100)
        );
        Set<String> minerSells = Set.of(
                "minecraft:coal", "minecraft:raw_copper", "minecraft:copper_ingot", "minecraft:raw_iron",
                "minecraft:iron_ingot", "minecraft:iron_nugget", "minecraft:raw_gold", "minecraft:gold_ingot",
                "minecraft:gold_nugget", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:quartz",
                "minecraft:amethyst_shard", "minecraft:glowstone_dust", "minecraft:diamond", "minecraft:emerald",
                "minecraft:ancient_debris", "minecraft:netherite_scrap", "minecraft:netherite_ingot",
                "minecraft:obsidian", "minecraft:coal_block", "minecraft:copper_block", "minecraft:iron_block",
                "minecraft:gold_block", "minecraft:redstone_block", "minecraft:lapis_block",
                "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:netherite_block");
        out.add(new ClassDef("miner", "Miner", Category.ECONOMY, Items.IRON_PICKAXE, miner, minerSells));

        // ---- Farmer. Total cost of all skills = 10,150 Class XP.
        List<Skill> farmer = List.of(
                s("sell1", "Harvest Value I", "+50% money for farm goods sold with /sell (adds up)", Items.EMERALD, null, 100, 1, 0, 4, SELL, 50),
                s("growth1", "Fast Growth I", "+20% crop growth speed around you (adds up)", Items.BONE_MEAL, null, 150, 1, 0, 2, GROWTH, 20),
                s("sell2", "Harvest Value II", "+80% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell1", 300, 3, 1, 6, SELL, 80),
                s("growth2", "Fast Growth II", "+40% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth1", 300, 3, 1, 1, GROWTH, 40),
                s("veinharvester", "Vein Harvester", "Ability: harvesting one ripe crop harvests all connected ripe crops on the same level. Use /ability veinharvester true", Items.IRON_HOE, "growth1", 1200, 6, 1, 3, ABILITY, 0),
                s("sell3", "Harvest Value III", "+100% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell2", 700, 6, 2, 6, SELL, 100),
                s("growth3", "Fast Growth III", "+50% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth2", 600, 6, 2, 1, GROWTH, 50),
                s("instantgrow", "Instant Grow", "Ability: right-click a crop with a hoe to fully grow it and all connected crops. 5 minute cooldown. Use /ability instantgrow true", Items.DIAMOND_HOE, "veinharvester", 2400, 12, 3, 3, ABILITY, 0),
                s("sell4", "Harvest Value IV", "+150% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell3", 2000, 12, 3, 6, SELL, 150),
                s("growth4", "Fast Growth IV", "+60% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth3", 900, 9, 3, 1, GROWTH, 60),
                s("growth5", "Fast Growth V", "+100% crop growth speed around you (adds up)", Items.WHEAT_SEEDS, "growth4", 1500, 12, 4, 1, GROWTH, 100)
        );
        Set<String> farmerSells = Set.of(
                "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:baked_potato",
                "minecraft:poisonous_potato", "minecraft:beetroot", "minecraft:sugar_cane", "minecraft:sugar",
                "minecraft:pumpkin", "minecraft:melon_slice", "minecraft:apple", "minecraft:sweet_berries",
                "minecraft:glow_berries", "minecraft:chorus_fruit", "minecraft:cocoa_beans", "minecraft:nether_wart",
                "minecraft:kelp", "minecraft:dried_kelp", "minecraft:bamboo", "minecraft:egg",
                "minecraft:honey_bottle", "minecraft:bread", "minecraft:cookie", "minecraft:pumpkin_pie");
        out.add(new ClassDef("farmer", "Farmer", Category.ECONOMY, Items.WHEAT, farmer, farmerSells));

        // ---- Lumberjack. Total cost of all skills = 10,000 Class XP.
        List<Skill> lumberjack = List.of(
                s("growth1", "Fast Growth I", "+40% sapling growth speed around you (adds up)", Items.BONE_MEAL, null, 100, 1, 0, 3, GROWTH, 40),
                s("growth2", "Fast Growth II", "+50% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth1", 250, 3, 1, 3, GROWTH, 50),
                s("growth3", "Fast Growth III", "+60% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth2", 450, 6, 2, 3, GROWTH, 60),
                s("growth4", "Fast Growth IV", "+70% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth3", 700, 9, 3, 3, GROWTH, 70),
                s("growth5", "Fast Growth V", "+120% sapling growth speed around you (adds up)", Items.OAK_SAPLING, "growth4", 1500, 12, 4, 4, GROWTH, 120),
                s("treegrow", "Instant Tree Growth", "Ability: right-click a sapling with an axe and it grows into a full tree right away. 30 second cooldown. Use /ability treegrow true", Items.DIAMOND_AXE, "growth4", 2400, 12, 4, 2, ABILITY, 0),
                s("chop1", "Chopping Speed I", "+20% chopping speed while holding an axe (adds up)", Items.GOLDEN_AXE, "growth1", 150, 3, 1, 1, SPEED, 20),
                s("chop2", "Chopping Speed II", "+30% chopping speed while holding an axe (adds up)", Items.GOLDEN_AXE, "chop1", 350, 6, 2, 1, SPEED, 30),
                s("chop3", "Chopping Speed III", "+50% chopping speed while holding an axe (adds up)", Items.NETHERITE_AXE, "chop2", 700, 9, 3, 1, SPEED, 50),
                s("sell1", "Timber Value I", "+40% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, null, 100, 1, 0, 6, SELL, 40),
                s("sell2", "Timber Value II", "+50% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 1, 6, SELL, 50),
                s("sell3", "Timber Value III", "+60% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 450, 6, 2, 6, SELL, 60),
                s("sell4", "Timber Value IV", "+70% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 800, 9, 3, 6, SELL, 70),
                s("sell5", "Timber Value V", "+120% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 1800, 12, 4, 6, SELL, 120)
        );
        Set<String> lumberSells = new HashSet<>();
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"}) {
            lumberSells.add("minecraft:" + wood + "_log");
            lumberSells.add("minecraft:" + wood + "_wood");
            lumberSells.add("minecraft:" + wood + "_planks");
            lumberSells.add("minecraft:" + wood + "_leaves");
        }
        for (String tree : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "pale_oak"}) {
            lumberSells.add("minecraft:" + tree + "_sapling");
        }
        lumberSells.addAll(List.of("minecraft:mangrove_propagule", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves",
                "minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:crimson_planks", "minecraft:warped_planks"));
        out.add(new ClassDef("lumberjack", "Lumberjack", Category.ECONOMY, Items.IRON_AXE, lumberjack, lumberSells));

        // ---- The other classes: selectable already, their trees come later.
        // ---- Hunter. Total cost of all skills = 10,250 Class XP.
        List<Skill> hunter = List.of(
                s("sell1", "Hunter Value I", "+40% money for meat and hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, null, 100, 1, 0, 4, SELL, 40),
                s("sell2", "Hunter Value II", "+50% money for meat and hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 1, 4, SELL, 50),
                s("meatluck", "Lucky Drops", "Ability: animals you kill drop 1 or 2 extra meat. Use /ability meatluck true", Items.RABBIT_FOOT, "sell2", 2000, 6, 2, 2, ABILITY, 0),
                s("sell3", "Hunter Value III", "+60% money for meat and hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 500, 6, 2, 4, SELL, 60),
                s("sell4", "Hunter Value IV", "+70% money for meat and hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 900, 9, 3, 4, SELL, 70),
                s("autocook", "Auto Cook", "Ability: meat from animals you kill drops already cooked. Use /ability autocook true", Items.COOKED_PORKCHOP, "meatluck", 4000, 12, 3, 2, ABILITY, 0),
                s("sell5", "Hunter Value V", "+100% money for meat and hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 2500, 12, 4, 4, SELL, 100)
        );
        Set<String> hunterSells = Set.of(
                "minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken", "minecraft:rabbit",
                "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
                "minecraft:cooked_chicken", "minecraft:cooked_rabbit", "minecraft:leather", "minecraft:feather");
        out.add(new ClassDef("hunter", "Hunter", Category.ECONOMY, Items.COOKED_BEEF, hunter, hunterSells));
        // ---- Archer. Total cost of all skills = 10,000 Class XP.
        List<Skill> archer = List.of(
                s("dmg1", "Arrow Damage I", "+20% arrow damage (adds up)", Items.ARROW, null, 100, 1, 0, 4, DAMAGE, 20),
                s("dmg2", "Arrow Damage II", "+40% arrow damage (adds up)", Items.ARROW, "dmg1", 300, 3, 1, 3, DAMAGE, 40),
                s("res1", "Arrow Resistance I", "10% less damage from arrows (adds up)", Items.SHIELD, "dmg1", 300, 3, 1, 5, ARROW_RESIST, 10),
                s("ultrange", "Ultimate Range", "Ability: your arrows fly much further and faster. Use /ability ultrange true", Items.SPECTRAL_ARROW, "dmg2", 1000, 6, 2, 2, ABILITY, 0),
                s("dmg3", "Arrow Damage III", "+50% arrow damage (adds up)", Items.ARROW, "dmg2", 600, 6, 2, 4, DAMAGE, 50),
                s("res2", "Arrow Resistance II", "20% less damage from arrows (adds up)", Items.SHIELD, "res1", 600, 6, 2, 6, ARROW_RESIST, 20),
                s("tracker", "Tracker Arrow", "Ability: your arrows steer towards where you look while they fly. Use /ability tracker true", Items.ENDER_EYE, "ultrange", 1800, 9, 3, 2, ABILITY, 0),
                s("dmg4", "Arrow Damage IV", "+60% arrow damage (adds up)", Items.ARROW, "dmg3", 900, 9, 3, 4, DAMAGE, 60),
                s("res3", "Arrow Resistance III", "20% less damage from arrows (adds up)", Items.SHIELD, "res2", 900, 9, 3, 6, ARROW_RESIST, 20),
                s("poisonarrow", "Poisonous Arrow", "Ability: every arrow you shoot poisons its target, no potion needed. Use /ability poisonarrow true", Items.SPIDER_EYE, "tracker", 2000, 12, 4, 3, ABILITY, 0),
                s("dmg5", "Arrow Damage V", "+100% arrow damage (adds up)", Items.TIPPED_ARROW, "dmg4", 1500, 12, 4, 4, DAMAGE, 100)
        );
        out.add(new ClassDef("archer", "Archer", Category.COMBAT, Items.BOW, archer, Set.of()));
        // ---- Berserker (axes). Total cost of all skills = 10,000 Class XP.
        List<Skill> berserker = List.of(
                s("dmg1", "Axe Damage I", "+10% damage with axes (adds up)", Items.IRON_AXE, null, 100, 1, 0, 4, DAMAGE, 10),
                s("run1", "Swift Feet I", "+5% running speed (adds up)", Items.LEATHER_BOOTS, "dmg1", 150, 3, 1, 2, RUNSPEED, 5),
                s("dmg2", "Axe Damage II", "+20% damage with axes (adds up)", Items.IRON_AXE, "dmg1", 300, 3, 1, 4, DAMAGE, 20),
                s("res1", "Toughness I", "5% less damage from everything (adds up)", Items.IRON_CHESTPLATE, "dmg1", 300, 3, 1, 6, RESIST, 5),
                s("run2", "Swift Feet II", "+10% running speed (adds up)", Items.GOLDEN_BOOTS, "run1", 400, 6, 2, 2, RUNSPEED, 10),
                s("parry", "Parry", "Ability: when you hit an enemy with an axe, it loses half of its current health. 30 second cooldown. Use /ability parry true", Items.SHIELD, "dmg2", 1500, 6, 2, 4, ABILITY, 0),
                s("res2", "Toughness II", "10% less damage from everything (adds up)", Items.DIAMOND_CHESTPLATE, "res1", 500, 6, 2, 6, RESIST, 10),
                s("run3", "Swift Feet III", "+15% running speed (adds up)", Items.DIAMOND_BOOTS, "run2", 800, 9, 3, 2, RUNSPEED, 15),
                s("sharp", "Sharpness VI", "+3.5 damage with axes, like a Sharpness VI enchantment (no book needed, adds to a real Sharpness enchant)", Items.ENCHANTED_BOOK, "parry", 1500, 9, 3, 3, SHARP, 6),
                s("dmg3", "Axe Damage III", "+30% damage with axes (adds up)", Items.DIAMOND_AXE, "parry", 800, 9, 3, 5, DAMAGE, 30),
                s("res3", "Toughness III", "15% less damage from everything (adds up)", Items.NETHERITE_CHESTPLATE, "res2", 900, 9, 3, 6, RESIST, 15),
                s("fire", "Fire Aspect", "Your axe hits set the enemy on fire for 4 seconds (no book needed)", Items.FLINT_AND_STEEL, "sharp", 900, 12, 4, 3, FIRE, 4),
                s("doubleaxes", "Double Axes", "Ability: your axe hits do double damage, as if you swung two axes (no second axe needed). Use /ability doubleaxes true", Items.NETHERITE_AXE, "dmg3", 1850, 12, 4, 5, ABILITY, 0)
        );
        out.add(new ClassDef("berserker", "Berserker", Category.COMBAT, Items.DIAMOND_AXE, berserker, Set.of()));
        out.add(new ClassDef("warrior", "Warrior", Category.COMBAT, Items.IRON_SWORD, List.of(), Set.of()));
        out.add(new ClassDef("mage", "Mage", Category.COMBAT, Items.BLAZE_ROD, List.of(), Set.of()));
        return out;
    }
}
