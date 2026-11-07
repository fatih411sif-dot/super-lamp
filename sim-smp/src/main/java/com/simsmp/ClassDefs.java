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
    static final String ABILITY = "ability";
    /** +value% attack damage (applies to the whole hit, weapon included). */
    static final String DAMAGE = "damage";
    /** +value armor points. */
    static final String ARMOR = "armor";
    /** +value% walking speed. */
    static final String MOVE = "move";
    /** +value extra Class XP for every kill that already gives XP. */
    static final String XPKILL = "xp";

    enum Category {
        ECONOMY("Economy"), COMBAT("Combat");
        final String label;
        Category(String label) { this.label = label; }
    }

    /**
     * Position inside the tree window. The tree is drawn on a lattice so the resource pack can draw lines between skills:
     * row is 0, 2 or 4 and col is 0, 2, 4, 6 or 8 (every other slot is left empty for the connecting lines).
     */
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
                s("eff1", "Mining Speed I", "+10% mining speed (adds up)", Items.GOLDEN_PICKAXE, "sell1", 150, 3, 0, 6, SPEED, 10),
                s("sell2", "Ore Value II", "+20% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 0, 2, SELL, 20),
                s("eff2", "Mining Speed II", "+20% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff1", 300, 6, 0, 8, SPEED, 20),
                s("veinminer", "Vein Miner", "Ability: mining an ore breaks the whole connected vein. Needs a pickaxe. Use /ability veinminer true", Items.RAW_IRON, "eff1", 600, 6, 2, 8, ABILITY, 0),
                s("sell3", "Ore Value III", "+30% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 450, 6, 0, 0, SELL, 30),
                s("eff3", "Mining Speed III", "+40% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff2", 600, 9, 2, 6, SPEED, 40),
                s("nightvision", "Night Vision", "Ability: night vision with no time limit. Use /ability nightvision true", Items.GOLDEN_CARROT, "veinminer", 900, 9, 4, 6, ABILITY, 0),
                s("fall", "Light Landing", "50% less fall damage", Items.FEATHER, "sell3", 800, 9, 2, 0, FALL, 50),
                s("sell4", "Ore Value IV", "+50% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 900, 9, 2, 2, SELL, 50),
                s("eff4", "Mining Speed IV", "+60% mining speed (adds up)", Items.GOLDEN_PICKAXE, "eff3", 1000, 12, 4, 8, SPEED, 60),
                s("mine3x3", "3x3 Mining", "Ability: breaks a 3x3 area at once. Needs a pickaxe. Use /ability mine3x3 true", Items.DIAMOND_PICKAXE, "nightvision", 1500, 12, 4, 4, ABILITY, 0),
                s("supereff", "Super Efficiency", "Like an extra Efficiency III enchantment on your tool (stacks with everything)", Items.NETHERITE_PICKAXE, "fall", 900, 12, 4, 2, EFFICIENCY, 10),
                s("sell5", "Ore Value V", "+100% money for every ore sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 1800, 12, 4, 0, SELL, 100)
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
                s("sell1", "Harvest Value I", "+50% money for farm goods sold with /sell (adds up)", Items.EMERALD, null, 100, 1, 0, 6, SELL, 50),
                s("growth1", "Fast Growth I", "+20% crop growth speed around you (adds up)", Items.BONE_MEAL, null, 150, 1, 0, 2, GROWTH, 20),
                s("sell2", "Harvest Value II", "+80% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell1", 300, 3, 2, 4, SELL, 80),
                s("growth2", "Fast Growth II", "+40% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth1", 300, 3, 0, 4, GROWTH, 40),
                s("veinharvester", "Vein Harvester", "Ability: harvesting one ripe crop harvests all connected ripe crops on the same level. Use /ability veinharvester true", Items.IRON_HOE, "growth1", 1200, 6, 2, 0, ABILITY, 0),
                s("sell3", "Harvest Value III", "+100% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell2", 700, 6, 2, 2, SELL, 100),
                s("growth3", "Fast Growth III", "+50% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth2", 600, 6, 2, 6, GROWTH, 50),
                s("instantgrow", "Instant Grow", "Ability: right-click a crop with a hoe to fully grow it and all connected crops. 5 minute cooldown. Use /ability instantgrow true", Items.DIAMOND_HOE, "veinharvester", 2400, 12, 4, 2, ABILITY, 0),
                s("sell4", "Harvest Value IV", "+150% money for farm goods sold with /sell (adds up)", Items.EMERALD, "sell3", 2000, 12, 4, 4, SELL, 150),
                s("growth4", "Fast Growth IV", "+60% crop growth speed around you (adds up)", Items.BONE_MEAL, "growth3", 900, 9, 4, 8, GROWTH, 60),
                s("growth5", "Fast Growth V", "+100% crop growth speed around you (adds up)", Items.WHEAT_SEEDS, "growth4", 1500, 12, 4, 6, GROWTH, 100)
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
                s("growth1", "Fast Growth I", "+40% sapling growth speed around you (adds up)", Items.BONE_MEAL, null, 100, 1, 0, 4, GROWTH, 40),
                s("growth2", "Fast Growth II", "+50% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth1", 250, 3, 2, 6, GROWTH, 50),
                s("growth3", "Fast Growth III", "+60% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth2", 450, 6, 2, 4, GROWTH, 60),
                s("growth4", "Fast Growth IV", "+70% sapling growth speed around you (adds up)", Items.BONE_MEAL, "growth3", 700, 9, 2, 2, GROWTH, 70),
                s("growth5", "Fast Growth V", "+120% sapling growth speed around you (adds up)", Items.OAK_SAPLING, "growth4", 1500, 12, 4, 4, GROWTH, 120),
                s("treegrow", "Instant Tree Growth", "Ability: right-click a sapling with an axe and it grows into a full tree right away. 30 second cooldown. Use /ability treegrow true", Items.DIAMOND_AXE, "growth4", 2400, 12, 4, 0, ABILITY, 0),
                s("chop1", "Chopping Speed I", "+20% chopping speed while holding an axe (adds up)", Items.GOLDEN_AXE, "growth1", 150, 3, 0, 2, SPEED, 20),
                s("chop2", "Chopping Speed II", "+30% chopping speed while holding an axe (adds up)", Items.GOLDEN_AXE, "chop1", 350, 6, 2, 0, SPEED, 30),
                s("chop3", "Chopping Speed III", "+50% chopping speed while holding an axe (adds up)", Items.NETHERITE_AXE, "chop2", 700, 9, 4, 2, SPEED, 50),
                s("sell1", "Timber Value I", "+40% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, null, 100, 1, 0, 8, SELL, 40),
                s("sell2", "Timber Value II", "+50% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 0, 6, SELL, 50),
                s("sell3", "Timber Value III", "+60% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 450, 6, 2, 8, SELL, 60),
                s("sell4", "Timber Value IV", "+70% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 800, 9, 4, 6, SELL, 70),
                s("sell5", "Timber Value V", "+120% money for wood goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 1800, 12, 4, 8, SELL, 120)
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

        // ---- Hunter, Warrior, Berserker, Archer and Mage. Every class has about 6,000 Class XP of skills.
        // Kills give the XP (see KillXp). Perks are applied in ClassEffects.update().
        List<Skill> hunter = List.of(
                s("sell1", "Hunt Value I", "+40% money for hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, null, 100, 1, 0, 4, SELL, 40),
                s("move1", "Tracker I", "+8% walking speed (adds up)", Items.LEATHER_BOOTS, "sell1", 150, 3, 0, 2, MOVE, 8),
                s("sell2", "Hunt Value II", "+50% money for hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell1", 250, 3, 2, 6, SELL, 50),
                s("xp1", "Tracker's Instinct", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "move1", 300, 6, 2, 0, XPKILL, 1),
                s("fall1", "Light Landing", "40% less fall damage (adds up)", Items.FEATHER, "move1", 350, 6, 2, 4, FALL, 40),
                s("sell3", "Hunt Value III", "+60% money for hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell2", 450, 6, 2, 8, SELL, 60),
                s("move2", "Tracker II", "+10% walking speed (adds up)", Items.GOLDEN_BOOTS, "xp1", 600, 9, 2, 2, MOVE, 10),
                s("nightvision", "Night Hunter", "Ability: night vision with no time limit. Use /ability nightvision true", Items.GOLDEN_CARROT, "fall1", 700, 9, 4, 2, ABILITY, 0),
                s("sell4", "Hunt Value IV", "+70% money for hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell3", 800, 9, 4, 6, SELL, 70),
                s("xp2", "Master Tracker", "+2 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "move2", 900, 12, 4, 0, XPKILL, 2),
                s("move3", "Tracker III", "+12% walking speed (adds up)", Items.GOLDEN_BOOTS, "nightvision", 900, 12, 4, 4, MOVE, 12),
                s("sell5", "Hunt Value V", "+120% money for hunting goods sold with /sell (adds up)", Items.GOLD_INGOT, "sell4", 1800, 12, 4, 8, SELL, 120)
        );
        Set<String> hunterSells = Set.of(
                "minecraft:beef", "minecraft:cooked_beef", "minecraft:porkchop", "minecraft:cooked_porkchop",
                "minecraft:mutton", "minecraft:cooked_mutton", "minecraft:chicken", "minecraft:cooked_chicken",
                "minecraft:rabbit", "minecraft:cooked_rabbit", "minecraft:leather", "minecraft:feather",
                "minecraft:rabbit_hide", "minecraft:rabbit_foot", "minecraft:bone", "minecraft:string",
                "minecraft:spider_eye", "minecraft:ink_sac", "minecraft:glow_ink_sac", "minecraft:gunpowder",
                "minecraft:slime_ball", "minecraft:rotten_flesh", "minecraft:ender_pearl");
        out.add(new ClassDef("hunter", "Hunter", Category.ECONOMY, Items.COOKED_BEEF, hunter, hunterSells));

        List<Skill> archer = List.of(
                s("swift1", "Light Step I", "+8% walking speed (adds up)", Items.LEATHER_BOOTS, null, 100, 1, 0, 4, MOVE, 8),
                s("feather1", "Feather Fall I", "30% less fall damage (adds up)", Items.FEATHER, "swift1", 150, 3, 0, 6, FALL, 30),
                s("xp1", "Sharp Eye", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "swift1", 200, 3, 0, 2, XPKILL, 1),
                s("swift2", "Light Step II", "+10% walking speed (adds up)", Items.GOLDEN_BOOTS, "feather1", 300, 6, 2, 8, MOVE, 10),
                s("nightvision", "Hawk Eyes", "Ability: night vision with no time limit. Use /ability nightvision true", Items.GOLDEN_CARROT, "feather1", 600, 6, 2, 4, ABILITY, 0),
                s("armor1", "Padded Vest I", "+1 armor (adds up)", Items.IRON_CHESTPLATE, "xp1", 300, 6, 0, 0, ARMOR, 1),
                s("swift3", "Light Step III", "+12% walking speed (adds up)", Items.GOLDEN_BOOTS, "swift2", 600, 9, 2, 6, MOVE, 12),
                s("feather2", "Feather Fall II", "40% less fall damage (adds up)", Items.FEATHER, "nightvision", 500, 9, 4, 6, FALL, 40),
                s("xp2", "Marksman", "+2 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "armor1", 700, 9, 2, 2, XPKILL, 2),
                s("swift4", "Light Step IV", "+15% walking speed (adds up)", Items.GOLDEN_BOOTS, "swift3", 900, 12, 4, 8, MOVE, 15),
                s("armor2", "Padded Vest II", "+2 armor (adds up)", Items.DIAMOND_CHESTPLATE, "xp2", 800, 12, 4, 0, ARMOR, 2)
        );
        out.add(new ClassDef("archer", "Archer", Category.COMBAT, Items.BOW, archer, Set.of()));

        List<Skill> berserker = List.of(
                s("rage1", "Rage I", "+8% damage (adds up)", Items.IRON_AXE, null, 100, 1, 0, 4, DAMAGE, 8),
                s("fast1", "Bloodrush I", "+8% walking speed (adds up)", Items.LEATHER_BOOTS, "rage1", 150, 3, 0, 2, MOVE, 8),
                s("hide1", "Thick Hide I", "+1 armor (adds up)", Items.IRON_CHESTPLATE, "rage1", 150, 3, 2, 6, ARMOR, 1),
                s("rage2", "Rage II", "+12% damage (adds up)", Items.IRON_AXE, "fast1", 300, 6, 2, 0, DAMAGE, 12),
                s("xp1", "Bloodlust", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "fast1", 400, 6, 2, 4, XPKILL, 1),
                s("fall1", "Heavy Landing", "40% less fall damage (adds up)", Items.FEATHER, "hide1", 350, 6, 2, 8, FALL, 40),
                s("rage3", "Rage III", "+18% damage (adds up)", Items.DIAMOND_AXE, "rage2", 600, 9, 2, 2, DAMAGE, 18),
                s("fast2", "Bloodrush II", "+12% walking speed (adds up)", Items.GOLDEN_BOOTS, "xp1", 600, 9, 4, 2, MOVE, 12),
                s("hide2", "Thick Hide II", "+2 armor (adds up)", Items.DIAMOND_CHESTPLATE, "fall1", 600, 9, 4, 6, ARMOR, 2),
                s("rage4", "Rage IV", "+25% damage (adds up)", Items.NETHERITE_AXE, "rage3", 1000, 12, 4, 4, DAMAGE, 25),
                s("fast3", "Bloodrush III", "+15% walking speed (adds up)", Items.GOLDEN_BOOTS, "fast2", 900, 12, 4, 0, MOVE, 15),
                s("xp2", "Warlord", "+2 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "hide2", 900, 12, 4, 8, XPKILL, 2)
        );
        out.add(new ClassDef("berserker", "Berserker", Category.COMBAT, Items.DIAMOND_AXE, berserker, Set.of()));

        List<Skill> warrior = List.of(
                s("dmg1", "Sharpened Blade I", "+5% damage (adds up)", Items.IRON_SWORD, null, 100, 1, 0, 4, DAMAGE, 5),
                s("arm1", "Iron Skin I", "+2 armor (adds up)", Items.IRON_CHESTPLATE, "dmg1", 150, 3, 0, 6, ARMOR, 2),
                s("move1", "Swift Footing I", "+5% walking speed (adds up)", Items.LEATHER_BOOTS, "dmg1", 150, 3, 0, 2, MOVE, 5),
                s("dmg2", "Sharpened Blade II", "+10% damage (adds up)", Items.IRON_SWORD, "arm1", 300, 6, 2, 8, DAMAGE, 10),
                s("xp1", "Battle Hardened", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "arm1", 400, 6, 2, 4, XPKILL, 1),
                s("fall1", "Light Landing", "30% less fall damage (adds up)", Items.FEATHER, "move1", 350, 6, 2, 0, FALL, 30),
                s("dmg3", "Sharpened Blade III", "+15% damage (adds up)", Items.DIAMOND_SWORD, "dmg2", 600, 9, 2, 6, DAMAGE, 15),
                s("arm2", "Iron Skin II", "+3 armor (adds up)", Items.DIAMOND_CHESTPLATE, "xp1", 600, 9, 4, 6, ARMOR, 3),
                s("move2", "Swift Footing II", "+10% walking speed (adds up)", Items.GOLDEN_BOOTS, "fall1", 600, 9, 4, 2, MOVE, 10),
                s("dmg4", "Sharpened Blade IV", "+20% damage (adds up)", Items.NETHERITE_SWORD, "dmg3", 1000, 12, 4, 8, DAMAGE, 20),
                s("arm3", "Iron Skin III", "+4 armor (adds up)", Items.NETHERITE_CHESTPLATE, "arm2", 1000, 12, 4, 4, ARMOR, 4),
                s("xp2", "Veteran", "+2 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "move2", 900, 12, 4, 0, XPKILL, 2)
        );
        out.add(new ClassDef("warrior", "Warrior", Category.COMBAT, Items.IRON_SWORD, warrior, Set.of()));

        List<Skill> mage = List.of(
                s("arcane1", "Arcane Focus I", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, null, 100, 1, 0, 4, XPKILL, 1),
                s("nightvision", "Mage Sight", "Ability: night vision with no time limit. Use /ability nightvision true", Items.GOLDEN_CARROT, "arcane1", 400, 3, 0, 6, ABILITY, 0),
                s("feather1", "Featherweight", "50% less fall damage (adds up)", Items.FEATHER, "arcane1", 150, 3, 0, 2, FALL, 50),
                s("swift1", "Haste I", "+8% walking speed (adds up)", Items.SUGAR, "nightvision", 300, 6, 2, 4, MOVE, 8),
                s("ward1", "Mana Ward I", "+2 armor (adds up)", Items.IRON_CHESTPLATE, "nightvision", 350, 6, 0, 8, ARMOR, 2),
                s("arcane2", "Arcane Focus II", "+1 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "feather1", 400, 6, 2, 0, XPKILL, 1),
                s("swift2", "Haste II", "+10% walking speed (adds up)", Items.SUGAR, "swift1", 600, 9, 2, 2, MOVE, 10),
                s("ward2", "Mana Ward II", "+3 armor (adds up)", Items.DIAMOND_CHESTPLATE, "ward1", 700, 9, 2, 6, ARMOR, 3),
                s("arcane3", "Arcane Focus III", "+2 Class XP for every kill that gives XP (adds up)", Items.EXPERIENCE_BOTTLE, "arcane2", 700, 9, 4, 2, XPKILL, 2),
                s("swift3", "Haste III", "+12% walking speed (adds up)", Items.SUGAR, "swift2", 900, 12, 4, 0, MOVE, 12),
                s("ward3", "Mana Ward III", "+4 armor (adds up)", Items.NETHERITE_CHESTPLATE, "ward2", 1000, 12, 4, 8, ARMOR, 4),
                s("arcane4", "Arcane Focus IV", "+3 Class XP for every kill that gives XP (adds up)", Items.NETHER_STAR, "arcane3", 1000, 12, 4, 4, XPKILL, 3)
        );
        out.add(new ClassDef("mage", "Mage", Category.COMBAT, Items.BLAZE_ROD, mage, Set.of()));
        return out;
    }
}
