package dev.errnicraft.twocore

import net.minecraft.network.chat.Component
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val IPC_PORT_PROP = "twocore.ipc.port"

fun translateColorCodes(text: String): String =
    text.replace(Regex("&([0-9a-fk-orA-FK-OR])")) { "§${it.groupValues[1]}" }

fun formatMessage(template: String, playerName: String = "", message: String = ""): String =
    translateColorCodes(template.replace("%player%", playerName).replace("%message%", message))

private fun packet(vararg pairs: Pair<String, String>): String =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()


class ChatLinkServer {

    private val running   = AtomicBoolean(false)
    private val clientRef = AtomicReference<PrintWriter?>(null)
    private var serverSocket: ServerSocket? = null
    var port: Int = 0
        private set

    // Pending player list callbacks: requestId -> callback
    private val pendingPlayerListCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    /**
     * Start the IPC server. Returns the chosen port.
     * @param preferredPort  0 = auto-pick a free port; any other value = bind to that port.
     */
    fun start(preferredPort: Int = 0): Int {
        serverSocket = ServerSocket(preferredPort)
        port = serverSocket!!.localPort
        running.set(true)
        TwoCoreMod.log("[ChatLink] Primary IPC listening on port $port (localhost only)")

        Thread(null, {
            while (running.get()) {
                try {
                    val sock = serverSocket?.accept() ?: break
                    // Accept only localhost connections
                    if (!sock.inetAddress.isLoopbackAddress) {
                        TwoCoreMod.log("[ChatLink] Rejected non-localhost connection from ${sock.inetAddress.hostAddress}")
                        sock.close()
                        continue
                    }
                    TwoCoreMod.log("[ChatLink] Secondary connected")
                    handleClient(sock)
                } catch (e: Exception) {
                    if (running.get()) TwoCoreMod.log("[ChatLink] Accept error: ${e.message}")
                }
            }
        }, "2Core-IPC-Accept", 0L).also { it.isDaemon = true; it.start() }

        return port
    }

    fun stop() {
        running.set(false)
        clientRef.get()?.close()
        clientRef.set(null)
        try { serverSocket?.close() } catch (_: Exception) {}
        TwoCoreMod.log("[ChatLink] Primary IPC stopped")
    }

    fun isConnected(): Boolean = clientRef.get() != null

    private fun send(raw: String) {
        val w = clientRef.get() ?: return
        try {
            w.println(raw)
            if (w.checkError()) clientRef.set(null)
        } catch (e: Exception) {
            TwoCoreMod.log("[ChatLink] Send error: ${e.message}")
            clientRef.set(null)
        }
    }

    fun sendChat(playerName: String, message: String) =
        send(packet("t" to "chat", "p" to playerName, "m" to message))

    fun sendJoin(playerName: String) =
        send(packet("t" to "join", "p" to playerName))

    fun sendLeave(playerName: String) =
        send(packet("t" to "leave", "p" to playerName))

    fun sendDeath(playerName: String, deathMessage: String) =
        send(packet("t" to "death", "p" to playerName, "m" to deathMessage))

    /**
     * Request the player list from secondary.
     * The callback is invoked on the primary server thread with a formatted message.
     */
    fun requestPlayerList(callback: (String) -> Unit) {
        val reqId = System.nanoTime().toString()
        pendingPlayerListCallbacks[reqId] = callback
        send(packet("t" to "playerlist_req", "id" to reqId))
    }

