/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.vpn

import app.opentv.core.SecretStore
import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Built-in VPN, using WireGuard — the protocol every mainstream provider (Mullvad, Proton, Nord,
 * Surfshark, IVPN, Windscribe…) offers as a downloadable config. The user either types the
 * provider's values into the form or imports the provider's `.conf` file; we turn that into a
 * wg-quick config and hand it to WireGuard's own userspace backend ([GoBackend]), which runs it
 * through Android's VpnService. No server of ours is involved — the tunnel goes straight from the
 * box to the user's VPN provider.
 *
 * By default only OpenTV's own traffic goes through the tunnel ([VpnProfile.onlyThisApp]), so the
 * rest of the box (Play Store, other apps) is untouched; the user can widen it to the whole device.
 */
class VpnController private constructor(context: Context) {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    /** One provider shortcut in the form; [dns] is that provider's in-tunnel resolver, pre-filled for the user. */
    data class Provider(val id: String, val name: String, val dns: String)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("opentv_vpn", Context.MODE_PRIVATE)
    private val backend by lazy { GoBackend(app) }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<VpnProfile> = _profile.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = "opentv"
        override fun onStateChange(newState: Tunnel.State) {
            _status.value = if (newState == Tunnel.State.UP) Status.CONNECTED else Status.DISCONNECTED
        }
    }

    /**
     * Kill switch: with it on, nothing streams, records or uploads unless the tunnel is up. If the
     * VPN drops mid-programme the player stops instead of silently carrying on unprotected.
     */
    fun isBlocked(): Boolean {
        val p = _profile.value
        return p.killSwitch && p.isComplete && _status.value != Status.CONNECTED
    }

    /** True once the user has accepted Android's one-time "allow VPN connection" prompt. */
    fun isAuthorized(): Boolean = VpnService.prepare(app) == null

    fun save(profile: VpnProfile) {
        _profile.value = profile
        prefs.edit()
            .putString("provider", profile.provider)
            .also { SecretStore.put(it, "privateKey", profile.privateKey) }
            .putString("address", profile.address)
            .putString("dns", profile.dns)
            .putString("publicKey", profile.publicKey)
            .also { SecretStore.put(it, "presharedKey", profile.presharedKey) }
            .putString("endpoint", profile.endpoint)
            .putString("allowedIps", profile.allowedIps)
            .putBoolean("onlyThisApp", profile.onlyThisApp)
            .putBoolean("autoConnect", profile.autoConnect)
            .putBoolean("killSwitch", profile.killSwitch)
            .apply()
    }

    private fun read() = VpnProfile(
        provider = prefs.getString("provider", "mullvad") ?: "mullvad",
        privateKey = SecretStore.get(prefs, "privateKey"),
        address = prefs.getString("address", "") ?: "",
        dns = prefs.getString("dns", "") ?: "",
        publicKey = prefs.getString("publicKey", "") ?: "",
        presharedKey = SecretStore.get(prefs, "presharedKey"),
        endpoint = prefs.getString("endpoint", "") ?: "",
        allowedIps = prefs.getString("allowedIps", DEFAULT_ALLOWED) ?: DEFAULT_ALLOWED,
        onlyThisApp = prefs.getBoolean("onlyThisApp", true),
        autoConnect = prefs.getBoolean("autoConnect", false),
        killSwitch = prefs.getBoolean("killSwitch", false),
    )

    /** The profile as a wg-quick config — the format WireGuard's own parser validates for us. */
    fun toConfigText(p: VpnProfile): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${p.privateKey.trim()}")
        appendLine("Address = ${p.address.trim()}")
        if (p.dns.isNotBlank()) appendLine("DNS = ${p.dns.trim()}")
        if (p.onlyThisApp) appendLine("IncludedApplications = ${app.packageName}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${p.publicKey.trim()}")
        if (p.presharedKey.isNotBlank()) appendLine("PresharedKey = ${p.presharedKey.trim()}")
        appendLine("Endpoint = ${p.endpoint.trim()}")
        appendLine("AllowedIPs = ${p.allowedIps.trim().ifBlank { DEFAULT_ALLOWED }}")
        appendLine("PersistentKeepalive = 25")
    }

    /** Checks a profile without connecting; returns the parser's complaint, or null when it's valid. */
    fun validate(p: VpnProfile): String? =
        runCatching { Config.parse(ByteArrayInputStream(toConfigText(p).toByteArray())) }
            .exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    suspend fun connect(p: VpnProfile = _profile.value) = withContext(Dispatchers.IO) {
        _error.value = null
        _status.value = Status.CONNECTING
        try {
            val config = Config.parse(ByteArrayInputStream(toConfigText(p).toByteArray()))
            backend.setState(tunnel, Tunnel.State.UP, config)
            _status.value = Status.CONNECTED
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
            _status.value = Status.ERROR
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        _status.value = Status.DISCONNECTED
    }

    /** Called once at app start: reconnects when the user asked for it and already allowed VPN use. */
    suspend fun autoConnectIfWanted() {
        val p = _profile.value
        if (p.autoConnect && p.isComplete && isAuthorized()) connect(p)
    }

    companion object {
        const val DEFAULT_ALLOWED = "0.0.0.0/0, ::/0"

        /** Shortcuts for the providers people actually use; each pre-fills that provider's DNS. */
        val PROVIDERS = listOf(
            Provider("mullvad", "Mullvad", "10.64.0.1"),
            Provider("proton", "Proton VPN", "10.2.0.1"),
            Provider("nord", "NordVPN", "103.86.96.100, 103.86.99.100"),
            Provider("surfshark", "Surfshark", "162.252.172.57, 149.154.159.92"),
            Provider("ivpn", "IVPN", "172.16.0.1"),
            Provider("windscribe", "Windscribe", "10.255.255.3"),
            Provider("custom", "WireGuard", ""),
        )

        @Volatile private var instance: VpnController? = null

        fun get(context: Context): VpnController =
            instance ?: synchronized(this) { instance ?: VpnController(context).also { instance = it } }

        /**
         * Reads a provider's `.conf` (wg-quick format) into a profile, keeping the user's own
         * app-scope and auto-connect choices. Unknown keys are ignored; returns null if the text
         * has no key pair at all, so a wrong file doesn't silently wipe the form.
         */
        fun parseConfig(text: String, base: VpnProfile): VpnProfile? {
            val values = mutableMapOf<String, String>()
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { '=' in it && !it.startsWith("[") }
                .forEach { line ->
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    // Base64 keys end in '=' — substringAfter on the FIRST '=' keeps them intact.
                    values[key] = value
                }
            if (values["privatekey"].isNullOrBlank() && values["publickey"].isNullOrBlank()) return null
            return base.copy(
                privateKey = values["privatekey"] ?: base.privateKey,
                address = values["address"] ?: base.address,
                dns = values["dns"] ?: base.dns,
                publicKey = values["publickey"] ?: base.publicKey,
                presharedKey = values["presharedkey"] ?: "",
                endpoint = values["endpoint"] ?: base.endpoint,
                allowedIps = values["allowedips"] ?: base.allowedIps,
            )
        }
    }
}

/**
 * Everything one WireGuard connection needs. Stored in the app's private preferences — the box's
 * app sandbox, readable by OpenTV alone. The private key never leaves the device.
 */
data class VpnProfile(
    val provider: String = "mullvad",
    val privateKey: String = "",
    val address: String = "",
    val dns: String = "",
    val publicKey: String = "",
    val presharedKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = VpnController.DEFAULT_ALLOWED,
    val onlyThisApp: Boolean = true,
    val autoConnect: Boolean = false,
    val killSwitch: Boolean = false,
) {
    val isComplete: Boolean
        get() = privateKey.isNotBlank() && address.isNotBlank() && publicKey.isNotBlank() && endpoint.isNotBlank()
}
