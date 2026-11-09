package com.simsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ClassMenu extends ChestMenu {
   private static final int SLOT_TREE = 7;
   private static final int SLOT_INFO = 8;
   private static final int SLOT_BACK = 0;
   /** false = the class selection screen ("MAIN CLASSES"), true = the skill tree of the equipped class */
   private boolean tree;
   private final SimpleContainer view;
   private final ServerPlayer viewer;
   private final ClassManager classes;
   private final SimConfig cfg;
   private final String[] slotClass = new String[54];
   private final String[] slotSkill = new String[54];
   private String pending;

   private ClassMenu(int id, Inventory inv, SimpleContainer view, ServerPlayer viewer, ClassManager classes, SimConfig cfg) {
      super(MenuType.GENERIC_9x6, id, inv, view, 6);
      this.view = view;
      this.viewer = viewer;
      this.classes = classes;
      this.cfg = cfg;
   }

   /**
    * With the SIM SMP skill-tree resource pack the title is a string of font glyphs that draws the tree background:
    * U+E001 moves the cursor to the left edge, U+E100+classIndex is the class background, and for every unlocked skill
    * U+E002 steps back and U+E200+classIndex*32+skillIndex draws that skill's gold frame and gold line.
    * (make_pack.py generates the textures with the same numbering.)
    */
   /** Bedrock players joining through Geyser/Floodgate have a UUID whose upper 64 bits are 0. They can't use the Java resource pack. */
   static boolean isBedrock(ServerPlayer p) {
      return p.getUUID().getMostSignificantBits() == 0L;
   }

   static Component titleFor(ServerPlayer p, ClassManager classes, boolean tree) {
      if (isBedrock(p)) {
         return Component.literal("Classes");
      }

      UUID id = p.getUUID();
      boolean setup = classes.setupDone(id);
      String active = classes.active(id);
      if (!tree) {
         // selection screen: U+E0F0 = panels, then one state overlay per class (U+E400 picked, U+E410 equipped, U+E420 dim)
         StringBuilder sb = new StringBuilder();
         sb.append('\ue001').append('\ue0f0');
         List<ClassDefs.ClassDef> all = ClassDefs.all();

         for (int ci = 0; ci < all.size(); ci++) {
            String cid = all.get(ci).id();
            boolean picked = classes.isPicked(id, cid);
            int glyph;
            if (setup && cid.equals(active)) {
               glyph = 0xE410 + ci;
            } else if (picked) {
               glyph = 0xE400 + ci;
            } else if (setup) {
               glyph = 0xE420 + ci;
            } else {
               continue;
            }

            sb.append('\ue002').append((char)glyph);
         }

         return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE);
      }

      ClassDefs.ClassDef def = setup ? ClassDefs.get(active) : null;
      if (def == null || def.skills().isEmpty()) {
         return Component.literal("Classes");
      } else {
         int ci = ClassDefs.all().indexOf(def);
         StringBuilder sb = new StringBuilder();
         sb.append('\ue001').append((char)(0xE100 + ci));
         List<ClassDefs.Skill> skills = def.skills();

         for (int i = 0; i < skills.size(); i++) {
            if (classes.hasSkill(id, def.id(), skills.get(i).id())) {
               sb.append('\ue002').append((char)(0xE200 + ci * 32 + i));
            }
         }

         return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE);
      }
   }

   public static void open(ServerPlayer player, ClassManager classes, SimConfig cfg) {
      open(player, classes, cfg, false);
   }

   public static void open(ServerPlayer player, ClassManager classes, SimConfig cfg, boolean tree) {
      SimpleContainer view = new SimpleContainer(54);
      player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
         ClassMenu menu = new ClassMenu(id, inv, view, player, classes, cfg);
         menu.tree = tree;
         menu.refresh();
         return menu;
      }, titleFor(player, classes, tree)));
   }

   /** Java: re-open so the title (the drawn background) updates. Bedrock: just refresh the items. */
   private void redraw() {
      if (isBedrock(this.viewer)) {
         this.refresh();
      } else {
         ClassMenu.open(this.viewer, this.classes, this.cfg, this.tree);
      }
   }

   private void switchMode(boolean toTree) {
      this.tree = toTree;
      this.pending = null;
      this.redraw();
   }

   private void refresh() {
      for (int s = 0; s < 54; s++) {
         this.view.setItem(s, ItemStack.EMPTY);
         this.slotClass[s] = null;
         this.slotSkill[s] = null;
      }

      UUID id = this.viewer.getUUID();
      boolean setup = this.classes.setupDone(id);
      String active = this.classes.active(id);
      ClassDefs.ClassDef activeDef = setup ? ClassDefs.get(active) : null;
      if (this.tree && (activeDef == null || activeDef.skills().isEmpty())) {
         this.tree = false;
      }

      this.view.setItem(SLOT_INFO, this.infoIcon(id, setup, active));
      if (this.tree) {
         this.slotClass[SLOT_BACK] = "@back";
         this.view.setItem(SLOT_BACK, Ui.named(Items.ARROW, "Back to classes", ChatFormatting.YELLOW, "Return to the class selection."));
         this.drawTree(id, activeDef);
         return;
      }


      int economyIndex = 0;
      int combatIndex = 0;

      for (ClassDefs.ClassDef def : ClassDefs.all()) {
         // every class is a panel of 2x2 cells: economy in container rows 1-2, combat in rows 4-5; the icon is the top-left cell
         boolean economy = def.category() == ClassDefs.Category.ECONOMY;
         int col = (economy ? economyIndex++ : combatIndex++) * 2;
         int row = economy ? 1 : 4;
         for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
               this.slotClass[(row + r) * 9 + col + c] = def.id();
            }
         }

         this.view.setItem(row * 9 + col, this.classIcon(id, def, setup, active));
      }

      if (setup && activeDef != null && !activeDef.skills().isEmpty()) {
         this.slotClass[SLOT_TREE] = "@tree";
         this.view.setItem(SLOT_TREE, Ui.named(Items.BOOK, "Skill tree", ChatFormatting.AQUA, "Open the skill tree of " + activeDef.name() + "."));
      }
   }

   private boolean isBedrockView() {
      return isBedrock(this.viewer);
   }

   private ItemStack classIcon(UUID id, ClassDefs.ClassDef def, boolean setup, String active) {
      boolean picked = this.classes.isPicked(id, def.id());
      List<String> lore = new ArrayList<>();
      lore.add(def.category().label + " class");
      ChatFormatting color;
      if (!setup) {
         color = picked ? ChatFormatting.GREEN : ChatFormatting.GRAY;
         lore.add(picked ? "PICKED - click to un-pick" : "Click to pick");
      } else if (!picked) {
         color = ChatFormatting.DARK_GRAY;
         lore.add("Not one of your classes");
         lore.add("Swap with /class replace <old> <new>");
         lore.add("Costs " + this.fmtMoney(this.cfg.classChangeFee) + " and resets that class");
      } else {
         lore.add("Level " + this.classes.level(id, def.id()) + "  |  " + this.classes.totalXp(id, def.id()) + " total Class XP");
         if (def.id().equals(active)) {
            color = ChatFormatting.GREEN;
            lore.add("EQUIPPED");
         } else {
            color = ChatFormatting.YELLOW;
            lore.add("Click twice to equip");
         }
      }

      return Ui.named(def.icon(), def.name(), color, lore.toArray(new String[0]));
   }

   private ItemStack infoIcon(UUID id, boolean setup, String active) {
      if (!setup) {
         return Ui.named(
            Items.NETHER_STAR,
            "Choose your classes",
            ChatFormatting.LIGHT_PURPLE,
            "Economy: " + this.classes.picksIn(id, ClassDefs.Category.ECONOMY) + "/2",
            "Combat: " + this.classes.picksIn(id, ClassDefs.Category.COMBAT) + "/2"
         );
      } else {
         ClassDefs.ClassDef def = ClassDefs.get(active);
         if (def == null) {
            return Ui.named(Items.NETHER_STAR, "No class equipped", ChatFormatting.LIGHT_PURPLE, "Click one of your classes twice to equip it.");
         } else {
            int level = this.classes.level(id, active);
            long total = this.classes.totalXp(id, active);
            long nextAt = 60L * level * level;
            long wait = this.classes.switchWaitMillis(id);
            return Ui.named(
               Items.NETHER_STAR,
               def.name() + " - level " + level,
               ChatFormatting.LIGHT_PURPLE,
               "Class XP to spend: " + this.classes.availableXp(id, active),
               "Total earned: " + total + "  (next level at " + nextAt + ")",
               wait > 0L ? "You can change class in " + DailyShopMenu.duration(wait) : "You can change class now"
            );
         }
      }
   }

   private void drawTree(UUID id, ClassDefs.ClassDef def) {
      if (def.skills().isEmpty()) {
         this.view.setItem(31, Ui.named(Items.PAPER, def.name() + " skill tree", ChatFormatting.YELLOW, "The skills of this class are coming soon."));
      } else {

         for (ClassDefs.Skill skill : def.skills()) {
            int slot = 9 + skill.row() * 9 + skill.col();
            if (slot >= 9 && slot <= 53) {
               this.slotSkill[slot] = skill.id();
               this.view.setItem(slot, this.skillIcon(id, def, skill));
            }
         }
      }
   }

   private ItemStack skillIcon(UUID id, ClassDefs.ClassDef def, ClassDefs.Skill skill) {
      boolean unlocked = this.classes.hasSkill(id, def.id(), skill.id());
      String problem = this.classes.unlockProblem(id, def, skill);
      boolean parentOk = skill.parent() == null || this.classes.hasSkill(id, def.id(), skill.parent());
      List<String> lore = new ArrayList<>();
      lore.add(skill.desc());
      lore.add(" ");
      lore.add("Cost: " + skill.cost() + " Class XP  |  Needs level " + skill.minLevel());
      if (skill.parent() != null) {
         ClassDefs.Skill parent = def.skill(skill.parent());
         lore.add("Requires: " + (parent == null ? skill.parent() : parent.name()));
      }

      ChatFormatting color;
      if (unlocked) {
         color = ChatFormatting.GREEN;
         lore.add("UNLOCKED");
      } else if (!skill.active()) {
         color = ChatFormatting.DARK_GRAY;
         lore.add("Coming soon");
      } else if (!parentOk) {
         color = ChatFormatting.RED;
         lore.add("LOCKED - unlock the skill above first");
      } else if (problem == null) {
         color = ChatFormatting.YELLOW;
         lore.add("Click twice to unlock");
      } else {
         color = ChatFormatting.GOLD;
         lore.add(problem);
      }

      return Ui.named(skill.icon(), skill.name(), color, lore.toArray(new String[0]));
   }

   private String fmtMoney(long amount) {
      return this.cfg.currencySymbol + String.format(Locale.US, "%,d", amount);
   }

   public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
      String kind = clickType.name();
      boolean pick = kind.equals("PICKUP") || kind.equals("PICKUP_ALL");
      boolean handled = pick && slotId >= 0 && slotId < 54 && (this.slotClass[slotId] != null || this.slotSkill[slotId] != null);
      if (!handled && slotId >= 0 && (slotId >= 54 || !pick)) {
         // the client already moved an item in its own copy of the menu (inventory click, shift-click, number key, double click);
         // re-open so everything is put back
         this.redraw();
         return;
      }

      if (handled) {
         if (this.slotClass[slotId] != null) {
            this.clickClass(this.slotClass[slotId]);
         } else if (this.slotSkill[slotId] != null) {
            this.clickSkill(this.slotSkill[slotId]);
         }
      }
   }

   private void clickClass(String classId) {
      if (classId.equals("@tree")) {
         this.switchMode(true);
         return;
      } else if (classId.equals("@back")) {
         this.switchMode(false);
         return;
      }

      UUID id = this.viewer.getUUID();
      ClassDefs.ClassDef def = ClassDefs.get(classId);
      if (def != null) {
         if (!this.classes.setupDone(id)) {
            String error = this.classes.togglePick(id, classId);
            if (error != null) {
               Msg.err(this.viewer, error);
            } else if (this.classes.setupDone(id)) {
               Msg.ok(this.viewer, "All 4 classes chosen! Click one of them twice to equip it.");
            }

            this.pending = null;
            this.refresh();
         } else if (!this.classes.isPicked(id, classId)) {
            Msg.info(
               this.viewer,
               "You did not choose "
                  + def.name()
                  + ". To swap one of your classes use /class replace <old> <new> ("
                  + this.fmtMoney(this.cfg.classChangeFee)
                  + ", the old class goes back to level 0)."
            );
         } else if (classId.equals(this.classes.active(id))) {
            if (def.skills().isEmpty()) {
               Msg.info(this.viewer, def.name() + " is already equipped.");
            } else {
               this.switchMode(true);
            }
         } else {
            String key = "class:" + classId;
            if (!key.equals(this.pending)) {
               this.pending = key;
               Msg.info(this.viewer, "Click again to equip " + def.name() + ".");
            } else {
               this.pending = null;
               String error = this.classes.equip(id, classId);
               if (error != null) {
                  Msg.err(this.viewer, error);
               } else {
                  Msg.ok(this.viewer, "You are now a " + def.name() + ".");
                  RankManager.refresh(this.viewer);
                  if (!def.skills().isEmpty()) {
                     this.tree = true;
                  }
               }

               this.redraw();
            }
         }
      }
   }

   private void clickSkill(String skillId) {
      UUID id = this.viewer.getUUID();
      String active = this.classes.active(id);
      ClassDefs.ClassDef def = ClassDefs.get(active);
      if (def != null) {
         ClassDefs.Skill skill = def.skill(skillId);
         if (skill != null) {
            String problem = this.classes.unlockProblem(id, def, skill);
            if (problem != null) {
               Msg.err(this.viewer, skill.name() + ": " + problem);
               this.pending = null;
            } else {
               String key = "skill:" + skillId;
               if (!key.equals(this.pending)) {
                  this.pending = key;
                  Msg.info(this.viewer, "Click again to unlock " + skill.name() + " for " + skill.cost() + " Class XP.");
               } else {
                  this.pending = null;
                  String error = this.classes.unlock(id, active, skillId);
                  if (error != null) {
                     Msg.err(this.viewer, error);
                  } else {
                     Msg.ok(this.viewer, "Unlocked " + skill.name() + "!");
                  }

                  this.redraw();
               }
            }
         }
      }
   }
}
