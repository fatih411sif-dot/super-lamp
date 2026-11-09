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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Type MAP_TYPE = (new TypeToken<Map<String, Long>>() {}).getType();
   private static final Type NAME_TYPE = (new TypeToken<Map<String, String>>() {}).getType();
   private final Path file;
   private final Path namesFile;
   private final SimConfig cfg;
   private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
   private final Map<UUID, String> names = new ConcurrentHashMap<>();
   private volatile boolean dirty;
   private volatile boolean namesDirty;

   public EconomyManager(Path file, SimConfig cfg) {
      this.file = file;
      this.namesFile = file.resolveSibling("names.json");
      this.cfg = cfg;
   }

   public void load() {
      if (Files.exists(this.file)) {
         try (Reader r = Files.newBufferedReader(this.file)) {
            Map<String, Long> raw = (Map<String, Long>)GSON.fromJson(r, MAP_TYPE);
            if (raw != null) {
               raw.forEach((k, v) -> {
                  try {
                     this.balances.put(UUID.fromString(k), v);
                  } catch (IllegalArgumentException var4) {
                  }
               });
            }
         } catch (RuntimeException | IOException var10) {
            SimSmp.LOGGER.error("balances.json is unreadable; keeping a backup and starting fresh", var10);

            try {
               Files.move(this.file, this.file.resolveSibling("balances.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException var5) {
            }
         }
      }

      if (Files.exists(this.namesFile)) {
         try (Reader rx = Files.newBufferedReader(this.namesFile)) {
            Map<String, String> raw = (Map<String, String>)GSON.fromJson(rx, NAME_TYPE);
            if (raw != null) {
               raw.forEach((k, v) -> {
                  try {
                     this.names.put(UUID.fromString(k), v);
                  } catch (IllegalArgumentException var4) {
                  }
               });
            }
         } catch (RuntimeException | IOException var8) {
            SimSmp.LOGGER.warn("names.json is unreadable; names will be learned again as players join", var8);
         }
      }
   }

   public synchronized void save() {
      if (this.dirty) {
         this.dirty = false;
         Map<String, Long> raw = new TreeMap<>();
         this.balances.forEach((k, v) -> raw.put(k.toString(), v));
         if (!writeAtomic(this.file, GSON.toJson(raw))) {
            this.dirty = true;
         }
      }

      if (this.namesDirty) {
         this.namesDirty = false;
         Map<String, String> raw = new TreeMap<>();
         this.names.forEach((k, v) -> raw.put(k.toString(), v));
         if (!writeAtomic(this.namesFile, GSON.toJson(raw))) {
            this.namesDirty = true;
         }
      }
   }

   private static boolean writeAtomic(Path target, String json) {
      try {
         Files.createDirectories(target.getParent());
         Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
         Files.writeString(tmp, json);

         try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var4) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
         }

         return true;
      } catch (IOException var5) {
         SimSmp.LOGGER.error("Could not save {}", target.getFileName(), var5);
         return false;
      }
   }

   public void ensure(UUID id) {
      this.balances.computeIfAbsent(id, k -> {
         this.dirty = true;
         return this.cfg.startingBalance;
      });
   }

   public void rememberName(UUID id, String name) {
      String old = this.names.put(id, name);
      if (!name.equals(old)) {
         this.namesDirty = true;
      }
   }

   public String nameOf(UUID id) {
      return this.names.getOrDefault(id, "Unknown");
   }

   public List<Entry<UUID, Long>> top(int n) {
      List<Entry<UUID, Long>> all = new ArrayList<>();
      this.balances.forEach((k, v) -> all.add(new SimpleEntry<>(k, v)));
      all.sort(Comparator.comparingLong(Entry::getValue).reversed().thenComparing(e -> e.getKey().toString()));
      return (List<Entry<UUID, Long>>)(all.size() > n ? new ArrayList<>(all.subList(0, n)) : all);
   }

   public int rankOf(UUID id) {
      long mine = this.get(id);
      int rank = 1;

      for (Entry<UUID, Long> e : this.balances.entrySet()) {
         if (e.getValue() > mine) {
            rank++;
         }
      }

      return rank;
   }

   public long get(UUID id) {
      return this.balances.getOrDefault(id, 0L);
   }

   public boolean has(UUID id, long amount) {
      return this.get(id) >= amount;
   }

   public void add(UUID id, long amount) {
      this.balances.merge(id, amount, (a, b) -> {
         long s = a + b;
         if (((a ^ s) & (b ^ s)) < 0L) {
            s = Long.MAX_VALUE;
         }

         return s;
      });
      this.dirty = true;
   }

   public boolean take(UUID id, long amount) {
      if (amount >= 0L && this.get(id) >= amount) {
         this.balances.merge(id, -amount, Long::sum);
         this.dirty = true;
         return true;
      } else {
         return false;
      }
   }

   public String fmt(long amount) {
      return this.cfg.currencySymbol + String.format(Locale.US, "%,d", amount);
   }
}
