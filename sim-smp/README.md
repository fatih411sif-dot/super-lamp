# SIM SMP (Fabric mod, Minecraft 26.3)

Server-side Fabric mod. Players do **not** need to install anything.

## Features
- **₹ economy** - balances saved in `config/sim_smp/balances.json`. New players start with ₹2,000.
- **Sidebar scoreboard** titled **SIM SMP**: Player, Money, Hearts, Deaths, Playtime, Ping, Online (updates every second, per player).
- **/sell** - opens a chest. Put ores/resources in, close it, get paid. Unsellable items are returned.
- **/balance** (`/bal`, `/money`), **/pay <player> <amount>**
- **/trade <player>** - player-to-player trading with items and ₹ (both must accept; any change resets accepts).

## Trade commands
| Command | What it does |
|---|---|
| `/trade <player>` | Send a trade request (expires after 60s) |
| `/trade accept <player>` | Accept a request, opens the trade window |
| `/trade deny` | Decline a request |
| `/trade money <amount>` | Set your ₹ offer inside a trade |
| `/trade cancel` | Cancel the current trade |

In the window: left 4 columns = your items, right 4 = partner's. Click the green button to accept.

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
