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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores each player's rupee balance in balances.json. */
public final class EconomyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final SimConfig cfg;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public EconomyManager(Path file, SimConfig cfg) {
        this.file = file;
        this.cfg = cfg;
    }

    public void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Type t = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> raw = GSON.fromJson(r, t);
            if (raw == null) return;
            raw.forEach((k, v) -> {
                try {
                    balances.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException ignored) {
                }
            });
        } catch (IOException | RuntimeException e) {
            SimSmp.LOGGER.error("balances.json is unreadable; keeping a backup and starting fresh", e);
            try {
                Files.move(file, file.resolveSibling("balances.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        dirty = false;
        Map<String, Long> raw = new TreeMap<>();
        balances.forEach((k, v) -> raw.put(k.toString(), v));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("balances.json.tmp");
            Files.writeString(tmp, GSON.toJson(raw));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty = true;
            SimSmp.LOGGER.error("Could not save balances.json", e);
        }
    }

    /** Gives new players the starting balance. */
    public void ensure(UUID id) {
        balances.computeIfAbsent(id, k -> {
            dirty = true;
            return cfg.startingBalance;
        });
    }

    public long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public boolean has(UUID id, long amount) {
        return get(id) >= amount;
    }

    public void add(UUID id, long amount) {
        balances.merge(id, amount, (a, b) -> {
            long s = a + b;
            if (((a ^ s) & (b ^ s)) < 0) s = Long.MAX_VALUE; // overflow guard
            return s;
        });
        dirty = true;
    }

    /** @return false (and changes nothing) if the player can't afford it. */
    public boolean take(UUID id, long amount) {
        if (amount < 0 || get(id) < amount) return false;
        balances.merge(id, -amount, Long::sum);
        dirty = true;
        return true;
    }

    public String fmt(long amount) {
        return cfg.currencySymbol + String.format(Locale.US, "%,d", amount);
    }
}
