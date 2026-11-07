package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player classes (classes.json).
 *
 * Every player picks 2 economy + 2 combat classes once, and has ONE of those four equipped at a time.
 * Each class keeps its own total XP and unlocked skills, so switching away and back loses nothing.
 * Class XP is also the currency for skills: available XP = total earned - spent. The level only
 * depends on total earned XP, so buying a skill never lowers it.
 */
public final class ClassManager {
    public static final int PICKS_PER_CATEGORY = 2;

    /** Progress inside one class. */
    public static final class ClassData {
        public long totalXp = 0;
        public long spentXp = 0;
        public List<String> skills = new ArrayList<>();
    }

    /** Everything saved about one player. */
    public static final class PlayerData {
        public List<String> picks = new ArrayList<>();
        public String active = "";
        public long lastSwitch = 0;
        public Map<String, ClassData> classes = new HashMap<>();
        /** Abilities the player switched on with /ability (they only work while the class is equipped). */
        public List<String> abilities = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<HashMap<String, PlayerData>>() {}.getType();

    private final SimConfig cfg;
    private final EconomyManager eco;
    private final Path file;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private boolean dirty;

    public ClassManager(SimConfig cfg, EconomyManager eco, Path dir) {
        this.cfg = cfg;
        this.eco = eco;
        this.file = dir.resolve("classes.json");
    }

    // ------------------------------------------------------------------ files

