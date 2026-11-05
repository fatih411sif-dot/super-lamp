# SIM SMP (Fabric mod, Minecraft 26.3)

Server-side Fabric mod. Players do **not** need to install anything.

## Features
- **₹ economy** - balances saved in `config/sim_smp/balances.json`. New players start with ₹2,000.
- **Sidebar scoreboard** titled **SIM SMP**: Player, Money, Hearts, Deaths, Playtime, Ping, Online (updates every second, per player).
- **/sell** - opens a chest. Put ores/resources in, close it, get paid. Unsellable items are returned.
- **/balance** (`/bal`, `/money`), **/tf <player> <amount>** (send money)
- **/trade <player>** - player-to-player trading with items and ₹ (both must accept; any change resets accepts).

## Trade commands
| Command | What it does |
|---|---|
| `/trade <player>` | Send a trade request (expires after 60s) |
| `/trade accept <player>` | Accept a request, opens the trade window |
| `/trade deny` | Decline a request |
| `/trade money <amount>` | Set your ₹ offer inside a trade |
| `/trade cancel` | Cancel the current trade |

In the window: left 4 columns = your items, right 4 = partner's. Click the emerald to accept.

## Install
1. Install **Fabric Loader 0.19.5+** and **Fabric API (26.3)** on the server, Java 25.
2. Put the built `sim-smp-1.0.0.jar` (from `build/libs/`) in `mods/`.
3. Start the server once. Edit `config/sim_smp/config.json` and `prices.json`, then restart.

## Build
Use the official Fabric template generator for 26.3 (https://fabricmc.net/develop/template/) to get the
Gradle wrapper, then copy this project's `src/` folder plus `build.gradle`/`gradle.properties` over it
(or run `gradle wrapper --gradle-version 9.6.0` here), then:

    ./gradlew build

## Config
`config.json`: `currencySymbol`, `scoreboardTitle`, `startingBalance`, `saveIntervalSeconds`, `tradeRequestSeconds`.
`prices.json`: `"item_id": price_per_item` (e.g. `"diamond": 60`). Items not listed can't be sold.

## Notes
- Hearts = max health / 2 (default 10). Edit `SidebarManager.build` if your lifesteal mod uses a different scale.
- If a stat name fails to compile on your exact build (`Stats.PLAY_TIME`), try `Stats.PLAY_ONE_MINUTE`.

## Transfer, market, leaderboard, daily shop, OP prices
| Command | Who | What it does |
|---|---|---|
| `/tf <player> <amount>` | everyone | Send ₹ to another player (replaces `/pay`) |
| `/top` | everyone | GUI podium of the 3 richest players: 1st in the middle, 2nd on the right, 3rd on the left, plus your own rank |
| `/market` (also `/ah`) | everyone | Player market GUI. Click a listing twice to buy it |
| `/market sell <price> [amount]` | everyone | List the item in your main hand (any item). With `amount` you sell only that many and keep the rest; without it the whole stack. The price is for the whole listing |
| `/gacha` | everyone | Gacha GUI: pay ₹100, a row of prizes spins and slows down, and the prize stops under the arrow |
| `/dshop` | everyone | Your personal daily shop: 20 random items, different for every player |
| `/prices` | everyone | What the system pays for each item with `/sell` |
| `/sellprice hand <price>` | OP | Set the system sell price of the item in your hand |
| `/sellprice set <item> <price>` | OP | e.g. `/sellprice set diamond 80` |
| `/sellprice remove <item>` | OP | Stop the system buying that item |

- `/sell` still buys ores and resources (coal, iron, copper, gold, diamond, emerald, netherite, ...) for the system.
- Daily shop refreshes every **5 hours** (`dailyShopHours` in `config.json`). Each offer can be bought once per refresh.
- Items, amount, price and **chance** of the daily shop are in `config/sim_smp/dailyshop.json`:
  `{"item": "minecraft:diamond", "amount": 2, "price": 300, "chance": 10}`
  `chance` is a weight: 100 = common, 40 = uncommon, 10 = rare, 3 = epic. Restart after editing.
- `dailyShopSlots` (default 20, max 25), `maxListingsPerPlayer` (10) and `marketTaxPercent` (0) are in `config.json`.
- Player names for `/top` are learned when a player joins ("Unknown" until then).
- `/sellprice` changes are saved to `prices.json` immediately.

## Gacha
- One pull costs **₹100** (`gachaPrice` in `config.json`). Click the emerald to spin; the book shows the drop rates.
- Default odds: Common 70%, Uncommon 22%, Rare 6.5%, Epic 1.4%, Legendary 0.1%. Epic and Legendary pulls are announced to the whole server.
- The prize pool is `config/sim_smp/gacha.json`. Each line: `{"item": "minecraft:diamond", "amount": 1, "chance": 464, "rarity": "RARE"}`.
  `chance` is a weight (higher = drops more often), `rarity` is COMMON, UNCOMMON, RARE, EPIC or LEGENDARY. Restart after editing.
- The money is taken when the spin starts. If the window is closed during the spin, the prize is handed over immediately.
