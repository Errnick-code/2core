# 2Core

> Run a second Fabric server as a separate JVM process — one mod, two roles.

---

## Overview

**2Core** lets you run two Minecraft servers on the same machine from a single mod. The primary server launches and manages the secondary server as a child JVM process. Both servers use the same mod JAR — the role is determined automatically at startup via a system property.

No plugins, no proxies, no BungeeCord required.

---

## Features

- **One mod, two servers** — install once, the mod handles both roles automatically
- **Cross-server chat sync** — chat messages, join/leave notifications, and death messages relayed in real time
- **`/listother`** — shows online players on the other server, available to all players
- **Process management** — start, stop, restart the secondary with simple in-game commands
- **Console relay** — optionally mirror secondary console output to the primary
- **Auto-start** — optionally start the secondary automatically when the primary starts
- **Configurable messages** — customize join/leave/chat/death formats per server with `&` color codes
- **Localhost IPC** — inter-server communication over a local TCP socket, no external exposure

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.18.6 |
| Fabric API | any |
| Fabric Language Kotlin | ≥ 1.13.0 |

Server-side only. No client installation needed.

---

## Installation

1. Drop the mod into your primary server's `mods/` folder (along with Fabric API and Fabric Language Kotlin)
2. Start the primary server — a `2core/` folder and `2core/2core.properties` config are created automatically
3. Place your secondary server files inside `2core/` (or set `copy_mode` to copy them automatically)
4. Run `/2core start` in-game or in the console

The mod copies itself into `2core/mods/` automatically — no need to install it manually on the secondary.

---

## Commands

### All players (both servers)

| Command | Description |
|---|---|
| `/listother` | Show online players on the other server |

### Admins — primary server only

| Command | Description |
|---|---|
| `/2core start` | Start the second server |
| `/2core stop` | Stop the second server |
| `/2core restart` | Restart the second server |
| `/2core status` | Show status, config, and IPC info |
| `/2core chatlink on\|off` | Toggle cross-server event sync |
| `/2core console on\|off` | Toggle secondary console relay |
| `/2core cmd <command>` | Send a command to the secondary server |
| `/2core set <key> <value>` | Change a config value live |
| `/2core help` | Show command list |

---

## Configuration

Config file: `2core/2core.properties`

| Key | Default | Description |
|---|---|---|
| `copy_mode` | `none` | `none` / `launcher` / `full` — how to copy server files into `2core/` |
| `launcher_jar` | `fabric-server-launch.jar` | Fabric launcher jar name |
| `vanilla_jar` | `server.jar` | Vanilla server jar name |
| `port` | `25566` | Port for the secondary server |
| `max_ram_mb` | `1024` | Max RAM for the secondary JVM |
| `auto_start` | `false` | Start secondary automatically on primary startup |
| `auto_console` | `false` | Enable console relay automatically on startup |
| `chat_link` | `false` | Sync chat, join, leave, and death events |
| `ipc_port` | `0` | IPC port (0 = auto) |
| `join_message_for_primary` | `&6%player% joined the second server` | Shown on primary when someone joins secondary |
| `join_message_for_secondary` | `&6%player% joined the main server` | Shown on secondary when someone joins primary |
| `leave_message_for_primary` | `&6%player% left the second server` | Shown on primary when someone leaves secondary |
| `leave_message_for_secondary` | `&6%player% left the main server` | Shown on secondary when someone leaves primary |
| `chat_format_for_primary` | `&7[&62nd&7] &f<%player%> %message%` | Chat format on primary for messages from secondary |
| `chat_format_for_secondary` | `&7[&61st&7] &f<%player%> %message%` | Chat format on secondary for messages from primary |
| `death_format_for_primary` | `&7[&62nd&7] &c%message%` | Death message format on primary |
| `death_format_for_secondary` | `&7[&61st&7] &c%message%` | Death message format on secondary |

Placeholders: `%player%`, `%message%`. Color codes: `&0`–`&9`, `&a`–`&f`, `&l`, `&o`, `&r`, etc.

---

## How it works

When the primary server starts, it launches the secondary as a child JVM with `-Dtwocore.role=secondary`. Both processes run the same JAR — the secondary detects its role via system property and skips all process management logic. Cross-server communication (chat sync, `/listother`) happens over a localhost TCP socket. The secondary shuts down automatically if the primary process dies.

---

## Download

Available on [Modrinth](https://modrinth.com/mod/2core).

---

## License

[MIT](LICENSE)
