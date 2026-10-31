# Grants

**GUI-based rank granting for LuckPerms, with full grant history and a Discord bot.**

Grants lets your staff give, remove and look up player ranks through clean in-game menus instead of typing long `/lp` commands. Every grant is logged with who gave it, why, for how long and when it ends, and you can manage it all from Discord too.

![Version](https://img.shields.io/badge/version-1.1.1-blue)
![Minecraft](https://img.shields.io/badge/minecraft-1.8.8%20--%201.21%2B-green)
![Java](https://img.shields.io/badge/java-8%2B-orange)
![Requires](https://img.shields.io/badge/requires-LuckPerms-purple)

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Commands](#commands)
- [Permissions](#permissions)
- [How granting works](#how-granting-works)
- [Durations](#durations)
- [Configuration files](#configuration-files)
- [Cross-server sync (database)](#cross-server-sync-database)
- [Discord bot](#discord-bot)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Credits](#credits)

---

## Features

- **Menu-driven granting**: pick a rank, a duration and a reason, then confirm. No commands to memorise.
- **Temporary or permanent ranks**: preset durations, permanent, or type any custom time like `1d12h`.
- **Full grant history**: active grants in green, expired or removed grants in red, the default rank in grey. Hover for every detail.
- **Rank hierarchy**: staff can only grant or remove ranks lower than their own (based on LuckPerms weights).
- **Tracks `/lp` too**: ranks given with normal LuckPerms commands still show up in history.
- **Player notifications**: players get a "Your rank has now been set to..." message, even across servers.
- **Cross-server sync**: share history and notifications between servers using MySQL, MariaDB or PostgreSQL, or just reuse your LuckPerms database.
- **Discord bot**: `/grants add`, `/grants remove` and `/grants history` slash commands, role-based access, multiple Discord servers, and a log channel for every change.
- **Fully customisable**: every message, menu title, item, slot, sound, colour and reason is editable. Supports `&` colour codes and hex colours (`&#55FFFF`, 1.16+).
- **Wide version support**: one jar works on 1.8.8 through the latest versions.

---

## Requirements

| Requirement | Version |
|---|---|
| Server | Spigot, Paper or a fork |
| Minecraft | 1.8.8 to 1.21+ |
| Java | 8 or newer |
| [LuckPerms](https://luckperms.net/) | 5.x (required) |

The Discord bot is built into the jar. You don't need to install anything extra for it.

---

## Installation

1. Download `Grants-1.1.1.jar` (or [build it yourself](#building-from-source)).
2. Put it in your server's `plugins/` folder, next to LuckPerms.
3. Restart the server. Config files are created in `plugins/Grants/`.
4. Give your staff the [permissions](#permissions) they need.
5. *(Optional)* Set rank weights so the hierarchy works:
   ```
   /lp group owner setweight 100
   /lp group admin setweight 80
   /lp group mod setweight 50
   ```
6. *(Optional)* Set up [cross-server sync](#cross-server-sync-database) or the [Discord bot](#discord-bot).

After editing any file, run `/grants reload`. (`database.yml` and the Discord `enabled`/`token` settings need a full restart.)

---

## Commands

| Command | Description | Permission |
|---|---|---|
| `/grants add <player>` | Open the grant menu for a player | `grants.add` |
| `/grants remove <player>` | Pick one of the player's active grants to remove | `grants.remove` |
| `/grants history <player>` | View every grant the player has ever had | `grants.history` |
| `/grants color <rank> <color>` | Set the wool colour a rank shows in the menu | `grants.color` |
| `/grants reload` | Reload every Grants file | `grants.reload` |
| `/grants help` | Show help (only lists commands you can use) | any of the above |
| `/grants info` | Plugin info | none |

`/grants colour` works too.

---

## Permissions

| Permission (on LuckPerms) | What it allows | Default |
|---|---|---|
| **`grants.add`** | `/grants add <player>`: opens the menus to give someone a rank. | op |
| **`grants.remove`** | `/grants remove <player>`: removes someone's active grants through the confirm screen. | op |
| **`grants.history`** | `/grants history <player>`: views someone's grant history (green active, red expired or removed, grey default rank). | op |
| **`grants.color`** | `/grants color <rank> <color>`: changes a rank's wool colour in the add menu. With a shared database, the change reaches every server. | op |
| **`grants.reload`** | `/grants reload`: reloads the plugin's `.yml` files after you edit them. | op |
| **`grants.admin`** | All five above in one permission. | op |

### How they work together

- **`/grants help` and `/grants info`:** anyone with at least one of the five command permissions can use `/grants help`, and it only lists the commands they're allowed to use. Anyone without any of them who types any `/grants` command just sees the `/grants info` text.
- **The rank-weight limit still applies.** Even with `grants.add` or `grants.remove`, staff can only grant or remove ranks with a lower weight than their own. For example, an admin (80) can't touch manager (90) or owner (100). Only whoever holds your highest-weight rank can grant every rank, and no permission skips this. You can turn the limit off in `config.yml` under `rank-hierarchy`.
- **OPs:** they have all five command permissions by default, but they're still limited by rank weight.

### Giving permissions with LuckPerms

**With commands:**

```
# Give a rank one permission
/lp group mod permission set grants.history true

# Give a rank every Grants command
/lp group admin permission set grants.admin true

# Give a single player a permission
/lp user Steve permission set grants.add true

# Take a permission away
/lp group mod permission unset grants.history

# Give a permission for a limited time (e.g. 7 days)
/lp group helper permission settemp grants.history true 7d

# Only on one server (if you set "server" in LuckPerms' config.yml)
/lp group mod permission set grants.add true server=practice
```

**With the web editor:**

1. Run `/lp editor` in game or in the console. LuckPerms gives you a link.
2. Open the link and click the group (e.g. `mod`) or user on the left.
3. In the **Add permission** box at the bottom, type the permission (e.g. `grants.add`), keep the value **true**, and click **Add**. Add as many as you want.
4. Click **Save** at the top. LuckPerms shows a command like `/lp applyedits <code>`.
5. Run that command in game or in the console to apply the changes.

**Setting rank weights** (needed for the rank-weight limit):

```
/lp group owner setweight 100
/lp group manager setweight 90
/lp group admin setweight 80
/lp group mod setweight 50
```

You can also set the weight in `/lp editor` by clicking the group and changing its **weight** field.

**Example staff setup:**

| Rank | Weight | Permissions | Can grant |
|---|---|---|---|
| owner | 100 | `grants.admin` | Every rank |
| manager | 90 | `grants.admin` | Ranks below 90 |
| admin | 80 | `grants.add`, `grants.remove`, `grants.history` | Ranks below 80 |
| mod | 50 | `grants.history` | Nothing, history only |

---

## How granting works

`/grants add <player>` walks through four menus:

1. **Rank**: every LuckPerms group, shown as coloured wool. Ranks above yours show *Higher than your rank*, ranks they already have show *Already granted*.
2. **Duration**: preset times, **Permanent**, or **Custom** (type it in chat).
3. **Reason**: preset reasons, or **Custom Reason** (type it in chat).
4. **Confirm**: lime concrete to grant, red concrete to cancel.

While typing a custom duration or reason, type `cancel` to go back.

`/grants remove <player>` shows the player's active grants. Click one, then confirm.

`/grants history <player>` shows:
- 🟩 **Green wool**: active grants
- 🟥 **Red wool**: grants that expired or were removed (with who removed it and when)
- ⬜ **Grey wool** (bottom right): the default rank every player has

---

## Durations

Combine any of these units, for example `30m`, `2h30m`, `1d12h` or `2w`:

| Unit | Meaning |
|---|---|
| `s` | seconds |
| `m` | minutes |
| `h` | hours |
| `d` | days |
| `w` | weeks |
| `mo` | months |
| `y` | years |

Use `permanent` for a rank that never expires.

---

## Configuration files

All files are in `plugins/Grants/` and are commented throughout.

| File | What it controls |
|---|---|
| `config.yml` | Date format, timezone, default rank, rank hierarchy, player notifications, `/lp` tracking, cancel word, sounds |
| `messages.yml` | Every chat message and the `/grants help` page |
| `gui.yml` | Menu titles, items, names, lore and slots |
| `ranks.yml` | Wool colour per rank, and ranks hidden from the menu |
| `durations.yml` | Duration buttons (up to 26) |
| `reasons.yml` | Reason buttons (up to 27) |
| `database.yml` | Local storage or a shared SQL database |
| `discord.yml` | Discord bot token, servers, roles and log channel |
| `grants.yml` | Saved history when using local storage (don't edit) |

### Example: custom durations and reasons

```yaml
# durations.yml
presets:
  - 1h
  - 1d
  - 7d
  - 30d
```

```yaml
# reasons.yml
reasons:
  - Purchased
  - Promoted
  - Event Winner
  - Media
```

### Example: rank colours

```
/grants color owner red
/grants color admin cyan
/grants color vip lime
```

Available colours: `white`, `orange`, `magenta`, `light_blue`, `yellow`, `lime`, `pink`, `gray`, `light_gray`, `cyan`, `purple`, `blue`, `brown`, `green`, `red`, `black`.

To hide ranks from the grant menu, add them to `hidden` in `ranks.yml`.

---

## Cross-server sync (database)

By default history is stored per server in `grants.yml`. To share history **and** rank notifications between servers (for example a lobby and a practice server), point every server at the same SQL database.

The easiest way is to leave `use-luckperms-settings: true` in `database.yml` on every server. Grants then uses the same database LuckPerms does.

```yaml
# database.yml
use-luckperms-settings: false
type: mysql            # local, mysql, mariadb or postgresql
address: localhost:3306
database: minecraft
username: root
password: 'yourpassword'
table-prefix: 'grants_' # must be the same on every server
sync-interval: 2        # seconds between checks for other servers' messages
```

Grants creates two tables: `<prefix>history` and `<prefix>messages`. If LuckPerms uses H2, SQLite, MongoDB or flat files, Grants falls back to local storage.

Changes to `database.yml` need a full restart.

---

## Discord bot

Manage ranks straight from Discord with slash commands:

| Command | Description |
|---|---|
| `/grants add <player> <rank> <duration> <reason>` | Give a player a rank |
| `/grants remove <player> <rank>` | Take a rank away |
| `/grants history <player>` | Browse Active / Expired grants with page buttons |

All options autocomplete.

### Setup

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications), create an application, open **Bot**, click **Reset Token** and copy it.
2. Under **OAuth2 → URL Generator**, tick `bot` and `applications.commands`, open the link and add the bot to your Discord server(s).
3. In Discord, turn on **Settings → Advanced → Developer Mode**. Then right-click to copy your server ID, role IDs and log channel ID.
4. Fill in `discord.yml`:

```yaml
enabled: true
token: "YOUR_BOT_TOKEN"
log-channel-id: "123456789012345678"

discord-servers:
  main:
    name: "Main Discord"
    guild-id: "123456789012345678"
    allowed-roles:
      - "123456789012345678"
```

5. Restart the server.

**Notes**
- Access on Discord is controlled by `allowed-roles`, so the in-game rank hierarchy does not apply there.
- You can add as many Discord servers as you want. Copy a block under `discord-servers` and give it a new key.
- The log channel gets an embed for every grant and removal, from in-game, Discord and (optionally) `/lp`.
- Set `private-replies: true` to make the bot's replies visible only to the person who used the command.
- The bot needs **View Channel**, **Send Messages** and **Embed Links** in the log channel.
- **Never share your bot token.** Anyone who has it can control your bot.

---

## Building from source

You need **JDK 8+** and **Maven**.

```bash
git clone https://github.com/snipstxd/Grants-Plugin.git
cd Grants-Plugin/GrantsPlugin
mvn clean package
```

The jar is created at `GrantsPlugin/target/Grants-1.1.1.jar`. JDA is shaded into the jar and relocated under `dev.grants.libs` so it can't conflict with other plugins.

---

## Project structure

```
GrantsPlugin/
├── pom.xml
└── src/main/
    ├── java/dev/grants/
    │   ├── GrantsPlugin.java        # Plugin entry point
    │   ├── GrantService.java        # Grant / remove logic on top of LuckPerms
    │   ├── LuckPermsListener.java   # Tracks ranks changed with /lp
    │   ├── ChatInput.java           # Custom duration / reason typed in chat
    │   ├── command/                 # /grants command and tab completion
    │   ├── gui/                     # All menus (rank, duration, reason, confirm, history, remove)
    │   ├── model/                   # Grant data and storage
    │   ├── storage/                 # Local file and SQL database
    │   ├── discord/                 # Discord bot (JDA)
    │   └── util/                    # Durations, text, items, config and version helpers
    └── resources/                   # plugin.yml and every default config file
```

---

## Credits

Made by **Snipst**.

Built with [LuckPerms API](https://luckperms.net/) and [JDA](https://github.com/discord-jda/JDA).
