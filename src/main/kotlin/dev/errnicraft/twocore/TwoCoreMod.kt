package dev.errnicraft.twocore

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

class TwoCoreMod : ModInitializer {

    companion object {
        const val MOD_ID           = "twocore"
        const val CONFIG_FILE      = "2core.properties"
        const val SERVER_DIR       = "2core"
        const val ROLE_PROPERTY    = "twocore.role"
        const val PRIMARY_PID_PROP = "twocore.primary.pid"

        @Volatile var primaryServer: MinecraftServer? = null
        val secondServer = AtomicReference<SecondaryServer?>(null)

        val chatLinkServer = ChatLinkServer()
        val chatLinkClient = ChatLinkClient()
        @Volatile var currentConfig: Properties? = null

        fun log(msg: String) = println("[2Core] $msg")

        fun loadConfig(twoCoreDir: Path): Properties {
            val props = Properties()
            props["role"]                          = "primary"
            props["copy_mode"]                     = "none"
            props["launcher_jar"]                  = "fabric-server-launch.jar"
            props["vanilla_jar"]                   = "server.jar"
            props["port"]                          = "25566"
            props["max_ram_mb"]                    = "1024"
            props["auto_start"]                    = "false"
            props["console_relay"]                 = "false"
            props["auto_console"]                  = "false"
            props["chat_link"]                     = "false"
            props["ipc_port"]                      = "0"
            props["join_message_for_primary"]      = "&6%player% joined the second server"
            props["join_message_for_secondary"]    = "&6%player% joined the main server"
            props["leave_message_for_primary"]     = "&6%player% left the second server"
            props["leave_message_for_secondary"]   = "&6%player% left the main server"
            props["chat_format_for_primary"]       = "&7[&62nd&7] &f<%player%> %message%"
            props["chat_format_for_secondary"]     = "&7[&61st&7] &f<%player%> %message%"
            props["death_format_for_primary"]      = "&7[&62nd&7] &c%message%"
            props["death_format_for_secondary"]    = "&7[&61st&7] &c%message%"

            val cfgFile = twoCoreDir.resolve(CONFIG_FILE).toFile()
            if (cfgFile.exists()) {
                FileInputStream(cfgFile).use { props.load(it) }
            } else {
                cfgFile.parentFile.mkdirs()
                FileOutputStream(cfgFile).use { out ->
                    props.store(out, """
2Core config - second server always runs as a separate JVM process
  copy_mode     - none | launcher | full
  launcher_jar  - fabric launcher jar name (default: fabric-server-launch.jar)
  vanilla_jar   - vanilla server jar name (default: server.jar)
  port          - second server port
  max_ram_mb    - RAM for the second JVM process (-Xmx)
  auto_start    - true/false - start second server on primary startup
  console_relay - true/false - relay second server output to primary console
  auto_console  - true/false - enable console_relay automatically on startup
  ipc_port      - internal IPC port for localhost communication (0 = auto-pick free port)
  chat_format_for_primary      - chat message format on PRIMARY from secondary (%player%, %message%, & colors)
  chat_format_for_secondary    - chat message format on SECONDARY from primary (%player%, %message%, & colors)
  death_format_for_primary     - death message format on PRIMARY from secondary (%message%, & colors)
  death_format_for_secondary   - death message format on SECONDARY from primary (%message%, & colors)
  chat_link     - true/false - sync chat/join/leave/death between servers (/2core chatlink on|off)
  join_message_for_primary      - shown on PRIMARY when player joins SECONDARY (%player%, & colors)
  join_message_for_secondary    - shown on SECONDARY when player joins PRIMARY (%player%, & colors)
  leave_message_for_primary     - shown on PRIMARY when player leaves SECONDARY (%player%, & colors)
  leave_message_for_secondary   - shown on SECONDARY when player leaves PRIMARY (%player%, & colors)
                    """.trimIndent())
                }
                log("Config created: $SERVER_DIR/$CONFIG_FILE")
            }
            currentConfig = props
            return props
        }

        fun saveConfig(twoCoreDir: Path, props: Properties) {
            val cfgFile = twoCoreDir.resolve(CONFIG_FILE).toFile()
            cfgFile.parentFile.mkdirs()
            FileOutputStream(cfgFile).use { out -> props.store(out, "2Core config") }
            currentConfig = props
        }
    }

