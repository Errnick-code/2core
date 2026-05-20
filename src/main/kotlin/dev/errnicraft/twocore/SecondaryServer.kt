package dev.errnicraft.twocore

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// SecondaryServer
//
// Manages the external JVM process of the second server.
//
// Methods:
//   launch()           — starts stdout reader and process monitor threads
//   stop()             — graceful shutdown (stop command -> destroy -> destroyForcibly)
//   sendCommand(cmd)   — sends a command to secondary's stdin (for /2core cmd)
//   setConsoleRelay(b) — enable/disable relaying secondary output to primary console
//   isRelayingConsole()— current relay state
//   isRunning()        — whether the process is alive
//   uptimeSeconds()    — uptime in seconds
//
// stdout of secondary is always read (written to logs/console.log).
// Relaying to primary console is controlled by the relayConsole flag.
// ─────────────────────────────────────────────────────────────────────────────

class SecondaryServer(
    val workDir:         File,
    val externalProcess: Process,
    relayConsole:        Boolean = false
) {
    private val startTime    = AtomicLong(System.currentTimeMillis())
    private val relayEnabled = AtomicBoolean(relayConsole)

    // stdin writer for sending commands
    private val stdinWriter: PrintWriter = PrintWriter(externalProcess.outputStream, true)

    fun isRunning(): Boolean = externalProcess.isAlive

    fun uptimeSeconds(): Long {
        val t = startTime.get()
        return if (t == 0L) 0L else (System.currentTimeMillis() - t) / 1000L
    }

    fun isRelayingConsole(): Boolean = relayEnabled.get()

    fun setConsoleRelay(enabled: Boolean) {
        relayEnabled.set(enabled)
        TwoCoreMod.log("[ExternalJVM] Console relay: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    // ── sendCommand ────────────────────────────────────────────────────────
    /**
     * Sends a command to the secondary server's stdin.
     * Minecraft server reads stdin and executes commands without a slash.
     * Returns false if the process has already exited or stdin is unavailable.
     */
    fun sendCommand(command: String): Boolean {
        if (!externalProcess.isAlive) return false
        return try {
            stdinWriter.println(command)
            !stdinWriter.checkError()
        } catch (e: Exception) {
            TwoCoreMod.log("[ExternalJVM] Failed to send command: ${e.message}")
            false
        }
    }

    // ── launch ─────────────────────────────────────────────────────────────

    fun launch() {
        TwoCoreMod.log("[ExternalJVM] Process started, PID: ${externalProcess.pid()}")
        startOutputReader()
        startProcessWatcher()
    }

    // ── stop ───────────────────────────────────────────────────────────────

    fun stop() {
        if (!externalProcess.isAlive) return
        TwoCoreMod.log("[ExternalJVM] Stopping secondary (PID ${externalProcess.pid()})...")

        // Graceful: send "stop" to stdin
        try {
            stdinWriter.println("stop")
        } catch (_: Exception) {}

        // Wait up to 15 seconds for graceful shutdown
        externalProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)

        if (externalProcess.isAlive) {
            TwoCoreMod.log("[ExternalJVM] Process did not stop — calling destroy()...")
            externalProcess.destroy()
            externalProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        if (externalProcess.isAlive) {
            TwoCoreMod.log("[ExternalJVM] Force-killing process (destroyForcibly)...")
            externalProcess.destroyForcibly()
        }

        startTime.set(0L)
        TwoCoreMod.log("[ExternalJVM] Process terminated.")
    }

    // ── startOutputReader ──────────────────────────────────────────────────
    /**
     * Reads stdout (+ stderr via redirectErrorStream=true) of the secondary process.
     *
     * Each line:
     *   1. Written to 2core/logs/console.log (always)
     *   2. If relayEnabled=true — also printed to primary System.out with [2nd] prefix
     *
     * Daemon thread — does not block primary JVM shutdown.
     */
    private fun startOutputReader() {
        val logFile = File(workDir, "logs/console.log")
        logFile.parentFile.mkdirs()

        Thread(null, {
            try {
                val logOut = PrintStream(FileOutputStream(logFile, true), true, Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(externalProcess.inputStream, Charsets.UTF_8))

                logOut.println("=== 2Core Secondary Server log started ===")

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    // Write to secondary log file
                    logOut.println(l)
                    // Relay to primary console if enabled
                    if (relayEnabled.get()) {
                        System.out.println("[2nd] $l")
                    }
                }

                logOut.println("=== 2Core Secondary Server log ended ===")
                logOut.flush()
                logOut.close()
            } catch (e: Exception) {
                TwoCoreMod.log("[ExternalJVM] Error reading secondary output: ${e.message}")
            }
        }, "2Core-OutputReader", 0L).also { it.isDaemon = true; it.start() }
    }

    // ── startProcessWatcher ────────────────────────────────────────────────
    /**
     * Monitors the lifecycle of the secondary process.
     * When the process exits (normally or killed) — logs it and resets startTime.
     */
    private fun startProcessWatcher() {
        // Exit watcher
        Thread(null, {
            try {
                externalProcess.waitFor()
                val code = externalProcess.exitValue()
                TwoCoreMod.log("[ExternalJVM] Process exited with code $code")
                startTime.set(0L)
            } catch (_: InterruptedException) {}
        }, "2Core-ProcessWatcher", 0L).also { it.isDaemon = true; it.start() }


    }
}