    public synchronized void load() {
        players.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, PlayerData> raw = GSON.fromJson(r, TYPE);
            if (raw != null) {
                raw.forEach((k, v) -> {
                    try {
                        if (v == null) return;
                        if (v.picks == null) v.picks = new ArrayList<>();
                        if (v.active == null) v.active = "";
                        if (v.classes == null) v.classes = new HashMap<>();
                        if (v.abilities == null) v.abilities = new ArrayList<>();
                        v.classes.values().removeIf(c -> c == null);
                        v.classes.values().forEach(c -> {
                            if (c.skills == null) c.skills = new ArrayList<>();
                        });
                        players.put(UUID.fromString(k), v);
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("classes.json is unreadable - keeping a copy and starting fresh", e);
            try {
                Files.move(file, file.resolveSibling("classes.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        dirty = false;
        Map<String, PlayerData> raw = new HashMap<>();
        players.forEach((k, v) -> raw.put(k.toString(), v));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("classes.json.tmp");
            Files.writeString(tmp, GSON.toJson(raw));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty = true;
            SimSmp.LOGGER.error("Could not save classes.json", e);
        }
    }

    // ------------------------------------------------------------------ reading

    private PlayerData data(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerData());
    }

    private ClassData classData(UUID id, String classId) {
        return data(id).classes.computeIfAbsent(classId, k -> new ClassData());
    }

    public static int levelOf(long totalXp) {
        return 1 + (int) Math.sqrt(Math.max(0, totalXp) / 60.0);
    }

    public synchronized List<String> picks(UUID id) {
        return new ArrayList<>(data(id).picks);
    }

    public synchronized int picksIn(UUID id, ClassDefs.Category category) {
        int n = 0;
        for (String pick : data(id).picks) {
            ClassDefs.ClassDef def = ClassDefs.get(pick);
            if (def != null && def.category() == category) n++;
        }
        return n;
    }

    public synchronized boolean setupDone(UUID id) {
        return picksIn(id, ClassDefs.Category.ECONOMY) >= PICKS_PER_CATEGORY
                && picksIn(id, ClassDefs.Category.COMBAT) >= PICKS_PER_CATEGORY;
    }

    public synchronized boolean isPicked(UUID id, String classId) {
        return data(id).picks.contains(classId);
    }

    /** The equipped class id, or "" for none. */
    public synchronized String active(UUID id) {
        return data(id).active;
    }

    public synchronized long totalXp(UUID id, String classId) {
        ClassData c = data(id).classes.get(classId);
        return c == null ? 0 : c.totalXp;
    }

    /** Class XP that can still be spent on skills. */
    public synchronized long availableXp(UUID id, String classId) {
        ClassData c = data(id).classes.get(classId);
        return c == null ? 0 : c.totalXp - c.spentXp;
    }

    public synchronized int level(UUID id, String classId) {
        return levelOf(totalXp(id, classId));
    }

    public synchronized boolean hasSkill(UUID id, String classId, String skillId) {
        ClassData c = data(id).classes.get(classId);
        return c != null && c.skills.contains(skillId);
    }

    /** Remaining wait in milliseconds before the player may switch class again (0 = can switch now). */
    public synchronized long switchWaitMillis(UUID id) {
        long wait = data(id).lastSwitch + cfg.classSwitchMinutes * 60_000L - System.currentTimeMillis();
        return Math.max(0, wait);
    }

    // ------------------------------------------------------------------ choosing classes

    /** @return an error text, or null when it worked. Only allowed while the player is still choosing. */
    public synchronized String togglePick(UUID id, String classId) {
        ClassDefs.ClassDef def = ClassDefs.get(classId);
        if (def == null) return "Unknown class.";
        PlayerData d = data(id);
        if (setupDone(id)) return "You already chose your 4 classes.";
        if (d.picks.contains(classId)) {
            d.picks.remove(classId);
        } else {
            if (picksIn(id, def.category()) >= PICKS_PER_CATEGORY) {
                return "You already picked " + PICKS_PER_CATEGORY + " " + def.category().label + " classes. Click one of them to un-pick it.";
            }
            d.picks.add(classId);
        }
        dirty = true;
        return null;
    }

    /** Equips one of the 4 chosen classes. @return an error text, or null when it worked. */
    public synchronized String equip(UUID id, String classId) {
        PlayerData d = data(id);
        if (!d.picks.contains(classId)) return "You did not choose that class.";
        if (classId.equals(d.active)) return "That class is already equipped.";
        long wait = switchWaitMillis(id);
        if (!d.active.isEmpty() && wait > 0) {
            return "You can change class again in " + DailyShopMenu.duration(wait) + ".";
        }
        d.active = classId;
        d.lastSwitch = System.currentTimeMillis();
        dirty = true;
        return null;
    }

    /** Replaces one chosen class with another of the same category for a fee. The old class loses all progress. */
    public synchronized String replacePick(UUID id, String oldId, String newId) {
        PlayerData d = data(id);
        ClassDefs.ClassDef oldDef = ClassDefs.get(oldId);
        ClassDefs.ClassDef newDef = ClassDefs.get(newId);
        if (oldDef == null || newDef == null) return "Unknown class.";
        if (!setupDone(id)) return "Finish choosing your 4 classes first.";
        if (!d.picks.contains(oldId)) return "You did not choose " + oldDef.name() + ".";
        if (d.picks.contains(newId)) return "You already chose " + newDef.name() + ".";
        if (oldDef.category() != newDef.category()) return "You can only swap within the same category (" + oldDef.category().label + ").";
        if (!eco.take(id, cfg.classChangeFee)) return "You need " + eco.fmt(cfg.classChangeFee) + " to change a class.";
        d.picks.remove(oldId);
        d.picks.add(newId);
        d.classes.remove(oldId); // level and XP go back to 0
        if (oldId.equals(d.active)) d.active = "";
        dirty = true;
        return null;
    }

    // ------------------------------------------------------------------ XP and skills

    /** Adds Class XP to the class the player currently has equipped. */
    public synchronized void addXp(ServerPlayer p, long amount) {
        if (amount <= 0) return;
        UUID id = p.getUUID();
        String active = data(id).active;
        if (active.isEmpty()) return;
        ClassData c = classData(id, active);
        int before = levelOf(c.totalXp);
        c.totalXp += amount;
        dirty = true;
        int after = levelOf(c.totalXp);
        if (after > before) {
            ClassDefs.ClassDef def = ClassDefs.get(active);
            Msg.ok(p, (def == null ? active : def.name()) + " reached level " + after + "!");
        }
    }

    /** Why a skill cannot be unlocked right now, or null when it can. */
    public synchronized String unlockProblem(UUID id, ClassDefs.ClassDef def, ClassDefs.Skill skill) {
        if (!skill.active()) return "This skill is not available yet.";
        if (hasSkill(id, def.id(), skill.id())) return "Already unlocked.";
        if (skill.parent() != null && !hasSkill(id, def.id(), skill.parent())) {
            ClassDefs.Skill parent = def.skill(skill.parent());
            return "Unlock " + (parent == null ? skill.parent() : parent.name()) + " first.";
        }
        if (level(id, def.id()) < skill.minLevel()) return "Needs " + def.name() + " level " + skill.minLevel() + ".";
        if (availableXp(id, def.id()) < skill.cost()) {
            return "Needs " + skill.cost() + " Class XP (you have " + availableXp(id, def.id()) + ").";
        }
        return null;
    }

    /** @return an error text, or null when it worked. */
    public synchronized String unlock(UUID id, String classId, String skillId) {
        ClassDefs.ClassDef def = ClassDefs.get(classId);
        if (def == null) return "Unknown class.";
        ClassDefs.Skill skill = def.skill(skillId);
        if (skill == null) return "Unknown skill.";
        if (!classId.equals(data(id).active)) return "Equip " + def.name() + " first.";
        String problem = unlockProblem(id, def, skill);
        if (problem != null) return problem;
        ClassData c = classData(id, classId);
        c.spentXp += skill.cost();
        c.skills.add(skillId);
        dirty = true;
        return null;
    }

    // ------------------------------------------------------------------ perks

    /** Sum of the unlocked perks of one kind (ClassDefs.SELL, SPEED, ...) of the equipped class. Perks add up, never overlap. */
    public synchronized int perkTotal(UUID id, String perk) {
        String active = data(id).active;
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) return 0;
        int total = 0;
        for (ClassDefs.Skill s : def.skills()) {
            if (perk.equals(s.perk()) && hasSkill(id, active, s.id())) total += s.value();
        }
        return total;
    }

    /** Total "+X% sell price" of the equipped class for this item (0 = no bonus). */
    public synchronized int sellBonusPercent(UUID id, String itemId) {
        ClassDefs.ClassDef def = ClassDefs.get(data(id).active);
        if (def == null || !def.sellItems().contains(itemId)) return 0;
        return perkTotal(id, ClassDefs.SELL);
    }

    // ------------------------------------------------------------------ abilities (/ability)

    /** The abilities of the equipped class that the player has unlocked, in tree order. */
    public synchronized List<ClassDefs.Skill> unlockedAbilities(UUID id) {
        List<ClassDefs.Skill> out = new ArrayList<>();
        String active = data(id).active;
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) return out;
        for (ClassDefs.Skill s : def.skills()) {
            if (ClassDefs.ABILITY.equals(s.perk()) && hasSkill(id, active, s.id())) out.add(s);
        }
        return out;
    }

    /** True when the ability is unlocked, belongs to the equipped class, and the player switched it on. */
    public synchronized boolean abilityUsable(UUID id, String name) {
        for (ClassDefs.Skill s : unlockedAbilities(id)) {
            if (s.id().equals(name)) return data(id).abilities.contains(name);
        }
        return false;
    }

    public synchronized boolean abilitySwitchedOn(UUID id, String name) {
        return data(id).abilities.contains(name);
    }

    /** @return an error text, or null when it worked. */
    public synchronized String setAbility(UUID id, String name, boolean on) {
        String active = data(id).active;
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) return "Equip a class first with /class.";
        ClassDefs.Skill s = def.skill(name);
        if (s == null || !ClassDefs.ABILITY.equals(s.perk())) return def.name() + " has no ability called '" + name + "'. Type /ability to see yours.";
        if (!hasSkill(id, active, name)) return "You have not unlocked " + s.name() + " yet.";
        List<String> list = data(id).abilities;
        list.remove(name);
        if (on) list.add(name);
        dirty = true;
        return null;
    }

    /** The ore "family" of a block ("minecraft:deepslate_iron_ore" -> "iron_ore"), or null when it is not an ore. */
    public static String oreFamily(String blockId) {
        if (mineXp(blockId) <= 0) return null;
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
    }

    /** Money for selling this stack, including the equipped class's sell bonus (never for items bought from /dshop). */
    public long sellValue(UUID id, ItemStack stack, long unitPrice) {
        long base = unitPrice * stack.getCount();
        if (DailyShop.isShopItem(stack)) return base;
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int bonus = sellBonusPercent(id, key);
        return bonus <= 0 ? base : base + base * bonus / 100;
    }

    // ------------------------------------------------------------------ XP from mining

    /** Class XP for breaking this block (0 = none). Only ores give XP, so plain stone cannot be farmed. */
    public static int mineXp(String blockId) {
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        if (path.startsWith("deepslate_")) path = path.substring("deepslate_".length());
        return switch (path) {
            case "coal_ore" -> 1;
            case "copper_ore" -> 1;
            case "iron_ore" -> 2;
            case "nether_gold_ore" -> 2;
            case "redstone_ore" -> 2;
            case "nether_quartz_ore" -> 2;
            case "gold_ore" -> 3;
            case "lapis_ore" -> 3;
            case "emerald_ore" -> 6;
            case "diamond_ore" -> 8;
            case "ancient_debris" -> 15;
            default -> 0;
        };
    }
}