    override fun onInitialize() {
        log("Initializing 2Core mod")

        val sysPropRole = System.getProperty(ROLE_PROPERTY, "").trim().lowercase()
        if (sysPropRole == "secondary") {
            log("Role: SECONDARY")

            // Read config from file — secondary runs in the 2core/ folder, config is there too.
            // Only load keys relevant to secondary to avoid accidentally starting another server.
            val secondaryAllowedKeys = setOf(
                "chat_link",
                "join_message_for_secondary",
                "leave_message_for_secondary",
                "chat_format_for_secondary",
                "death_format_for_secondary"
            )
            val secondaryCfg = Properties()
            // Defaults
            secondaryCfg["chat_link"]                    = "true"
            secondaryCfg["join_message_for_secondary"]   = "&6%player% joined the main server"
            secondaryCfg["leave_message_for_secondary"]  = "&6%player% left the main server"
            secondaryCfg["chat_format_for_secondary"]    = "&7[&61st&7] &f<%player%> %message%"
            secondaryCfg["death_format_for_secondary"]   = "&7[&61st&7] &c%message%"
            // Read from file — secondary working dir is 2core/, config is at 2core/2core.properties
            val secondaryCfgFile = File(CONFIG_FILE)
            if (secondaryCfgFile.exists()) {
                val fileProps = Properties()
                FileInputStream(secondaryCfgFile).use { fileProps.load(it) }
                for (key in secondaryAllowedKeys) {
                    fileProps.getProperty(key)?.let { secondaryCfg[key] = it }
                }
                log("Secondary loaded config from ${secondaryCfgFile.absolutePath} (keys: ${secondaryAllowedKeys.joinToString()})")
            } else {
                log("Secondary config file not found at ${secondaryCfgFile.absolutePath} — using defaults")
            }
            currentConfig = secondaryCfg

            ServerLifecycleEvents.SERVER_STARTED.register { server ->
                primaryServer = server
                log("Secondary server started on port ${server.port}")
                chatLinkClient.start()
            }

            // Chat
            ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
                if (chatLinkClient.isConnected()) {
                    chatLinkClient.sendChat(sender.gameProfile.name, message.decoratedContent().string)
                }
            }

            // Join
            ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
                if (chatLinkClient.isConnected()) {
                    chatLinkClient.sendJoin(handler.player.gameProfile.name)
                }
            }

