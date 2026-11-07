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
| `/redeem <code>` | everyone | Redeem a code for money and/or items (once per player per code) |
| `/gacha` | everyone | Gacha GUI: pay ₹100, a row of prizes spins and slows down, and the prize stops under the arrow |
| `/dshop` | everyone | Daily shop: 20 random items, the same for every player. When someone buys an item it is sold out for everybody (first come, first served) |
| `/prices` | everyone | What the system pays for each item with `/sell` |
| `/sellprice hand <price>` | OP | Set the system sell price of the item in your hand |
| `/sellprice set <item> <price>` | OP | e.g. `/sellprice set diamond 80` |
| `/sellprice remove <item>` | OP | Stop the system buying that item |

- `/sell` still buys ores and resources (coal, iron, copper, gold, diamond, emerald, netherite, ...) for the system.
- Daily shop refreshes every **1 hour** (`dailyShopHours` in `config.json`). The shop is shared by all players and each offer can be bought only once per refresh, by whoever is fastest.
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

## Classes
8 classes: Economy (Miner, Farmer, Lumberjack, Hunter) and Combat (Archer, Berserker, Warrior, Mage). **Miner**, **Farmer** and **Lumberjack** have a skill tree for now.
- `/class` opens the GUI. First choose **2 Economy + 2 Combat** classes (click to pick), then click one of them twice to equip it. Only ONE class is equipped at a time; every class keeps its own level, XP and skills.
- Switching the equipped class has a cooldown (`classSwitchMinutes`, default 10). To swap one of your 4 chosen classes use `/class replace <old> <new>`: costs `classChangeFee` (default 10,000) and the old class goes back to level 0.
- **Class XP** is shown in the sidebar. It is also the money for skills. Level comes from total XP earned, so buying skills never lowers it.
- Skills form a tree: a skill can only be unlocked after the skill above it. Perks of the same kind add up.
- `/ability` lists your abilities, `/ability <name> <true|false>` switches one on or off.
- OP: `/classadmin addxp <player> <amount>` (for testing).

### Miner
XP comes from ores only (coal 1, copper 1, iron 2, redstone 2, gold 3, lapis 3, emerald 6, diamond 8, ancient debris 15). The whole tree costs 10,250 Class XP.
- Ore Value I-V: +10%, +20%, +30%, +50%, +100% money when selling ores with `/sell` (total +210%). Does not apply to items bought from `/dshop`.
- Mining Speed I-IV: +10%, +20%, +40%, +60% (total +130%). Super Efficiency: extra mining efficiency like Efficiency III.
- Light Landing: 50% less fall damage.
- Abilities (pickaxe needed): `veinminer` (breaks the connected ore vein, max `veinMinerMaxBlocks`, default 64), `nightvision` (no time limit), `mine3x3` (breaks a 3x3 area).

### Farmer
XP: 1 for every fully grown crop harvested (young crops give nothing, so replanting cannot be farmed). The whole tree costs 10,150 Class XP.
- Harvest Value I-IV: +50%, +80%, +100%, +150% money when selling farm goods (crops, fruit, bread, eggs, honey...) with `/sell` (total +380%). Does not apply to items bought from `/dshop`.
- Fast Growth I-V: +20%, +40%, +50%, +60%, +100% crop growth speed within 10 blocks of you (total +270%). It gives crops extra growth chances; water and light rules still apply.
- `veinharvester` ability: harvesting one ripe crop harvests all connected ripe crops of the same kind on the same level (max `veinHarvesterMaxBlocks`, default 128).
- `instantgrow` ability: right-click a crop with a hoe and it plus every connected crop grows to full size. Cooldown `instantGrowCooldownSeconds` (default 300 = 5 minutes), max `instantGrowMaxBlocks` crops (default 256).

### Lumberjack
XP: 2 for every log of a natural tree (stripped logs never count, and a log only counts when natural leaves are close, so placing and re-breaking logs cannot be farmed). There is no auto tree-cutting. The whole tree costs 10,000 Class XP.
- Timber Value I-V: +40%, +50%, +60%, +70%, +120% money when selling logs, wood, planks, leaves and saplings with `/sell` (total +340%). Does not apply to items bought from `/dshop`.
- Fast Growth I-V: +40%, +50%, +60%, +70%, +120% sapling growth speed within 10 blocks of you (total +340%).
- Chopping Speed I-III: +20%, +30%, +50% (total +100%), only while you hold an axe.
- `treegrow` ability: right-click a sapling with an axe and it grows into a full tree right away. Cooldown `instantTreeCooldownSeconds` (default 30). If the tree has no room it does not grow and the cooldown is not used.

## Redeem codes
Players type `/redeem <code>` (not case sensitive). Each player can use a code **once**. Wrong codes are rate limited (5 per minute).

### Setting up a code (in game, OP only)
| Command | What it does |
|---|---|
| `/rcode create <code> <money>` | Create a code that gives that much money (0 = items only) |
| `/rcode item hand <code> [amount]` | Add the item in your main hand as a reward. Without `amount` it uses the size of your stack. Your item is not taken |
| `/rcode item add <code> <item> <amount>` | Add a reward item by name, e.g. `/rcode item add WELCOME diamond 2` |
| `/rcode item clear <code>` | Remove all item rewards |
| `/rcode money <code> <amount>` | Change the money reward |
| `/rcode maxuses <code> <uses>` | How many players in total can use it (0 = unlimited) |
| `/rcode expire <code> <hours>` | The code stops working in that many hours (0 = never expires) |
| `/rcode enable <code>` / `/rcode disable <code>` | Switch a code on or off without deleting it |
| `/rcode info <code>` | Show rewards, uses and expiry |
| `/rcode list` | List all codes |
| `/rcode delete <code>` | Delete a code (and the record of who used it) |
| `/rcode reload` | Re-read `redeem.json` after editing it by hand |

Example: a welcome gift for the first 50 players, valid for 2 days:
```
/rcode create WELCOME 500
/rcode item add WELCOME diamond 2
/rcode item add WELCOME bread 16
/rcode maxuses WELCOME 50
/rcode expire WELCOME 48
```
Then tell players to type `/redeem WELCOME`.

### Setting up codes in the file
Codes are stored in `config/sim_smp/redeem.json` (created on first start with a disabled `EXAMPLE` code):
```json
[
  {
    "code": "WELCOME",
    "enabled": true,
    "money": 500,
    "items": [
      { "item": "minecraft:diamond", "amount": 2 },
      { "item": "minecraft:bread", "amount": 16 }
    ],
    "maxUses": 50,
    "expires": "2026-12-31 23:59"
  }
]
```
- `money`: rupees given (0 for none). `items`: any Minecraft item id and amount (big amounts are split into stacks; unknown ids are skipped with a warning in the log).
- `maxUses`: total number of players who can use the code, `0` = unlimited.
- `expires`: `yyyy-MM-dd HH:mm` in **UTC** (Jakarta time is UTC+7, so subtract 7 hours), or `""` for never.
- After editing, run `/rcode reload` (or restart the server).
- Who already used a code is kept in `redeem_state.json`; deleting a code with `/rcode delete` also clears its record.
- Reward items keep only the item type and amount (no enchantments or custom names).
