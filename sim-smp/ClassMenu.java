package com.simsmp;

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /class window. The top row shows all 8 classes (economy on the left, combat on the right) with
 * your class info in the middle. The 5 rows below show the skill tree of the class you have equipped.
 *
 * Before the 4 classes are chosen, clicking a class picks or un-picks it. Afterwards clicking one of your
 * classes twice equips it, and clicking a skill twice unlocks it. Every click is handled here so
 * nothing can be moved in or out of the window.
 */
public final class ClassMenu extends ChestMenu {
    private static final int SLOT_INFO = 4;
    private static final int[] ECONOMY_SLOTS = {0, 1, 2, 3};
    private static final int[] COMBAT_SLOTS = {5, 6, 7, 8};

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

    public static void open(ServerPlayer player, ClassManager classes, SimConfig cfg) {
        SimpleContainer view = new SimpleContainer(54);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            ClassMenu menu = new ClassMenu(id, inv, view, player, classes, cfg);
            menu.refresh();
            return menu;
        }, Component.literal("Classes")));
    }

    // ------------------------------------------------------------------ drawing

    private void refresh() {
        for (int s = 0; s < 54; s++) {
            view.setItem(s, ItemStack.EMPTY);
            slotClass[s] = null;
            slotSkill[s] = null;
        }
        UUID id = viewer.getUUID();
        boolean setup = classes.setupDone(id);
        String active = classes.active(id);

        int economyIndex = 0;
        int combatIndex = 0;
        for (ClassDefs.ClassDef def : ClassDefs.all()) {
            int slot = def.category() == ClassDefs.Category.ECONOMY ? ECONOMY_SLOTS[economyIndex++] : COMBAT_SLOTS[combatIndex++];
            slotClass[slot] = def.id();
            view.setItem(slot, classIcon(id, def, setup, active));
        }

        view.setItem(SLOT_INFO, infoIcon(id, setup, active));

        if (setup && !active.isEmpty()) {
            drawTree(id, ClassDefs.get(active));
        } else if (!setup) {
            view.setItem(31, Ui.named(Items.PAPER, "Pick your classes", ChatFormatting.YELLOW,
                    "Choose 2 Economy and 2 Combat classes in the top row.",
                    "Click a class to pick it, click again to un-pick it."));
        } else {
            view.setItem(31, Ui.named(Items.PAPER, "No class equipped", ChatFormatting.YELLOW,
                    "Click one of your classes twice to equip it.",
                    "Its skill tree will show up here."));
        }
    }

    private ItemStack classIcon(UUID id, ClassDefs.ClassDef def, boolean setup, String active) {
        boolean picked = classes.isPicked(id, def.id());
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
            lore.add("Costs " + fmtMoney(cfg.classChangeFee) + " and resets that class");
        } else {
            lore.add("Level " + classes.level(id, def.id()) + "  |  " + classes.totalXp(id, def.id()) + " total Class XP");
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
            return Ui.named(Items.NETHER_STAR, "Choose your classes", ChatFormatting.LIGHT_PURPLE,
                    "Economy: " + classes.picksIn(id, ClassDefs.Category.ECONOMY) + "/" + ClassManager.PICKS_PER_CATEGORY,
                    "Combat: " + classes.picksIn(id, ClassDefs.Category.COMBAT) + "/" + ClassManager.PICKS_PER_CATEGORY);
        }
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) {
            return Ui.named(Items.NETHER_STAR, "No class equipped", ChatFormatting.LIGHT_PURPLE,
                    "Click one of your classes twice to equip it.");
        }
        int level = classes.level(id, active);
        long total = classes.totalXp(id, active);
        long nextAt = 60L * level * level;
        long wait = classes.switchWaitMillis(id);
        return Ui.named(Items.NETHER_STAR, def.name() + " - level " + level, ChatFormatting.LIGHT_PURPLE,
                "Class XP to spend: " + classes.availableXp(id, active),
                "Total earned: " + total + "  (next level at " + nextAt + ")",
                wait > 0 ? "You can change class in " + DailyShopMenu.duration(wait) : "You can change class now");
    }

    private void drawTree(UUID id, ClassDefs.ClassDef def) {
        if (def.skills().isEmpty()) {
            view.setItem(31, Ui.named(Items.PAPER, def.name() + " skill tree", ChatFormatting.YELLOW,
                    "The skills of this class are coming soon."));
            return;
        }
        for (ClassDefs.Skill skill : def.skills()) {
            int slot = 9 + skill.row() * 9 + skill.col();
            if (slot < 9 || slot > 53) continue;
            slotSkill[slot] = skill.id();
            view.setItem(slot, skillIcon(id, def, skill));
        }
    }

    private ItemStack skillIcon(UUID id, ClassDefs.ClassDef def, ClassDefs.Skill skill) {
        boolean unlocked = classes.hasSkill(id, def.id(), skill.id());
        String problem = classes.unlockProblem(id, def, skill);
        boolean parentOk = skill.parent() == null || classes.hasSkill(id, def.id(), skill.parent());

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
        return cfg.currencySymbol + String.format(java.util.Locale.US, "%,d", amount);
    }

    // ------------------------------------------------------------------ clicking

    @Override
    public void clicked(int slotId, int button, ContainerInput clickType, Player player) {
        // Only plain clicks inside the window. Everything else is ignored.
        if (clickType != ContainerInput.PICKUP || slotId < 0 || slotId >= 54) return;
        if (slotClass[slotId] != null) {
            clickClass(slotClass[slotId]);
        } else if (slotSkill[slotId] != null) {
            clickSkill(slotSkill[slotId]);
        }
    }

    private void clickClass(String classId) {
        UUID id = viewer.getUUID();
        ClassDefs.ClassDef def = ClassDefs.get(classId);
        if (def == null) return;

        if (!classes.setupDone(id)) {
            String error = classes.togglePick(id, classId);
            if (error != null) {
                Msg.err(viewer, error);
            } else if (classes.setupDone(id)) {
                Msg.ok(viewer, "All 4 classes chosen! Click one of them twice to equip it.");
            }
            pending = null;
            refresh();
            return;
        }
        if (!classes.isPicked(id, classId)) {
            Msg.info(viewer, "You did not choose " + def.name() + ". To swap one of your classes use /class replace <old> <new> ("
                    + fmtMoney(cfg.classChangeFee) + ", the old class goes back to level 0).");
            return;
        }
        if (classId.equals(classes.active(id))) {
            Msg.info(viewer, def.name() + " is already equipped.");
            return;
        }
        String key = "class:" + classId;
        if (!key.equals(pending)) {
            pending = key;
            Msg.info(viewer, "Click again to equip " + def.name() + ".");
            return;
        }
        pending = null;
        String error = classes.equip(id, classId);
        if (error != null) {
            Msg.err(viewer, error);
        } else {
            Msg.ok(viewer, "You are now a " + def.name() + ".");
        }
        refresh();
    }

    private void clickSkill(String skillId) {
        UUID id = viewer.getUUID();
        String active = classes.active(id);
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) return;
        ClassDefs.Skill skill = def.skill(skillId);
        if (skill == null) return;

        String problem = classes.unlockProblem(id, def, skill);
        if (problem != null) {
            Msg.err(viewer, skill.name() + ": " + problem);
            pending = null;
            return;
        }
        String key = "skill:" + skillId;
        if (!key.equals(pending)) {
            pending = key;
            Msg.info(viewer, "Click again to unlock " + skill.name() + " for " + skill.cost() + " Class XP.");
            return;
        }
        pending = null;
        String error = classes.unlock(id, active, skillId);
        if (error != null) {
            Msg.err(viewer, error);
        } else {
            Msg.ok(viewer, "Unlocked " + skill.name() + "!");
        }
        refresh();
    }
}
