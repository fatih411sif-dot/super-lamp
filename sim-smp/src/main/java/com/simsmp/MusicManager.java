package com.simsmp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server background music. The track is a sound of the resource pack (sim_smp:kingdom_dance, see sounds.json in the pack).
 * Minecraft cannot loop a sound by itself, so it is started again every `seconds` seconds. Players can switch it off with /music off.
 * Later the track can depend on the biome: only soundFor() has to change.
 * Settings live in config/sim_smp/music.properties.
 */
public final class MusicManager {
   private final Path file;
   private final Path offFile;
   private boolean enabled = true;
   private String sound = "sim_smp:kingdom_dance";
   private int seconds = 157;
   private int delay = 5;
   private final Set<UUID> off = new HashSet<>();
   /** player -> epoch millis when the track is started again */
   private final Map<UUID, Long> nextAt = new HashMap<>();
   private final Map<UUID, String> playing = new HashMap<>();

   public MusicManager(Path dir) {
      this.file = dir.resolve("music.properties");
      this.offFile = dir.resolve("music_off.txt");
   }

   public void load() {
      try {
         Files.createDirectories(this.file.getParent());
         if (Files.exists(this.file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(this.file)) {
               p.load(r);
            }

            this.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            this.sound = p.getProperty("sound", this.sound).trim();
            this.seconds = Math.max(5, Integer.parseInt(p.getProperty("seconds", "157").trim()));
            this.delay = Math.max(0, Integer.parseInt(p.getProperty("delay", "5").trim()));
         } else {
            Properties p = new Properties();
            p.setProperty("enabled", "true");
            p.setProperty("sound", this.sound);
            p.setProperty("seconds", String.valueOf(this.seconds));
            p.setProperty("delay", String.valueOf(this.delay));
            try (Writer w = Files.newBufferedWriter(this.file)) {
               p.store(w, "SIM SMP music: sound = sound event of the resource pack, seconds = length of the song (it is restarted after that), delay = seconds after joining");
            }
         }

         if (Files.exists(this.offFile)) {
            for (String line : Files.readAllLines(this.offFile)) {
               try {
                  this.off.add(UUID.fromString(line.trim()));
               } catch (IllegalArgumentException ignored) {
               }
            }
         }
      } catch (IOException | RuntimeException e) {
         SimSmp.LOGGER.error("Could not read music.properties, using defaults", e);
      }
   }

   private void saveOff() {
      try {
         List<String> lines = this.off.stream().map(UUID::toString).toList();
         Files.write(this.offFile, lines);
      } catch (IOException e) {
         SimSmp.LOGGER.warn("Could not save music_off.txt", e);
      }
   }

   /** The track for a player right now. One song everywhere for now; per-biome songs would be chosen here. */
   private String soundFor(ServerPlayer p) {
      return this.sound;
   }

   private static void run(MinecraftServer server, String cmd) {
      server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
   }

   private void start(ServerPlayer p, String id) {
      MinecraftServer server = p.level().getServer();
      run(server, "stopsound " + p.getUUID() + " music " + id);
      run(server, "execute as " + p.getUUID() + " at @s run playsound " + id + " music @s ~ ~ ~ 1 1 1");
      this.playing.put(p.getUUID(), id);
   }

   private void stop(ServerPlayer p) {
      String id = this.playing.remove(p.getUUID());
      if (id != null) {
         run(p.level().getServer(), "stopsound " + p.getUUID() + " music " + id);
      }
   }

   public void onJoin(ServerPlayer p) {
      this.nextAt.put(p.getUUID(), System.currentTimeMillis() + this.delay * 1000L);
   }

   public void forget(ServerPlayer p) {
      this.nextAt.remove(p.getUUID());
      this.playing.remove(p.getUUID());
   }

   /** called once per second for every online player */
   public void update(ServerPlayer p) {
      if (!this.enabled || this.off.contains(p.getUUID()) || ClassMenu.isBedrock(p)) {
         return;
      }

      long now = System.currentTimeMillis();
      Long due = this.nextAt.get(p.getUUID());
      if (due == null) {
         this.nextAt.put(p.getUUID(), now + this.delay * 1000L);
      } else if (now >= due) {
         String id = this.soundFor(p);
         this.start(p, id);
         this.nextAt.put(p.getUUID(), now + this.seconds * 1000L);
      }
   }

   public void register(CommandDispatcher<CommandSourceStack> d) {
      LiteralArgumentBuilder<CommandSourceStack> music = Commands.literal("music");
      music.executes(ctx -> {
         ServerPlayer p = ctx.getSource().getPlayerOrException();
         Msg.info(p, "Music is " + (this.off.contains(p.getUUID()) ? "off" : "on") + ". Use /music on or /music off.");
         return 1;
      });
      music.then(
         Commands.argument("mode", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
            .executes(ctx -> {
               ServerPlayer p = ctx.getSource().getPlayerOrException();
               String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
               if (mode.equals("off")) {
                  this.off.add(p.getUUID());
                  this.stop(p);
                  this.saveOff();
                  Msg.ok(p, "Music is now off.");
                  return 1;
               } else if (mode.equals("on")) {
                  this.off.remove(p.getUUID());
                  this.nextAt.put(p.getUUID(), System.currentTimeMillis());
                  this.saveOff();
                  Msg.ok(p, "Music is now on.");
                  return 1;
               } else {
                  Msg.err(p, "Use /music on or /music off.");
                  return 0;
               }
            })
      );
      d.register(music);
   }
}