    private fun handleClient(sock: Socket) {
        Thread(null, {
            try {
                val writer = PrintWriter(sock.getOutputStream(), true, Charsets.UTF_8)
                clientRef.set(writer)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    dispatchIncoming(l)
                }
            } catch (e: Exception) {
                if (running.get()) TwoCoreMod.log("[ChatLink] Client error: ${e.message}")
            } finally {
                clientRef.set(null)
                try { sock.close() } catch (_: Exception) {}
                TwoCoreMod.log("[ChatLink] Secondary disconnected")
            }
        }, "2Core-IPC-Client", 0L).also { it.isDaemon = true; it.start() }
    }

    /** Handle a packet from secondary — display on primary or resolve a callback. */
    private fun dispatchIncoming(raw: String) {
        try {
            val json    = Json.parseToJsonElement(raw).jsonObject
            val t       = json["t"]?.jsonPrimitive?.content ?: return

            // Player list request FROM secondary — secondary called /listother, respond with primary's player list
            if (t == "playerlist_req") {
                val reqId  = json["id"]?.jsonPrimitive?.content ?: return
                val server = TwoCoreMod.primaryServer ?: return
                server.execute {
                    val players = server.playerList.players
                    val names   = players.joinToString(", ") { it.gameProfile.name }
                    val count   = players.size
                    send(packet("t" to "playerlist_res", "id" to reqId, "names" to names, "count" to count.toString()))
                }
                return
            }

            // Player list response from secondary
            if (t == "playerlist_res") {
                val reqId    = json["id"]?.jsonPrimitive?.content ?: return
                val callback = pendingPlayerListCallbacks.remove(reqId) ?: return
                val names    = json["names"]?.jsonPrimitive?.content ?: ""
                val count    = json["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val msg = if (count == 0) {
                    "§e[2Core] No players on the second server (2nd)."
                } else {
                    "§6[2Core] Players on the second server (2nd) §7[§f$count§7]: §f$names"
                }
                callback(msg)
                return
            }

            val server  = TwoCoreMod.primaryServer ?: return
            val cfg     = TwoCoreMod.currentConfig ?: return
            if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return

            val player = json["p"]?.jsonPrimitive?.content ?: return

            val text: String = when (t) {
                "chat" -> {
                    val msg = json["m"]?.jsonPrimitive?.content ?: return
                    val template = cfg.getProperty("chat_format_for_primary", "&7[&62nd&7] &f<%player%> %message%")
                    formatMessage(template, player, msg)
                }
                "join" -> {
                    val template = cfg.getProperty("join_message_for_primary", "&6%player% joined the second server")
                    formatMessage(template, player)
                }
                "leave" -> {
                    val template = cfg.getProperty("leave_message_for_primary", "&6%player% left the second server")
                    formatMessage(template, player)
                }
                "death" -> {
                    val msg = json["m"]?.jsonPrimitive?.content ?: "$player died"
                    val template = cfg.getProperty("death_format_for_primary", "&7[&62nd&7] &c%message%")
                    formatMessage(template, player, msg)
                }
                else -> return
            }

            server.execute {
                server.playerList.broadcastSystemMessage(Component.literal(text), false)
            }
        } catch (e: Exception) {
            TwoCoreMod.log("[ChatLink] Bad packet from secondary: $raw — ${e.message}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ChatLinkClient — runs on SECONDARY
// ─────────────────────────────────────────────────────────────────────────────

class ChatLinkClient {

    private val running   = AtomicBoolean(false)
    private val writerRef = AtomicReference<PrintWriter?>(null)
    private var ipcPort   = 0

    // Pending player list callbacks: requestId -> callback
    private val pendingPlayerListCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    fun start() {
        ipcPort = System.getProperty(IPC_PORT_PROP, "0").trim().toIntOrNull() ?: 0
        if (ipcPort == 0) {
            TwoCoreMod.log("[ChatLink] IPC port not set — chat link unavailable on secondary")
            return
        }
        running.set(true)
        TwoCoreMod.log("[ChatLink] Secondary IPC -> localhost:$ipcPort")
        startConnectLoop()
    }

    fun stop() {
        running.set(false)
        writerRef.get()?.close()
        writerRef.set(null)
    }

    fun isConnected(): Boolean = writerRef.get() != null

    private fun send(raw: String) {
        val w = writerRef.get() ?: return
        try {
            w.println(raw)
            if (w.checkError()) writerRef.set(null)
        } catch (e: Exception) {
            TwoCoreMod.log("[ChatLink] Send error: ${e.message}")
            writerRef.set(null)
        }
    }

    fun sendChat(playerName: String, message: String) =
        send(packet("t" to "chat", "p" to playerName, "m" to message))

    fun sendJoin(playerName: String) =
        send(packet("t" to "join", "p" to playerName))

    fun sendLeave(playerName: String) =
        send(packet("t" to "leave", "p" to playerName))

    fun sendDeath(playerName: String, deathMessage: String) =
        send(packet("t" to "death", "p" to playerName, "m" to deathMessage))

    /**
     * Request the player list from primary.
     * The callback is invoked on the secondary server thread with a formatted message.
     */
    fun requestPlayerList(callback: (String) -> Unit) {
        val reqId = System.nanoTime().toString()
        pendingPlayerListCallbacks[reqId] = callback
        send(packet("t" to "playerlist_req", "id" to reqId))
    }

    private fun startConnectLoop() {
        Thread(null, {
            while (running.get()) {
                try {
                    val sock   = Socket("127.0.0.1", ipcPort)
                    val writer = PrintWriter(sock.getOutputStream(), true, Charsets.UTF_8)
                    writerRef.set(writer)
                    TwoCoreMod.log("[ChatLink] Connected to primary IPC port $ipcPort")

                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        dispatchIncoming(l)
                    }
                } catch (e: Exception) {
                    if (running.get()) TwoCoreMod.log("[ChatLink] Disconnected from primary: ${e.message}")
                } finally {
                    writerRef.set(null)
                }
                if (running.get()) {
                    TwoCoreMod.log("[ChatLink] Reconnecting in 5s...")
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }, "2Core-IPC-Connect", 0L).also { it.isDaemon = true; it.start() }
    }

    /** Handle a packet from primary — display on secondary or resolve a callback. */
    private fun dispatchIncoming(raw: String) {
        try {
            val json = Json.parseToJsonElement(raw).jsonObject
            val t    = json["t"]?.jsonPrimitive?.content ?: return

            // Player list request from primary — respond with our own player list
            if (t == "playerlist_req") {
                val reqId  = json["id"]?.jsonPrimitive?.content ?: return
                val server = TwoCoreMod.primaryServer ?: return  // on secondary this is also primaryServer
                server.execute {
                    val players = server.playerList.players
                    val names   = players.joinToString(", ") { it.gameProfile.name }
                    val count   = players.size
                    send(packet("t" to "playerlist_res", "id" to reqId, "names" to names, "count" to count.toString()))
                }
                return
            }

            // Player list response from primary (to /2core listother on secondary)
            if (t == "playerlist_res") {
                val reqId    = json["id"]?.jsonPrimitive?.content ?: return
                val callback = pendingPlayerListCallbacks.remove(reqId) ?: return
                val names    = json["names"]?.jsonPrimitive?.content ?: ""
                val count    = json["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val msg = if (count == 0) {
                    "§e[2Core] No players on the main server (1st)."
                } else {
                    "§6[2Core] Players on the main server (1st) §7[§f$count§7]: §f$names"
                }
                callback(msg)
                return
            }

            val server = TwoCoreMod.primaryServer ?: return
            val cfg    = TwoCoreMod.currentConfig ?: return
            if (!cfg.getProperty("chat_link", "false").trim().equals("true", ignoreCase = true)) return

            val player = json["p"]?.jsonPrimitive?.content ?: return

            val text: String = when (t) {
                "chat" -> {
                    val msg = json["m"]?.jsonPrimitive?.content ?: return
                    val template = cfg.getProperty("chat_format_for_secondary", "&7[&61st&7] &f<%player%> %message%")
                    formatMessage(template, player, msg)
                }
                "join" -> {
                    val template = cfg.getProperty("join_message_for_secondary", "&6%player% joined the main server")
                    formatMessage(template, player)
                }
                "leave" -> {
                    val template = cfg.getProperty("leave_message_for_secondary", "&6%player% left the main server")
                    formatMessage(template, player)
                }
                "death" -> {
                    val msg = json["m"]?.jsonPrimitive?.content ?: "$player died"
                    val template = cfg.getProperty("death_format_for_secondary", "&7[&61st&7] &c%message%")
                    formatMessage(template, player, msg)
                }
                else -> return
            }

            server.execute {
                server.playerList.broadcastSystemMessage(Component.literal(text), false)
            }
        } catch (e: Exception) {
            TwoCoreMod.log("[ChatLink] Bad packet from primary: $raw — ${e.message}")
        }
    }
}
