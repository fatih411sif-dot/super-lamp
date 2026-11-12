package com.simsmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class MarketManager {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private final SimConfig cfg;
   private final EconomyManager eco;
   private final Path file;
   private final List<MarketManager.Listing> listings = new ArrayList<>();
   private long nextId = 1L;
   private MinecraftServer server;

   public MarketManager(SimConfig cfg, EconomyManager eco, Path file) {
      this.cfg = cfg;
      this.eco = eco;
      this.file = file;
   }

   public void load(MinecraftServer server) {
      this.server = server;
      if (Files.exists(this.file)) {
         try {
            Files.copy(this.file, this.file.resolveSibling("market.json.bak"), StandardCopyOption.REPLACE_EXISTING);
         } catch (IOException var11) {
         }

         RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());

         try (Reader r = Files.newBufferedReader(this.file)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            this.nextId = root.get("nextId").getAsLong();

            for (JsonElement e : root.getAsJsonArray("listings")) {
               try {
                  JsonObject o = e.getAsJsonObject();
                  Optional<ItemStack> stack = ItemStack.CODEC.parse(ops, o.get("item")).result();
                  if (!stack.isEmpty() && !stack.get().isEmpty()) {
                     this.listings
                        .add(
                           new MarketManager.Listing(
                              o.get("id").getAsLong(),
                              UUID.fromString(o.get("seller").getAsString()),
                              o.get("sellerName").getAsString(),
                              stack.get(),
                              o.get("price").getAsLong()
                           )
                        );
                  }
               } catch (RuntimeException var10) {
                  SimSmp.LOGGER.warn("Skipping an unreadable market listing (see market.json.bak)", var10);
               }
            }
         } catch (RuntimeException | IOException var13) {
            SimSmp.LOGGER.error("Could not read market.json (a backup is in market.json.bak)", var13);
         }
      }
   }

   public synchronized void save() {
      if (this.server != null) {
         RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, this.server.registryAccess());
         JsonObject root = new JsonObject();
         root.addProperty("nextId", this.nextId);
         JsonArray arr = new JsonArray();

         for (MarketManager.Listing l : this.listings) {
            Optional<JsonElement> item = ItemStack.CODEC.encodeStart(ops, l.stack()).result();
            if (!item.isEmpty()) {
               JsonObject o = new JsonObject();
               o.addProperty("id", l.id());
               o.addProperty("seller", l.seller().toString());
               o.addProperty("sellerName", l.sellerName());
               o.addProperty("price", l.price());
               o.add("item", item.get());
               arr.add(o);
            }
         }

         root.add("listings", arr);

         try {
            Files.createDirectories(this.file.getParent());
            Path tmp = this.file.resolveSibling("market.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));

            try {
               Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var8) {
               Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException var9) {
            SimSmp.LOGGER.error("Could not save market.json", var9);
         }
      }
   }

   public List<MarketManager.Listing> view(boolean mineOnly, UUID me) {
      List<MarketManager.Listing> out = new ArrayList<>();

      for (MarketManager.Listing l : this.listings) {
         if (!mineOnly || l.seller().equals(me)) {
            out.add(l);
         }
      }

      Collections.reverse(out);
      return out;
   }

   public MarketManager.Listing find(long id) {
      for (MarketManager.Listing l : this.listings) {
         if (l.id() == id) {
            return l;
         }
      }

      return null;
   }

   private static String label(MarketManager.Listing l) {
      return l.stack().getCount() + "x " + l.stack().getHoverName().getString();
   }

   public void list(ServerPlayer p, long price, int amount) {
      ItemStack held = p.getMainHandItem();
      if (held.isEmpty()) {
         Msg.err(p, "Hold the item you want to sell in your main hand.");
      } else {
         int count = amount <= 0 ? held.getCount() : amount;
         if (count > held.getCount()) {
            Msg.err(p, "You are only holding " + held.getCount() + ".");
         } else {
            long mine = this.listings.stream().filter(lx -> lx.seller().equals(p.getUUID())).count();
            if (mine >= this.cfg.maxListingsPerPlayer) {
               Msg.err(p, "You already have " + mine + " listings (max " + this.cfg.maxListingsPerPlayer + "). Take one back in /market first.");
            } else {
               ItemStack copy = held.copy();
               copy.setCount(count);
               held.shrink(count);
               if (held.isEmpty()) {
                  p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
               }

               MarketManager.Listing l = new MarketManager.Listing(this.nextId++, p.getUUID(), Msg.name(p), copy, price);
               this.listings.add(l);
               this.save();
               Msg.ok(p, "Listed " + label(l) + " for " + this.eco.fmt(price) + ". Open /market to see it.");
            }
         }
      }
   }

   public boolean buy(ServerPlayer buyer, long id) {
      MarketManager.Listing l = this.find(id);
      if (l == null) {
         Msg.err(buyer, "That listing is no longer available.");
         return false;
      } else if (l.seller().equals(buyer.getUUID())) {
         Msg.err(buyer, "That's your own listing. Switch to \"My listings\" to take it back.");
         return false;
      } else if (!this.eco.take(buyer.getUUID(), l.price())) {
         Msg.err(buyer, "You need " + this.eco.fmt(l.price()) + " but only have " + this.eco.fmt(this.eco.get(buyer.getUUID())) + ".");
         return false;
      } else {
         this.listings.remove(l);
         long tax = l.price() * Math.max(0, Math.min(100, this.cfg.marketTaxPercent)) / 100L;
         long payout = l.price() - tax;
         this.eco.add(l.seller(), payout);
         if (PriceEngine.get() != null) {
            PriceEngine.get().recordMarket(l.price());
         }

         ItemGiver.give(buyer, l.stack().copy());
         this.save();
         Msg.ok(buyer, "You bought " + label(l) + " for " + this.eco.fmt(l.price()) + ".");
         if (this.server != null) {
            ServerPlayer seller = this.server.getPlayerList().getPlayer(l.seller());
            if (seller != null) {
               Msg.ok(
                  seller,
                  Msg.name(buyer)
                     + " bought your "
                     + label(l)
                     + ". You received "
                     + this.eco.fmt(payout)
                     + (tax > 0L ? " (fee " + this.eco.fmt(tax) + ")" : "")
                     + "."
               );
            }
         }

         return true;
      }
   }

   public void cancel(ServerPlayer p, long id) {
      MarketManager.Listing l = this.find(id);
      if (l == null) {
         Msg.err(p, "That listing is no longer available.");
      } else if (!l.seller().equals(p.getUUID())) {
         Msg.err(p, "That isn't your listing.");
      } else {
         this.listings.remove(l);
         ItemGiver.give(p, l.stack().copy());
         this.save();
         Msg.ok(p, "You took back " + label(l) + ".");
      }
   }

   public record Listing(long id, UUID seller, String sellerName, ItemStack stack, long price) {
   }
}
