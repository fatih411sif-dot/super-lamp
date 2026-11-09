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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class RedeemManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
   private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,32}");
   private static final int MAX_FAILS = 5;
   private static final long WINDOW_MS = 60000L;
   private final EconomyManager eco;
   private final Path codesFile;
   private final Path stateFile;
   private final Map<String, RedeemManager.Code> codes = new LinkedHashMap<>();
   private final Map<String, Set<UUID>> used = new HashMap<>();
   private final Map<UUID, long[]> failures = new HashMap<>();

   public RedeemManager(EconomyManager eco, Path dir) {
      this.eco = eco;
      this.codesFile = dir.resolve("redeem.json");
      this.stateFile = dir.resolve("redeem_state.json");
   }

   public synchronized void load() {
      this.codes.clear();
      this.used.clear();

      try {
         if (Files.exists(this.codesFile)) {
            try (Reader r = Files.newBufferedReader(this.codesFile)) {
               Type t = (new TypeToken<ArrayList<RedeemManager.Code>>() {
                  {
                     Objects.requireNonNull(RedeemManager.this);
                  }
               }).getType();
               List<RedeemManager.Code> read = (List<RedeemManager.Code>)GSON.fromJson(r, t);
               if (read != null) {
                  for (RedeemManager.Code c : read) {
                     if (c != null && c.code != null && !c.code.isBlank()) {
                        if (c.items == null) {
                           c.items = new ArrayList<>();
                        }

                        if (c.expires == null) {
                           c.expires = "";
                        }

                        c.code = key(c.code);
                        this.codes.put(c.code, c);
                        this.validate(c);
                     }
                  }
               }
            }
         } else {
            RedeemManager.Code example = new RedeemManager.Code();
            example.code = "EXAMPLE";
            example.enabled = false;
            example.money = 500L;
            RedeemManager.Reward rx = new RedeemManager.Reward();
            rx.item = "minecraft:diamond";
            rx.amount = 2;
            example.items.add(rx);
            example.maxUses = 100;
            example.expires = "2030-01-01 00:00";
            this.codes.put(example.code, example);
            this.saveCodes();
         }
      } catch (RuntimeException | IOException var12) {
         SimSmp.LOGGER.error("Could not read redeem.json - no codes are active", var12);
         this.codes.clear();
      }

      if (Files.exists(this.stateFile)) {
         try (Reader rx = Files.newBufferedReader(this.stateFile)) {
            Type t = (new TypeToken<HashMap<String, List<String>>>() {
               {
                  Objects.requireNonNull(RedeemManager.this);
               }
            }).getType();
            Map<String, List<String>> raw = (Map<String, List<String>>)GSON.fromJson(rx, t);
            if (raw != null) {
               raw.forEach((k, list) -> {
                  Set<UUID> set = new HashSet<>();

                  for (String s : list) {
                     try {
                        set.add(UUID.fromString(s));
                     } catch (IllegalArgumentException var7) {
                     }
                  }

                  this.used.put(k.toUpperCase(Locale.ROOT), set);
               });
            }
         } catch (RuntimeException | IOException var10) {
            SimSmp.LOGGER.error("redeem_state.json is unreadable - keeping a copy and starting fresh", var10);

            try {
               Files.move(this.stateFile, this.stateFile.resolveSibling("redeem_state.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException var6) {
            }
         }
      }

      SimSmp.LOGGER.info("Redeem codes loaded: {}", this.codes.size());
   }

   private void validate(RedeemManager.Code c) {
      for (RedeemManager.Reward r : c.items) {
         if (r == null || ItemIds.find(r.item) == null) {
            SimSmp.LOGGER.warn("redeem.json: code {} has an unknown item '{}' (it will be skipped)", c.code, r == null ? null : r.item);
         }
      }

      if (expiresAt(c) == -1L) {
         SimSmp.LOGGER.warn("redeem.json: code {} has an invalid \"expires\" ('{}'); use yyyy-MM-dd HH:mm. The code counts as expired.", c.code, c.expires);
      }
   }

   private synchronized void saveCodes() {
      writeAtomic(this.codesFile, GSON.toJson(new ArrayList<>(this.codes.values())));
   }

   private synchronized void saveState() {
      Map<String, List<String>> raw = new HashMap<>();
      this.used.forEach((k, set) -> {
         List<String> list = new ArrayList<>();

         for (UUID id : set) {
            list.add(id.toString());
         }

         raw.put(k, list);
      });
      writeAtomic(this.stateFile, GSON.toJson(raw));
   }

   private static void writeAtomic(Path target, String json) {
      try {
         Files.createDirectories(target.getParent());
         Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
         Files.writeString(tmp, json);

         try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var4) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var5) {
         SimSmp.LOGGER.error("Could not save {}", target.getFileName(), var5);
      }
   }

   private static String key(String raw) {
      return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
   }

   private static long expiresAt(RedeemManager.Code c) {
      if (c.expires != null && !c.expires.isBlank()) {
         try {
            return LocalDateTime.parse(c.expires.trim(), TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
         } catch (DateTimeParseException var2) {
            return -1L;
         }
      } else {
         return 0L;
      }
   }

   private static boolean isExpired(RedeemManager.Code c, long now) {
      long exp = expiresAt(c);
      return exp == -1L || exp > 0L && now >= exp;
   }

   private int usesOf(String key) {
      Set<UUID> set = this.used.get(key);
      return set == null ? 0 : set.size();
   }

   public synchronized void redeem(ServerPlayer p, String raw) {
      UUID id = p.getUUID();
      long now = System.currentTimeMillis();
      long[] f = this.failures.get(id);
      if (f != null && f[0] >= 5L && now - f[1] < 60000L) {
         Msg.err(p, "Too many wrong codes. Try again in " + (60000L - (now - f[1]) + 999L) / 1000L + "s.");
      } else {
         String key = key(raw);
         RedeemManager.Code c = this.codes.get(key);
         if (c != null && c.enabled) {
            if (isExpired(c, now)) {
               Msg.err(p, "This code has expired.");
            } else {
               Set<UUID> set = this.used.computeIfAbsent(key, k -> new HashSet<>());
               if (set.contains(id)) {
                  Msg.err(p, "You already used this code.");
               } else if (c.maxUses > 0 && set.size() >= c.maxUses) {
                  Msg.err(p, "This code has reached its use limit.");
               } else {
                  set.add(id);
                  this.failures.remove(id);
                  this.saveState();
                  List<String> parts = new ArrayList<>();
                  if (c.money > 0L) {
                     this.eco.add(id, c.money);
                     parts.add(this.eco.fmt(c.money));
                  }

                  for (RedeemManager.Reward r : c.items) {
                     if (r != null) {
                        Item item = ItemIds.find(r.item);
                        if (item != null) {
                           int total = Math.max(1, r.amount);
                           int max = new ItemStack(item).getMaxStackSize();
                           int left = total;

                           while (left > 0) {
                              int n = Math.min(left, max);
                              ItemGiver.give(p, new ItemStack(item, n));
                              left -= n;
                           }

                           parts.add(total + "x " + Msg.pretty(r.item));
                        }
                     }
                  }

                  SimSmp.LOGGER.info("{} redeemed code {}", Msg.name(p), key);
                  Msg.ok(p, "Code " + key + " redeemed! You received: " + (parts.isEmpty() ? "nothing" : String.join(", ", parts)) + ".");
               }
            }
         } else {
            if (f != null && now - f[1] < 60000L) {
               f[0]++;
            } else {
               this.failures.put(id, new long[]{1L, now});
            }

            Msg.err(p, "That code is not valid.");
         }
      }
   }

   public synchronized List<String> codeNames() {
      return new ArrayList<>(this.codes.keySet());
   }

   public synchronized String create(String raw, long money) {
      if (!NAME.matcher(raw.trim()).matches()) {
         return "A code must be 3-32 characters: letters, numbers, _ or -.";
      } else {
         String key = key(raw);
         if (this.codes.containsKey(key)) {
            return "Code " + key + " already exists.";
         } else {
            RedeemManager.Code c = new RedeemManager.Code();
            c.code = key;
            c.money = Math.max(0L, money);
            this.codes.put(key, c);
            this.saveCodes();
            return null;
         }
      }
   }

   public synchronized String delete(String raw) {
      String key = key(raw);
      if (this.codes.remove(key) == null) {
         return "No such code: " + key;
      } else {
         this.used.remove(key);
         this.saveCodes();
         this.saveState();
         return null;
      }
   }

   public synchronized String setMoney(String raw, long money) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else {
         c.money = Math.max(0L, money);
         this.saveCodes();
         return null;
      }
   }

   public synchronized String setMaxUses(String raw, int maxUses) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else {
         c.maxUses = Math.max(0, maxUses);
         this.saveCodes();
         return null;
      }
   }

   public synchronized String setExpireHours(String raw, int hours) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else {
         c.expires = hours <= 0 ? "" : LocalDateTime.now(ZoneOffset.UTC).plusHours(hours).format(TIME);
         this.saveCodes();
         return null;
      }
   }

   public synchronized String setEnabled(String raw, boolean enabled) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else {
         c.enabled = enabled;
         this.saveCodes();
         return null;
      }
   }

   public synchronized String addItem(String raw, String itemId, int amount) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else if (ItemIds.find(itemId) == null) {
         return "Unknown item: " + itemId;
      } else {
         String id = ItemIds.normalize(itemId);

         for (RedeemManager.Reward r : c.items) {
            if (r != null && id.equals(ItemIds.normalize(r.item))) {
               r.amount = r.amount + Math.max(1, amount);
               this.saveCodes();
               return null;
            }
         }

         RedeemManager.Reward rx = new RedeemManager.Reward();
         rx.item = id;
         rx.amount = Math.max(1, amount);
         c.items.add(rx);
         this.saveCodes();
         return null;
      }
   }

   public synchronized String clearItems(String raw) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return "No such code: " + key(raw);
      } else {
         c.items.clear();
         this.saveCodes();
         return null;
      }
   }

   public synchronized List<String> list() {
      List<String> out = new ArrayList<>();
      long now = System.currentTimeMillis();

      for (RedeemManager.Code c : this.codes.values()) {
         String status = !c.enabled ? "OFF" : (isExpired(c, now) ? "EXPIRED" : "on");
         out.add(
            c.code
               + "  ["
               + status
               + "]  "
               + this.eco.fmt(c.money)
               + " + "
               + c.items.size()
               + " item(s)  used "
               + this.usesOf(c.code)
               + (c.maxUses > 0 ? "/" + c.maxUses : "")
         );
      }

      return out;
   }

   public synchronized List<String> info(String raw) {
      RedeemManager.Code c = this.codes.get(key(raw));
      if (c == null) {
         return null;
      } else {
         long now = System.currentTimeMillis();
         List<String> out = new ArrayList<>();
         out.add("Code: " + c.code + (c.enabled ? " (enabled)" : " (DISABLED)"));
         out.add("Money: " + this.eco.fmt(c.money));
         List<String> items = new ArrayList<>();

         for (RedeemManager.Reward r : c.items) {
            if (r != null) {
               items.add(r.amount + "x " + Msg.pretty(r.item));
            }
         }

         out.add("Items: " + (items.isEmpty() ? "none" : String.join(", ", items)));
         out.add("Used: " + this.usesOf(c.code) + (c.maxUses > 0 ? " of " + c.maxUses : " (no limit)"));
         out.add("Expires: " + (c.expires != null && !c.expires.isBlank() ? c.expires + " UTC" : "never") + (isExpired(c, now) ? "  -> EXPIRED" : ""));
         return out;
      }
   }

   public static final class Code {
      public String code;
      public boolean enabled = true;
      public long money = 0L;
      public List<RedeemManager.Reward> items = new ArrayList<>();
      public int maxUses = 0;
      public String expires = "";

      public Code() {
      }
   }

   public static final class Reward {
      public String item;
      public int amount = 1;

      public Reward() {
      }
   }
}
