/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.opentv.core.SecretStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * "Phone as remote": scan the QR code on the TV once, and the phone's browser becomes a remote —
 * D-pad, OK, back, home, menu, play/pause, channel ±, volume, number keys and text input.
 *
 * - Runs on the home network only, and only while OpenTV is in the foreground.
 * - The pairing (token + 256-bit key) is kept, so the phone can bookmark the page; "pair again"
 *   on the TV issues new ones and every previously paired phone stops working at once.
 * - Every command is sealed with [LanCrypto] (AES-256-CTR + HMAC, key from the QR #fragment): a
 *   neighbour on the same Wi-Fi can neither read what is typed (e.g. a password into a form) nor
 *   inject key presses. Unsealed or forged commands are refused. A counter in each command stops a
 *   captured packet from being replayed.
 * - Commands become real key events dispatched to the app's window, so everything that works with
 *   the TV remote — focus, menus, number zapping, the on-screen keyboard — works from the phone.
 */
class RemoteServer private constructor(context: Context) {

    data class Pairing(val url: String, val host: String, val port: Int)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("opentv_remote", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var activity: WeakReference<Activity>? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var lastCounter = 0L

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    /** Remembered between sessions so a bookmarked phone keeps working. */
    val enabled: Boolean get() = prefs.getBoolean("enabled", true)

    fun setEnabled(value: Boolean, host: Activity) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (value) start(host) else stop()
    }

    private fun token(): String = SecretStore.get(prefs, "token").ifEmpty {
        val t = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        SecretStore.put(prefs.edit(), "token", t).commit(); t
    }

    private fun key(): ByteArray {
        val hex = SecretStore.get(prefs, "key").ifEmpty {
            val k = LanCrypto.hex(LanCrypto.newKey())
            SecretStore.put(prefs.edit(), "key", k).commit(); k
        }
        return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** New token + key + port: every previously paired phone is cut off. */
    fun repair(host: Activity) {
        prefs.edit().remove("token").remove("key").remove("port").apply()
        lastCounter = 0
        stop()
        start(host)
    }

    @Synchronized
    fun start(host: Activity) {
        activity = WeakReference(host)
        if (!enabled || server != null) return
        val ip = lanAddress() ?: run { _pairing.value = null; return }
        val socket = runCatching { ServerSocket(prefs.getInt("port", 0)) }
            .recoverCatching { ServerSocket(0) }
            .getOrElse { Log.w(TAG, "Remote server could not start", it); return }
        prefs.edit().putInt("port", socket.localPort).apply()
        server = socket
        val key = key()
        val token = token()
        _pairing.value = Pairing("http://$ip:${socket.localPort}/r?t=$token#k=${LanCrypto.hex(key)}", ip, socket.localPort)
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ runCatching { client.use { handle(it, token, key) } } }, "opentv-remote-client").start()
            }
        }, "opentv-remote").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        _pairing.value = null
    }

    private fun handle(client: Socket, token: String, key: ByteArray) {
        client.soTimeout = 15_000
        val out = client.getOutputStream()
        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            out.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only.")); return
        }
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val (method, path, query) = PairingHttp.parseRequestLine(reader.readLine() ?: return) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (n, v) -> headers[n] = v }
        }
        if (!PairingHttp.secretsMatch(query["t"].orEmpty(), token)) {
            out.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Scan the QR code on the TV again.")); return
        }
        when {
            method == "GET" && path == "/r" ->
                out.write(PairingHttp.httpResponse("200 OK", "text/html", RemotePage.html()))
            method == "POST" && path == "/cmd" -> {
                val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 8192)
                val buf = CharArray(len)
                var read = 0
                while (read < len) { val n = reader.read(buf, read, len - read); if (n < 0) break; read += n }
                val plain = LanCrypto.open(String(buf, 0, read), key)
                if (plain == null) {
                    out.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Bad seal.")); return
                }
                val json = JSONObject(plain)
                // Replay guard: the phone counts up from the page load's timestamp.
                val counter = json.optLong("n", 0)
                if (counter <= lastCounter) {
                    out.write(PairingHttp.httpResponse("409 Conflict", "text/plain", "Stale.")); return
                }
                lastCounter = counter
                execute(json)
                out.write(PairingHttp.httpResponse("200 OK", "application/json", "{\"ok\":true}"))
            }
            else -> out.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
        }
    }

    private fun execute(cmd: JSONObject) {
        cmd.optString("text").takeIf { it.isNotEmpty() }?.let { typeText(it); return }
        when (val k = cmd.optString("k")) {
            "volup", "voldown", "mute" -> {
                val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val dir = when (k) { "volup" -> AudioManager.ADJUST_RAISE; "voldown" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            }
            else -> KEYS[k]?.let { press(it, long = cmd.optBoolean("long")) }
        }
    }

    /** Dispatches a real key press into the foreground window — indistinguishable from the remote. */
    private fun press(code: Int, long: Boolean = false) = main.post {
        val act = activity?.get() ?: return@post
        val now = SystemClock.uptimeMillis()
        act.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        if (long) {
            // A held key: Android's long-press flag after the system timeout, then the release.
            main.postDelayed({
                act.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
                act.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            }, 600)
        } else {
            act.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        }
    }

    /** Text from the phone's keyboard, typed into whatever field has focus on the TV. */
    private fun typeText(text: String) = main.post {
        val act = activity?.get() ?: return@post
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        events?.forEach { act.dispatchKeyEvent(it) }
    }

    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                // Never advertise the VPN tunnel's address — the phone can't reach it.
                if (nic.name.startsWith("tun") || nic.name.startsWith("wg")) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress || address.hostAddress?.contains(':') == true) continue
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }
        return candidates.sortedBy { it.hostAddress?.startsWith("169.254") == true }.firstOrNull()?.hostAddress
    }

    companion object {
        private const val TAG = "RemoteServer"
        private val KEYS = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
            "home" to KeyEvent.KEYCODE_HOME, "menu" to KeyEvent.KEYCODE_MENU,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
            "rew" to KeyEvent.KEYCODE_MEDIA_REWIND, "ff" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            "rec" to KeyEvent.KEYCODE_MEDIA_RECORD, "info" to KeyEvent.KEYCODE_INFO,
            "guide" to KeyEvent.KEYCODE_GUIDE,
            "chup" to KeyEvent.KEYCODE_CHANNEL_UP, "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "del" to KeyEvent.KEYCODE_DEL, "enter" to KeyEvent.KEYCODE_ENTER,
        ) + (0..9).associate { "$it" to (KeyEvent.KEYCODE_0 + it) }

        @Volatile private var instance: RemoteServer? = null
        fun get(context: Context): RemoteServer =
            instance ?: synchronized(this) { instance ?: RemoteServer(context).also { instance = it } }
    }
}