            // Leave
            ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
                if (chatLinkClient.isConnected()) {
                    chatLinkClient.sendLeave(handler.player.gameProfile.name)
                }
            }

            // Death
            ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
                if (entity is net.minecraft.server.level.ServerPlayer) {
                    if (chatLinkClient.isConnected()) {
                        val deathMsg = entity.getCombatTracker().deathMessage?.string
                            ?: "${entity.gameProfile.name} died"
                        chatLinkClient.sendDeath(entity.gameProfile.name, deathMsg)
                    }
                }
            }

            ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
                log("Secondary server stopping")
                chatLinkClient.stop()
            }

            // Commands available on SECONDARY
            CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
                // /listother — available to all players on SECONDARY, shows players on PRIMARY
                dispatcher.register(
                    Commands.literal("listother")
                        .executes { ctx ->
                            if (!chatLinkClient.isConnected()) {
                                ctx.source.sendFailure(Component.literal("§c[2Core] Not connected to the main server."))
                                return@executes 0
                            }
                            chatLinkClient.requestPlayerList { response ->
                                val server = primaryServer ?: return@requestPlayerList
                                server.execute {
                                    ctx.source.sendSuccess({ Component.literal(response) }, false)
                                }
                            }
                            ctx.source.sendSuccess({ Component.literal("§7[2Core] Requesting player list from main server...") }, false)
                            1
                        }
                )
            }

            startPrimaryWatcher()
            return
        }

        // PRIMARY

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            primaryServer = server
            val serverDir  = server.serverDirectory
            val twoCoreDir = serverDir.resolve(SERVER_DIR)
            Files.createDirectories(twoCoreDir)

            val cfg         = loadConfig(twoCoreDir)
            val autoConsole = cfg.getProperty("auto_console", "false").trim().equals("true", ignoreCase = true)
            val ipcPort     = chatLinkServer.start(cfg.getProperty("ipc_port", "0").trim().toIntOrNull() ?: 0)

            log("Role: PRIMARY | PID: ${ProcessHandle.current().pid()} | IPC port: $ipcPort")

            Runtime.getRuntime().addShutdownHook(Thread({
                secondServer.get()?.let { if (it.isRunning()) { log("Shutdown: stopping secondary..."); it.stop() } }
                chatLinkServer.stop()
            }, "2Core-ShutdownHook"))

            if (cfg.getProperty("auto_start", "false").trim().equals("true", ignoreCase = true)) {
                log("auto_start=true — starting second server...")
                val err = startSecondary(server, relayConsole = autoConsole)
                if (err != null) log("Auto-start error: $err")
            } else {
                log("Ready. Use /2core start or set auto_start=true")
            }
        }

        // Chat
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            val cfg = currentConfig ?: return@register
            if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return@register
            if (chatLinkServer.isConnected())
                chatLinkServer.sendChat(sender.gameProfile.name, message.decoratedContent().string)
        }

        // Join
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val cfg = currentConfig ?: return@register
            if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return@register
            if (chatLinkServer.isConnected())
                chatLinkServer.sendJoin(handler.player.gameProfile.name)
        }

        // Leave
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val cfg = currentConfig ?: return@register
            if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return@register
            if (chatLinkServer.isConnected())
                chatLinkServer.sendLeave(handler.player.gameProfile.name)
        }

        // Death
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is net.minecraft.server.level.ServerPlayer) {
                val cfg = currentConfig ?: return@register
                if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return@register
                if (chatLinkServer.isConnected()) {
                    val deathMsg = entity.getCombatTracker().deathMessage?.string
                        ?: "${entity.gameProfile.name} died"
                    chatLinkServer.sendDeath(entity.gameProfile.name, deathMsg)
                }
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            secondServer.get()?.let { log("Primary stopping — stopping secondary..."); it.stop(); secondServer.set(null) }
            chatLinkServer.stop()
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("2core")
                    .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))

                    .then(Commands.literal("start").executes { ctx ->
                        val server = ctx.source.server
                        if (secondServer.get()?.isRunning() == true) {
                            ctx.source.sendSuccess({ Component.literal("§c[2Core] Second server is already running!") }, false); return@executes 0
                        }
                        ctx.source.sendSuccess({ Component.literal("§e[2Core] Starting...") }, false)
                        val twoCoreDir  = server.serverDirectory.resolve(SERVER_DIR)
                        val cfg         = loadConfig(twoCoreDir)
                        val autoConsole = cfg.getProperty("auto_console", "false").trim().equals("true", ignoreCase = true)
                        val err = startSecondary(server, relayConsole = autoConsole)
                        if (err != null) { ctx.source.sendFailure(Component.literal("§c[2Core] Error: $err")); 0 }
                        else { ctx.source.sendSuccess({ Component.literal("§a[2Core] Second server started!") }, false); 1 }
                    })

                    .then(Commands.literal("stop").executes { ctx ->
                        val sec = secondServer.get()
                        if (sec == null || !sec.isRunning()) {
                            ctx.source.sendSuccess({ Component.literal("§c[2Core] Not running.") }, false); return@executes 0
                        }
                        ctx.source.sendSuccess({ Component.literal("§e[2Core] Stopping...") }, false)
                        Thread { sec.stop(); secondServer.set(null) }.start()
                        1
                    })

                    .then(Commands.literal("restart").executes { ctx ->
                        val server = ctx.source.server
                        ctx.source.sendSuccess({ Component.literal("§e[2Core] Restarting...") }, false)
                        val twoCoreDir  = server.serverDirectory.resolve(SERVER_DIR)
                        val cfg         = loadConfig(twoCoreDir)
                        val autoConsole = cfg.getProperty("auto_console", "false").trim().equals("true", ignoreCase = true)
                        Thread {
                            secondServer.get()?.let { it.stop(); secondServer.set(null); Thread.sleep(2000) }
                            val err = startSecondary(server, relayConsole = autoConsole)
                            if (err != null) log("Restart error: $err")
                        }.start()
                        1
                    })

                    .then(Commands.literal("status").executes { ctx ->
                        val server     = ctx.source.server
                        val twoCoreDir = server.serverDirectory.resolve(SERVER_DIR)
                        val cfg        = loadConfig(twoCoreDir)
                        val sec        = secondServer.get()
                        val running    = sec?.isRunning() == true
                        val chatOn     = cfg.getProperty("chat_link","false").trim().equals("true", ignoreCase = true)
                        val sb = StringBuilder()
                        sb.appendLine("§6=== 2Core Status ===")
                        sb.appendLine("§7Role: §fPRIMARY | PID: §f${ProcessHandle.current().pid()}")
                        sb.appendLine("§7Second server: ${if (running) "§aRunning" else "§cStopped"}")
                        if (running && sec != null) {
                            sb.appendLine("§7  Uptime: §f${sec.uptimeSeconds()}s | PID: §f${sec.externalProcess.pid()}")
                            sb.appendLine("§7  Console relay: ${if (sec.isRelayingConsole()) "§aON" else "§cOFF"}")
                        }
                        sb.appendLine("§7Chat link: ${if (chatOn) "§aEnabled" else "§cDisabled"} | IPC: ${if (chatLinkServer.isConnected()) "§aConnected" else "§cDisconnected"} §7(port §f${chatLinkServer.port}§7)")
                        sb.appendLine("§7Synced events: chat, join, leave, death")
                        sb.appendLine("§7join_msg->primary:   §f${cfg.getProperty("join_message_for_primary","")}")
                        sb.appendLine("§7join_msg->secondary: §f${cfg.getProperty("join_message_for_secondary","")}")
                        sb.appendLine("§7leave_msg->primary:   §f${cfg.getProperty("leave_message_for_primary","")}")
                        sb.appendLine("§7leave_msg->secondary: §f${cfg.getProperty("leave_message_for_secondary","")}")
                        sb.appendLine("§7copy_mode: §f${cfg.getProperty("copy_mode","none")} | port: §f${cfg.getProperty("port","25566")} | ram: §f${cfg.getProperty("max_ram_mb","1024")}MB")
                        sb.append("§7auto_start: §f${cfg.getProperty("auto_start","false")} | auto_console: §f${cfg.getProperty("auto_console","false")}")
                        ctx.source.sendSuccess({ Component.literal(sb.toString().trimEnd()) }, false)
                        1
                    })

                    .then(Commands.literal("console")
                        .then(Commands.literal("on").executes { ctx ->
                            val sec = secondServer.get() ?: run { ctx.source.sendFailure(Component.literal("§c[2Core] Not running.")); return@executes 0 }
                            sec.setConsoleRelay(true)
                            ctx.source.sendSuccess({ Component.literal("§a[2Core] Console relay ON.") }, false); 1
                        })
                        .then(Commands.literal("off").executes { ctx ->
                            val sec = secondServer.get() ?: run { ctx.source.sendFailure(Component.literal("§c[2Core] Not running.")); return@executes 0 }
                            sec.setConsoleRelay(false)
                            ctx.source.sendSuccess({ Component.literal("§c[2Core] Console relay OFF.") }, false); 1
                        })
                    )

                    .then(Commands.literal("chatlink")
                        .then(Commands.literal("on").executes { ctx ->
                            val twoCoreDir = ctx.source.server.serverDirectory.resolve(SERVER_DIR)
                            val cfg = loadConfig(twoCoreDir); cfg["chat_link"] = "true"; saveConfig(twoCoreDir, cfg)
                            val status = if (chatLinkServer.isConnected()) "§aSecondary connected." else "§eSecondary not connected yet."
                            ctx.source.sendSuccess({ Component.literal("§a[2Core] Chat link ENABLED. $status") }, false); 1
                        })
                        .then(Commands.literal("off").executes { ctx ->
                            val twoCoreDir = ctx.source.server.serverDirectory.resolve(SERVER_DIR)
                            val cfg = loadConfig(twoCoreDir); cfg["chat_link"] = "false"; saveConfig(twoCoreDir, cfg)
                            ctx.source.sendSuccess({ Component.literal("§c[2Core] Chat link DISABLED.") }, false); 1
                        })
                    )

                    .then(Commands.literal("cmd")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val sec = secondServer.get() ?: run { ctx.source.sendFailure(Component.literal("§c[2Core] Not running.")); return@executes 0 }
                                val command = StringArgumentType.getString(ctx, "command")
                                if (sec.sendCommand(command)) { ctx.source.sendSuccess({ Component.literal("§7[2Core->2nd] §f$command") }, false); 1 }
                                else { ctx.source.sendFailure(Component.literal("§c[2Core] Failed to send command.")); 0 }
                            }
                        )
                    )

                    .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val twoCoreDir = ctx.source.server.serverDirectory.resolve(SERVER_DIR)
                                    val key   = StringArgumentType.getString(ctx, "key")
                                    val value = StringArgumentType.getString(ctx, "value")
                                    val allowed = setOf("copy_mode","launcher_jar","vanilla_jar","port","max_ram_mb",
                                        "auto_start","console_relay","auto_console","chat_link","ipc_port",
                                        "join_message_for_primary","join_message_for_secondary",
                                        "leave_message_for_primary","leave_message_for_secondary",
                                        "chat_format_for_primary","chat_format_for_secondary",
                                        "death_format_for_primary","death_format_for_secondary")
                                    if (key !in allowed) { ctx.source.sendFailure(Component.literal("§c[2Core] Unknown key. Available: ${allowed.joinToString()}")); return@executes 0 }
                                    val cfg = loadConfig(twoCoreDir); cfg[key] = value; saveConfig(twoCoreDir, cfg)
                                    if (key == "console_relay") secondServer.get()?.setConsoleRelay(value.trim().equals("true", ignoreCase = true))
                                    ctx.source.sendSuccess({ Component.literal("§a[2Core] $key = $value") }, false); 1
                                }
                            )
                        )
                    )

                    .then(Commands.literal("help").executes { ctx ->
                        ctx.source.sendSuccess({ Component.literal("""
§6=== 2Core Commands ===
§e/2core start §7- start second server
§e/2core stop §7- stop second server
§e/2core restart §7- restart second server
§e/2core status §7- show status and config
§e/2core console on|off §7- toggle console relay
§e/2core chatlink on|off §7- toggle cross-server sync (chat/join/leave/death)
§e/2core cmd <command> §7- send command to second server
§e/2core set <key> <value> §7- change config value
§7  Keys: copy_mode, launcher_jar, vanilla_jar, port, max_ram_mb,
§7         auto_start, console_relay, auto_console, chat_link,
§7         join/leave_message_for_primary/secondary""".trimIndent()) }, false); 1
                    })
            )

            // /listother — available to all players on PRIMARY, shows players on SECONDARY
            dispatcher.register(
                Commands.literal("listother")
                    .executes { ctx ->
                        if (!chatLinkServer.isConnected()) {
                            ctx.source.sendFailure(Component.literal("§c[2Core] Second server is not connected."))
                            return@executes 0
                        }
                        chatLinkServer.requestPlayerList { response ->
                            val server = primaryServer ?: return@requestPlayerList
                            server.execute {
                                ctx.source.sendSuccess({ Component.literal(response) }, false)
                            }
                        }
                        ctx.source.sendSuccess({ Component.literal("§7[2Core] Requesting player list from second server...") }, false)
                        1
                    }
            )
        }
    }

    private fun startPrimaryWatcher() {
        val pidStr = System.getProperty(PRIMARY_PID_PROP, "").trim()
        if (pidStr.isEmpty()) { log("[Secondary] Warning: primary PID not set."); return }
        val primaryPid = pidStr.toLongOrNull() ?: run { log("[Secondary] Invalid PID: $pidStr"); return }
        val primaryHandle = ProcessHandle.of(primaryPid).orElse(null) ?: run {
            log("[Secondary] Primary PID $primaryPid not found! Exiting..."); Thread.sleep(3000); System.exit(1); return
        }
        log("[Secondary] Monitoring primary PID $primaryPid")
        primaryHandle.onExit().thenRunAsync {
            log("[Secondary] Primary exited! Shutting down in 3s...")
            Thread.sleep(3000); System.exit(0)
        }
        Thread(null, {
            while (true) {
                Thread.sleep(5000L)
                if (!primaryHandle.isAlive) { log("[Secondary] Primary dead (poll). Shutting down..."); System.exit(0) }
            }
        }, "2Core-PrimaryWatcher", 0L).also { it.isDaemon = true; it.start() }
    }

    private fun startSecondary(primaryMc: MinecraftServer, relayConsole: Boolean): String? {
        return try {
            val serverDir  = primaryMc.serverDirectory
            val twoCoreDir = serverDir.resolve(SERVER_DIR).toFile().also { it.mkdirs() }
            val cfg        = loadConfig(twoCoreDir.toPath())
            val port       = cfg.getProperty("port",       "25566").trim().toIntOrNull() ?: 25566
            val maxRamMb   = cfg.getProperty("max_ram_mb", "1024").trim().toLongOrNull()  ?: 1024L
            val jarFile    = resolveJar(serverDir, twoCoreDir, cfg)

            if (!jarFile.exists()) return "Launcher jar not found: ${jarFile.absolutePath}"

            setupServerProperties(twoCoreDir, port)
            twoCoreDir.resolve("eula.txt").also { if (!it.exists()) it.writeText("eula=true\n") }
            deploySelfMod(serverDir, twoCoreDir)

            val proc = startSecondaryProcess(twoCoreDir, jarFile, port, maxRamMb,
                ProcessHandle.current().pid(), chatLinkServer.port)
            val sec = SecondaryServer(workDir = twoCoreDir, externalProcess = proc, relayConsole = relayConsole)
            secondServer.set(sec)
            sec.launch()
            null
        } catch (e: Exception) {
            log("Exception during start: ${e.message}"); e.printStackTrace(); e.message ?: "Unknown error"
        }
    }

    private fun resolveJar(serverDir: Path, twoCoreDir: File, cfg: Properties): File {
        val launcherName = cfg.getProperty("launcher_jar", "fabric-server-launch.jar").trim()
        val vanillaName  = cfg.getProperty("vanilla_jar",  "server.jar").trim()
        val copyMode     = cfg.getProperty("copy_mode",    "none").trim().lowercase()
        val destLauncher = twoCoreDir.resolve(launcherName)
        when (copyMode) {
            "launcher", "full" -> {
                val src = serverDir.resolve(launcherName).toFile()
                if (!src.exists()) throw RuntimeException("$launcherName not found in ${serverDir.toAbsolutePath()}")
                Files.copy(src.toPath(), destLauncher.toPath(), StandardCopyOption.REPLACE_EXISTING)
                log("Copied: $launcherName")
                if (copyMode == "full") {
                    val srcV = serverDir.resolve(vanillaName).toFile()
                    if (srcV.exists()) { Files.copy(srcV.toPath(), twoCoreDir.resolve(vanillaName).toPath(), StandardCopyOption.REPLACE_EXISTING); log("Copied: $vanillaName") }
                    for (d in listOf(".fabric","libraries","versions")) {
                        val s = serverDir.resolve(d).toFile()
                        if (s.exists()) { s.copyRecursively(twoCoreDir.resolve(d), overwrite = true); log("Copied: $d/") }
                    }
                }
            }
            else -> log("copy_mode=none — using files already in 2core/")
        }
        return destLauncher
    }

    private fun deploySelfMod(serverDir: Path, twoCoreDir: File) {
        val modsDir = twoCoreDir.resolve("mods").also { it.mkdirs() }
        try {
            val selfJar = File(TwoCoreMod::class.java.protectionDomain.codeSource.location.toURI())
            if (selfJar.exists() && selfJar.extension == "jar") {
                Files.copy(selfJar.toPath(), modsDir.resolve(selfJar.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                log("Mod copied: 2core/mods/${selfJar.name}")
            }
        } catch (e: Exception) { log("WARNING: mod JAR not found (dev mode?) — copy manually to 2core/mods/") }
        val primaryMods = serverDir.resolve("mods").toFile()
        if (primaryMods.exists()) {
            for (prefix in listOf("fabric-language-kotlin", "fabric-api")) {
                val jar = primaryMods.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.extension == "jar" }
                if (jar != null) { Files.copy(jar.toPath(), modsDir.resolve(jar.name).toPath(), StandardCopyOption.REPLACE_EXISTING); log("Copied: 2core/mods/${jar.name}") }
                else log("WARNING: $prefix not found in mods/")
            }
        }
    }

    private fun startSecondaryProcess(twoCoreDir: File, jarFile: File, port: Int, maxRamMb: Long, primaryPid: Long, ipcPort: Int): Process {
        val javaExe = ProcessHandle.current().info().command().orElse("java")
        val cmd = listOf(
            javaExe, "-Xmx${maxRamMb}M",
            "-D$ROLE_PROPERTY=secondary",
            "-D$PRIMARY_PID_PROP=$primaryPid",
            "-D$IPC_PORT_PROP=$ipcPort",
            "-Dfabric.gameDir=${twoCoreDir.absolutePath}",
            "-jar", jarFile.absolutePath,
            "nogui", "--gameDir", twoCoreDir.absolutePath
        )
        log("Starting secondary JVM: IPC port=$ipcPort, primary PID=$primaryPid")
        twoCoreDir.resolve("logs").mkdirs()
        return ProcessBuilder(cmd).directory(twoCoreDir).redirectErrorStream(true).start()
    }

    private fun setupServerProperties(twoCoreDir: File, port: Int) {
        val propsFile = twoCoreDir.resolve("server.properties")
        val p = Properties()
        if (propsFile.exists()) FileInputStream(propsFile).use { p.load(it) }
        else { p["server-port"]="$port"; p["online-mode"]="false"; p["max-players"]="20"; p["motd"]="2Core Secondary Server"; p["level-name"]="world"; p["enable-rcon"]="false"; p["enable-query"]="false" }
        p["server-port"] = port.toString()
        FileOutputStream(propsFile).use { p.store(it, "Managed by 2Core mod") }
    }
}
