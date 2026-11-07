package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ClassManager {
   public static final int PICKS_PER_CATEGORY = 2;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Type TYPE = (new TypeToken<HashMap<String, ClassManager.PlayerData>>() {}).getType();
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final Path file;
   private final Map<UUID, ClassManager.PlayerData> players = new HashMap<>();
   private boolean dirty;

   public ClassManager(SimConfig cfg, EconomyManager eco, Path dir) {
      this.cfg = cfg;
      this.eco = eco;
      this.file = dir.resolve("classes.json");
   }

   public synchronized void load() {
      this.players.clear();
      if (Files.exists(this.file)) {
         try (Reader r = Files.newBufferedReader(this.file)) {
            Map<String, ClassManager.PlayerData> raw = (Map<String, ClassManager.PlayerData>)GSON.fromJson(r, TYPE);
            if (raw != null) {
               raw.forEach((k, v) -> {
                  try {
                     if (v == null) {
                        return;
                     }

                     if (v.picks == null) {
                        v.picks = new ArrayList<>();
                     }

                     if (v.active == null) {
                        v.active = "";
                     }

                     if (v.classes == null) {
                        v.classes = new HashMap<>();
                     }

                     if (v.abilities == null) {
                        v.abilities = new ArrayList<>();
                     }

                     v.classes.values().removeIf(c -> c == null);
                     v.classes.values().forEach(c -> {
                        if (c.skills == null) {
                           c.skills = new ArrayList<>();
                        }
                     });
                     this.players.put(UUID.fromString(k), v);
                  } catch (IllegalArgumentException var4x) {
                  }
               });
            }
         } catch (RuntimeException | IOException var7) {
            SimSmp.LOGGER.error("classes.json is unreadable - keeping a copy and starting fresh", var7);

            try {
               Files.move(this.file, this.file.resolveSibling("classes.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException var4) {
            }
         }
      }
   }

   public synchronized void save() {
      if (this.dirty) {
         this.dirty = false;
         Map<String, ClassManager.PlayerData> raw = new HashMap<>();
         this.players.forEach((k, v) -> raw.put(k.toString(), v));

         try {
            Files.createDirectories(this.file.getParent());
            Path tmp = this.file.resolveSibling("classes.json.tmp");
            Files.writeString(tmp, GSON.toJson(raw));

            try {
               Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var4) {
               Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException var5) {
            this.dirty = true;
            SimSmp.LOGGER.error("Could not save classes.json", var5);
         }
      }
   }

   private ClassManager.PlayerData data(UUID id) {
      return this.players.computeIfAbsent(id, k -> new ClassManager.PlayerData());
   }

   private ClassManager.ClassData classData(UUID id, String classId) {
      return this.data(id).classes.computeIfAbsent(classId, k -> new ClassManager.ClassData());
   }

   public static int levelOf(long totalXp) {
      return 1 + (int)Math.sqrt(Math.max(0L, totalXp) / 60.0);
   }

   public synchronized List<String> picks(UUID id) {
      return new ArrayList<>(this.data(id).picks);
   }

   public synchronized int picksIn(UUID id, ClassDefs.Category category) {
      int n = 0;

      for (String pick : this.data(id).picks) {
         ClassDefs.ClassDef def = ClassDefs.get(pick);
         if (def != null && def.category() == category) {
            n++;
         }
      }

      return n;
   }

   public synchronized boolean setupDone(UUID id) {
      return this.picksIn(id, ClassDefs.Category.ECONOMY) >= 2 && this.picksIn(id, ClassDefs.Category.COMBAT) >= 2;
   }

   public synchronized boolean isPicked(UUID id, String classId) {
      return this.data(id).picks.contains(classId);
   }

   public synchronized String active(UUID id) {
      return this.data(id).active;
   }

   public synchronized long totalXp(UUID id, String classId) {
      ClassManager.ClassData c = this.data(id).classes.get(classId);
      return c == null ? 0L : c.totalXp;
   }

   public synchronized long availableXp(UUID id, String classId) {
      ClassManager.ClassData c = this.data(id).classes.get(classId);
      return c == null ? 0L : c.totalXp - c.spentXp;
   }

   public synchronized int level(UUID id, String classId) {
      return levelOf(this.totalXp(id, classId));
   }

   public synchronized boolean hasSkill(UUID id, String classId, String skillId) {
      ClassManager.ClassData c = this.data(id).classes.get(classId);
      return c != null && c.skills.contains(skillId);
   }

   public synchronized long switchWaitMillis(UUID id) {
      long var10001 = this.cfg.classSwitchMinutes;
      long wait = this.data(id).lastSwitch + var10001 * 60000L - System.currentTimeMillis();
      return Math.max(0L, wait);
   }

   public synchronized String togglePick(UUID id, String classId) {
      ClassDefs.ClassDef def = ClassDefs.get(classId);
      if (def == null) {
         return "Unknown class.";
      } else {
         ClassManager.PlayerData d = this.data(id);
         if (this.setupDone(id)) {
            return "You already chose your 4 classes.";
         } else {
            if (d.picks.contains(classId)) {
               d.picks.remove(classId);
            } else {
               if (this.picksIn(id, def.category()) >= 2) {
                  return "You already picked 2 " + def.category().label + " classes. Click one of them to un-pick it.";
               }

               d.picks.add(classId);
            }

            this.dirty = true;
            return null;
         }
      }
   }

   public synchronized String equip(UUID id, String classId) {
      ClassManager.PlayerData d = this.data(id);
      if (!d.picks.contains(classId)) {
         return "You did not choose that class.";
      } else if (classId.equals(d.active)) {
         return "That class is already equipped.";
      } else {
         long wait = this.switchWaitMillis(id);
         if (!d.active.isEmpty() && wait > 0L) {
            return "You can change class again in " + DailyShopMenu.duration(wait) + ".";
         } else {
            d.active = classId;
            d.lastSwitch = System.currentTimeMillis();
            this.dirty = true;
            return null;
         }
      }
   }

   public synchronized String replacePick(UUID id, String oldId, String newId) {
      ClassManager.PlayerData d = this.data(id);
      ClassDefs.ClassDef oldDef = ClassDefs.get(oldId);
      ClassDefs.ClassDef newDef = ClassDefs.get(newId);
      if (oldDef == null || newDef == null) {
         return "Unknown class.";
      } else if (!this.setupDone(id)) {
         return "Finish choosing your 4 classes first.";
      } else if (!d.picks.contains(oldId)) {
         return "You did not choose " + oldDef.name() + ".";
      } else if (d.picks.contains(newId)) {
         return "You already chose " + newDef.name() + ".";
      } else if (oldDef.category() != newDef.category()) {
         return "You can only swap within the same category (" + oldDef.category().label + ").";
      } else if (!this.eco.take(id, this.cfg.classChangeFee)) {
         return "You need " + this.eco.fmt(this.cfg.classChangeFee) + " to change a class.";
      } else {
         d.picks.remove(oldId);
         d.picks.add(newId);
         d.classes.remove(oldId);
         if (oldId.equals(d.active)) {
            d.active = "";
         }

         this.dirty = true;
         return null;
      }
   }

   /** Wipes a player's picks, active class, XP, skills and abilities. Returns false if they had nothing. */
   public synchronized boolean resetPlayer(UUID id) {
      ClassManager.PlayerData old = this.players.remove(id);
      if (old == null) {
         return false;
      } else {
         this.dirty = true;
         return true;
      }
   }

   public synchronized void addXp(ServerPlayer p, long amount) {
      if (amount > 0L) {
         UUID id = p.getUUID();
         String active = this.data(id).active;
         if (!active.isEmpty()) {
            ClassManager.ClassData c = this.classData(id, active);
            int before = levelOf(c.totalXp);
            c.totalXp += amount;
            this.dirty = true;
            int after = levelOf(c.totalXp);
            if (after > before) {
               ClassDefs.ClassDef def = ClassDefs.get(active);
               Msg.ok(p, (def == null ? active : def.name()) + " reached level " + after + "!");
            }
         }
      }
   }

   public synchronized String unlockProblem(UUID id, ClassDefs.ClassDef def, ClassDefs.Skill skill) {
      if (!skill.active()) {
         return "This skill is not available yet.";
      } else if (this.hasSkill(id, def.id(), skill.id())) {
         return "Already unlocked.";
      } else if (skill.parent() != null && !this.hasSkill(id, def.id(), skill.parent())) {
         ClassDefs.Skill parent = def.skill(skill.parent());
         return "Unlock " + (parent == null ? skill.parent() : parent.name()) + " first.";
      } else if (this.level(id, def.id()) < skill.minLevel()) {
         return "Needs " + def.name() + " level " + skill.minLevel() + ".";
      } else {
         return this.availableXp(id, def.id()) < skill.cost() ? "Needs " + skill.cost() + " Class XP (you have " + this.availableXp(id, def.id()) + ")." : null;
      }
   }

   public synchronized String unlock(UUID id, String classId, String skillId) {
      ClassDefs.ClassDef def = ClassDefs.get(classId);
      if (def == null) {
         return "Unknown class.";
      } else {
         ClassDefs.Skill skill = def.skill(skillId);
         if (skill == null) {
            return "Unknown skill.";
         } else if (!classId.equals(this.data(id).active)) {
            return "Equip " + def.name() + " first.";
         } else {
            String problem = this.unlockProblem(id, def, skill);
            if (problem != null) {
               return problem;
            } else {
               ClassManager.ClassData c = this.classData(id, classId);
               c.spentXp = c.spentXp + skill.cost();
               c.skills.add(skillId);
               this.dirty = true;
               return null;
            }
         }
      }
   }

   public synchronized int perkTotal(UUID id, String perk) {
      String active = this.data(id).active;
      ClassDefs.ClassDef def = ClassDefs.get(active);
      if (def == null) {
         return 0;
      } else {
         int total = 0;

         for (ClassDefs.Skill s : def.skills()) {
            if (perk.equals(s.perk()) && this.hasSkill(id, active, s.id())) {
               total += s.value();
            }
         }

         return total;
      }
   }

   public synchronized int sellBonusPercent(UUID id, String itemId) {
      ClassDefs.ClassDef def = ClassDefs.get(this.data(id).active);
      return def != null && def.sellItems().contains(itemId) ? this.perkTotal(id, "sell") : 0;
   }

   public synchronized List<ClassDefs.Skill> unlockedAbilities(UUID id) {
      List<ClassDefs.Skill> out = new ArrayList<>();
      String active = this.data(id).active;
      ClassDefs.ClassDef def = ClassDefs.get(active);
      if (def == null) {
         return out;
      } else {
         for (ClassDefs.Skill s : def.skills()) {
            if ("ability".equals(s.perk()) && this.hasSkill(id, active, s.id())) {
               out.add(s);
            }
         }

         return out;
      }
   }

   public synchronized boolean abilityUsable(UUID id, String name) {
      for (ClassDefs.Skill s : this.unlockedAbilities(id)) {
         if (s.id().equals(name)) {
            return this.data(id).abilities.contains(name);
         }
      }

      return false;
   }

   public synchronized boolean abilitySwitchedOn(UUID id, String name) {
      return this.data(id).abilities.contains(name);
   }

   public synchronized String setAbility(UUID id, String name, boolean on) {
      String active = this.data(id).active;
      ClassDefs.ClassDef def = ClassDefs.get(active);
      if (def == null) {
         return "Equip a class first with /class.";
      } else {
         ClassDefs.Skill s = def.skill(name);
         if (s == null || !"ability".equals(s.perk())) {
            return def.name() + " has no ability called '" + name + "'. Type /ability to see yours.";
         } else if (!this.hasSkill(id, active, name)) {
            return "You have not unlocked " + s.name() + " yet.";
         } else {
            List<String> list = this.data(id).abilities;
            list.remove(name);
            if (on) {
               list.add(name);
            }

            this.dirty = true;
            return null;
         }
      }
   }

   public static String oreFamily(String blockId) {
      if (mineXp(blockId) <= 0) {
         return null;
      } else {
         String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(58) + 1) : blockId;
         return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
      }
   }

   public long sellValue(UUID id, ItemStack stack, long unitPrice) {
      long base = unitPrice * stack.getCount();
      if (DailyShop.isShopItem(stack)) {
         return base;
      } else {
         String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
         int bonus = this.sellBonusPercent(id, key);
         return bonus <= 0 ? base : base + base * bonus / 100L;
      }
   }

   public static int mineXp(String blockId) {
      String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(58) + 1) : blockId;
      if (path.startsWith("deepslate_")) {
         path = path.substring("deepslate_".length());
      }
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

   public static final class ClassData {
      public long totalXp = 0L;
      public long spentXp = 0L;
      public List<String> skills = new ArrayList<>();

      public ClassData() {
      }
   }

   public static final class PlayerData {
      public List<String> picks = new ArrayList<>();
      public String active = "";
      public long lastSwitch = 0L;
      public Map<String, ClassManager.ClassData> classes = new HashMap<>();
      public List<String> abilities = new ArrayList<>();

      public PlayerData() {
      }
   }
}
