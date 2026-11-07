package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

/** /class (GUI), /class replace, and /classadmin addxp and reset for OPs. */
final class ClassCommands {
    private ClassCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d, ClassManager classes, SimConfig cfg) {
        SuggestionProvider<CommandSourceStack> classIds = (ctx, builder) ->
                SharedSuggestionProvider.suggest(ClassDefs.all().stream().map(ClassDefs.ClassDef::id).toList(), builder);

        // /class                       -> opens the GUI
        // /class replace <old> <new>   -> swap one of your 4 chosen classes for a fee
        d.register(Commands.literal("class")
                .executes(ctx -> {
                    ClassMenu.open(ctx.getSource().getPlayerOrException(), classes, cfg);
                    return 1;
                })
                .then(Commands.literal("replace")
                        .then(Commands.argument("old", StringArgumentType.word()).suggests(classIds)
                                .then(Commands.argument("new", StringArgumentType.word()).suggests(classIds)
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            String oldId = StringArgumentType.getString(ctx, "old").toLowerCase();
                                            String newId = StringArgumentType.getString(ctx, "new").toLowerCase();
                                            String error = classes.replacePick(p.getUUID(), oldId, newId);
                                            if (error != null) {
                                                Msg.err(p, error);
                                                return 0;
                                            }
                                            Msg.ok(p, "Swapped " + oldId + " for " + newId + " (" + cfg.currencySymbol
                                                    + String.format(java.util.Locale.US, "%,d", cfg.classChangeFee) + " paid).");
                                            return 1;
                                        })))));

        // /ability                      -> lists your abilities and whether they are on
        // /ability <name> <true|false>  -> switches one on or off
        SuggestionProvider<CommandSourceStack> abilityNames = (ctx, builder) -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            return SharedSuggestionProvider.suggest(
                    classes.unlockedAbilities(p.getUUID()).stream().map(ClassDefs.Skill::id).toList(), builder);
        };
        SuggestionProvider<CommandSourceStack> mageEffects = (ctx, builder) -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            List<String> names = new java.util.ArrayList<>(
                    classes.unlockedMageEffects(p.getUUID()).stream().map(ClassDefs.Skill::id).toList());
            names.add("off");
            return SharedSuggestionProvider.suggest(names, builder);
        };
        d.register(Commands.literal("ability")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    List<ClassDefs.Skill> list = classes.unlockedAbilities(p.getUUID());
                    List<ClassDefs.Skill> effects = classes.unlockedMageEffects(p.getUUID());
                    boolean isMage = "mage".equals(classes.active(p.getUUID()));
                    if (list.isEmpty() && !(isMage && !effects.isEmpty())) {
                        Msg.info(p, "You have no abilities yet. Unlock them in the skill tree with /class.");
                        return 0;
                    }
                    if (isMage && !effects.isEmpty()) {
                        String current = classes.mageEffect(p.getUUID());
                        Msg.info(p, "Mage effect: " + (current.isEmpty() ? "off" : current)
                                + "  (choose with /ability mage <effect>, or /ability mage off)");
                        p.sendSystemMessage(Component.literal(" - unlocked: "
                                + String.join(", ", effects.stream().map(ClassDefs.Skill::id).toList()))
                                .withStyle(ChatFormatting.GRAY));
                    }
                    if (list.isEmpty()) return 1;
                    Msg.info(p, "Your abilities (switch with /ability <name> <true|false>):");
                    for (ClassDefs.Skill s : list) {
                        boolean on = classes.abilitySwitchedOn(p.getUUID(), s.id());
                        p.sendSystemMessage(Component.literal(" - " + s.id() + ": " + (on ? "ON" : "off") + "  (" + s.name() + ")")
                                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                    }
                    return 1;
                })
                .then(Commands.literal("mage")
                        .then(Commands.argument("effect", StringArgumentType.word()).suggests(mageEffects)
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "effect").toLowerCase();
                                    String error = classes.setMageEffect(p.getUUID(), name);
                                    if (error != null) {
                                        Msg.err(p, error);
                                        return 0;
                                    }
                                    Msg.ok(p, name.equals("off") ? "Your weapon no longer applies an effect."
                                            : "Your weapon hits now apply " + name + ".");
                                    return 1;
                                })))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(abilityNames)
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                                    boolean on = BoolArgumentType.getBool(ctx, "enabled");
                                    String error = classes.setAbility(p.getUUID(), name, on);
                                    if (error != null) {
                                        Msg.err(p, error);
                                        return 0;
                                    }
                                    Msg.ok(p, name + " is now " + (on ? "ON" : "off") + ".");
                                    return 1;
                                }))));

        // /classadmin addxp <player> <amount>   (OP: also useful for testing)
        // /classadmin reset <player>            (OP: the player picks their 4 classes again from the start)
        d.register(Commands.literal("classadmin")
                .requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    classes.resetPlayer(target.getUUID());
                                    ClassEffects.update(target, classes); // takes the perks, Night Vision and speed off right away
                                    Msg.info(target, "An admin reset your classes. Type /class to choose your 4 classes again.");
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Reset all class data of " + Msg.name(target) + ". They can pick their classes again."), true);
                                    return 1;
                                })))
                .then(Commands.literal("addxp")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            long amount = LongArgumentType.getLong(ctx, "amount");
                                            if (classes.active(target.getUUID()).isEmpty()) {
                                                ctx.getSource().sendFailure(Component.literal(Msg.name(target) + " has no class equipped."));
                                                return 0;
                                            }
                                            classes.addXp(target, amount);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Gave " + amount + " Class XP to " + Msg.name(target) + "."), true);
                                            return 1;
                                        })))));
    }

    /** Short text for the sidebar: "120 (Miner)" or "-" when nothing is equipped. */
    static String sidebarText(ClassManager classes, ServerPlayer p) {
        String active = classes.active(p.getUUID());
        ClassDefs.ClassDef def = ClassDefs.get(active);
        if (def == null) return "- (use /class)";
        return classes.availableXp(p.getUUID(), active) + " (" + def.name() + " Lv" + classes.level(p.getUUID(), active) + ")";
    }
}
