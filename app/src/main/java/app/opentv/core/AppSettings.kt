/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import app.opentv.data.model.StremioAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The handful of user preferences that are not "data" (sources, guides) but "how the app
 * behaves". Backed by [android.content.SharedPreferences] rather than DataStore on purpose:
 * three flags read at composition time do not need an async, Flow-based store and the
 * ceremony that comes with it. Each setting is also mirrored into a [StateFlow] so Compose
 * recomposes the moment one changes.
 */
class AppSettings private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    private val addonJson = Json { ignoreUnknownKeys = true }

    /** How the app chooses light vs dark. TV defaults to dark under [ThemeMode.SYSTEM]. */
    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * How the live-TV channel list is laid out: the scrolling EPG time-[ChannelLayout.GRID], or a
     * compact one-channel-per-row [ChannelLayout.LIST] (logo, name, now/next) for people who find
     * the full grid busy. Defaults to the grid.
     */
    enum class ChannelLayout { GRID, LIST }

    private val _channelLayout = MutableStateFlow(readChannelLayout())
    val channelLayout: StateFlow<ChannelLayout> = _channelLayout.asStateFlow()

    /** Whether embedded subtitles/closed captions are shown when a stream carries them. */
    private val _subtitlesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SUBTITLES, true))
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    /** Whether the selected channel plays live inside the guide's preview pane. */
    private val _guidePreviewVideo = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_VIDEO, true))
    val guidePreviewVideo: StateFlow<Boolean> = _guidePreviewVideo.asStateFlow()

    /** Whether the guide preview plays sound (off by default — quieter while browsing). */
    private val _guidePreviewSound = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_SOUND, false))
    val guidePreviewSound: StateFlow<Boolean> = _guidePreviewSound.asStateFlow()

    /** The profile whose watch history is active. Defaults to the built-in profile (id 1). */
    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE, 1L))
    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    fun setActiveProfile(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_PROFILE, id).apply()
        _activeProfileId.value = id
    }

    // ---- Parental controls -------------------------------------------------------------------

    /** Whether a parental PIN is set. The PIN itself is only ever stored as a salted hash. */
    private val _pinIsSet = MutableStateFlow(prefs.getString(KEY_PIN_HASH, null) != null)
    val pinIsSet: StateFlow<Boolean> = _pinIsSet.asStateFlow()

    /** Category group keys the user has marked adult/hidden. */
    private val _hiddenCategories =
        MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_CATS, emptySet())!!.toSet())
    val hiddenCategories: StateFlow<Set<String>> = _hiddenCategories.asStateFlow()

    /**
     * Session unlock. Deliberately *not* persisted: revealing hidden categories lasts until the
     * app is next launched, so a child restarting the app is back behind the lock.
     */
    private val _hiddenUnlocked = MutableStateFlow(false)
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked.asStateFlow()

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        _pinIsSet.value = true
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
        _pinIsSet.value = false
        _hiddenUnlocked.value = true
    }

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return true
        return stored == hashPin(pin)
    }

    fun setHiddenCategories(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_CATS, keys).apply()
        _hiddenCategories.value = keys.toSet()
    }

    /** Settings, VPN, storage, channel order and phone remote behind the parental PIN. */
    private val _settingsLocked = MutableStateFlow(prefs.getBoolean(KEY_SETTINGS_LOCK, false))
    val settingsLocked: StateFlow<Boolean> = _settingsLocked.asStateFlow()
    fun setSettingsLocked(v: Boolean) { prefs.edit().putBoolean(KEY_SETTINGS_LOCK, v).apply(); _settingsLocked.value = v }

    /** Session unlock for the settings gate — like [hiddenUnlocked], gone at the next app start. */
    private val _settingsUnlocked = MutableStateFlow(false)
    val settingsUnlocked: StateFlow<Boolean> = _settingsUnlocked.asStateFlow()
    fun setSettingsUnlocked(v: Boolean) { _settingsUnlocked.value = v }

    fun setHiddenUnlocked(unlocked: Boolean) {
        _hiddenUnlocked.value = unlocked
    }

    private fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("opentv-pin::$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setChannelLayout(layout: ChannelLayout) {
        prefs.edit().putString(KEY_CHANNEL_LAYOUT, layout.name).apply()
        _channelLayout.value = layout
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTITLES, enabled).apply()
        _subtitlesEnabled.value = enabled
    }

    fun setGuidePreviewVideo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_VIDEO, enabled).apply()
        _guidePreviewVideo.value = enabled
    }

    fun setGuidePreviewSound(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_SOUND, enabled).apply()
        _guidePreviewSound.value = enabled
    }

    /** Whether launching the app jumps straight back to the last channel you watched. */
    private val _resumeLastChannel = MutableStateFlow(prefs.getBoolean(KEY_RESUME_LAST, false))
    val resumeLastChannel: StateFlow<Boolean> = _resumeLastChannel.asStateFlow()

    fun setResumeLastChannel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_LAST, enabled).apply()
        _resumeLastChannel.value = enabled
    }

    // ---- Recording behaviour -----------------------------------------------------------------

    /** Minutes to start a scheduled recording early, so a late start isn't clipped. */
    private val _recordPadStartMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_START, 1))
    val recordPadStartMinutes: StateFlow<Int> = _recordPadStartMinutes.asStateFlow()

    /** Minutes to keep recording past the listed end, so an overrun (sport/news) isn't cut off. */
    private val _recordPadEndMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_END, 5))
    val recordPadEndMinutes: StateFlow<Int> = _recordPadEndMinutes.asStateFlow()

    fun setRecordPadding(startMinutes: Int, endMinutes: Int) {
        val s = startMinutes.coerceIn(0, 30)
        val e = endMinutes.coerceIn(0, 60)
        prefs.edit().putInt(KEY_PAD_START, s).putInt(KEY_PAD_END, e).apply()
        _recordPadStartMinutes.value = s
        _recordPadEndMinutes.value = e
    }

    /**
     * When a scheduled recording starts, switch the box to it (playing from the growing file, so no
     * extra provider connection). Essential on a single-connection provider, where recording one
     * channel while another plays gets one of them cut. Default on.
     */
    private val _recordAutoSwitch = MutableStateFlow(prefs.getBoolean(KEY_REC_AUTOSWITCH, true))
    val recordAutoSwitch: StateFlow<Boolean> = _recordAutoSwitch.asStateFlow()

    fun setRecordAutoSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REC_AUTOSWITCH, enabled).apply()
        _recordAutoSwitch.value = enabled
    }

    /**
     * Pause & rewind live TV via a rolling on-disk buffer of the current channel. Experimental and
     * heavier on weak boxes, so it's opt-in (off by default); when off, playback is unchanged.
     */
    private val _livePauseEnabled = MutableStateFlow(prefs.getBoolean(KEY_LIVE_PAUSE, false))
    val livePauseEnabled: StateFlow<Boolean> = _livePauseEnabled.asStateFlow()

    fun setLivePauseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_PAUSE, enabled).apply()
        _livePauseEnabled.value = enabled
    }

    // ---- Content types -----------------------------------------------------------------------

    /**
     * Which content types the user wants synced and shown. Turning one off skips fetching that
     * type's catalogue on the next sync (the speed-up) and hides its tab; already-synced rows are
     * left in place, so turning it back on and refreshing brings everything straight back.
     */
    private val _liveEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_LIVE, true))
    val liveEnabled: StateFlow<Boolean> = _liveEnabled.asStateFlow()

    private val _moviesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_MOVIES, true))
    val moviesEnabled: StateFlow<Boolean> = _moviesEnabled.asStateFlow()

    private val _seriesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_SERIES, true))
    val seriesEnabled: StateFlow<Boolean> = _seriesEnabled.asStateFlow()

    fun setLiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTENT_LIVE, enabled).apply()
        _liveEnabled.value = enabled
    }

    fun setMoviesEnabled(enabled: Boolean) {
        // Also reset the VOD freshness stamp so the next Movies/Shows open re-syncs immediately
        // rather than waiting out the cache TTL — turning a content type on should show it now.
        prefs.edit()
            .putBoolean(KEY_CONTENT_MOVIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _moviesEnabled.value = enabled
    }

    fun setSeriesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CONTENT_SERIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _seriesEnabled.value = enabled
    }

    /**
     * UI language override. Blank = follow the device; otherwise a BCP-47 tag ("en", "es").
     * Applied at [android.content.ContextWrapper.attachBaseContext] time so the whole app —
     * including notifications built off the app context — picks it up.
     */
    private val _languageTag = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "").orEmpty())
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
        _languageTag.value = tag
    }

    /** The last channel played, for boot-to-last-channel. Not a flow — only read once at launch. */
    var lastChannelId: Long
        get() = prefs.getLong(KEY_LAST_CHANNEL, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHANNEL, value).apply() }

    /**
     * Video scaling in the player, as an [androidx.media3.ui.AspectRatioFrameLayout] RESIZE_MODE_*
     * constant (0 = Fit). Persisted so the choice survives leaving the player, which testers asked
     * for — picking Fill every single time you open a channel gets old fast.
     */
    private val _playerResizeMode = MutableStateFlow(prefs.getInt(KEY_RESIZE_MODE, 0))
    val playerResizeMode: StateFlow<Int> = _playerResizeMode.asStateFlow()

    fun setPlayerResizeMode(mode: Int) {
        prefs.edit().putInt(KEY_RESIZE_MODE, mode).apply()
        _playerResizeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)

    private fun readChannelLayout(): ChannelLayout =
        runCatching { ChannelLayout.valueOf(prefs.getString(KEY_CHANNEL_LAYOUT, null) ?: "") }
            .getOrDefault(ChannelLayout.LIST)

    // ---- Recording ---------------------------------------------------------------------------

    /**
     * Where recordings are written: this box's internal storage, a NAS over SMB, or a plugged-in
     * USB / external drive addressed through the Storage Access Framework (a granted tree URI —
     * the only way to write removable storage on modern Android without a raw filesystem path).
     */
    enum class RecordingTarget { INTERNAL, SMB, USB, CLOUD }

    private val _recordingTarget = MutableStateFlow(readRecordingTarget())
    val recordingTarget: StateFlow<RecordingTarget> = _recordingTarget.asStateFlow()

    /**
     * The SAF tree URI the user granted for USB recordings (a `content://` document-tree URI), or
     * null if none has been picked. Persisted as a string; a matching persistable permission is
     * taken when it is chosen, so the grant survives restarts.
     */
    private val _usbTreeUri = MutableStateFlow(prefs.getString(KEY_USB_TREE, null))
    val usbTreeUri: StateFlow<String?> = _usbTreeUri.asStateFlow()

    /** A friendly name for the chosen USB folder (e.g. the DocumentFile's name), for display. */
    private val _usbFolderLabel = MutableStateFlow(prefs.getString(KEY_USB_LABEL, null))
    val usbFolderLabel: StateFlow<String?> = _usbFolderLabel.asStateFlow()

    /** Save (or clear, with null) the granted USB tree URI and its display label together. */
    fun setUsbTree(treeUri: String?, label: String?) {
        prefs.edit()
            .putString(KEY_USB_TREE, treeUri)
            .putString(KEY_USB_LABEL, label)
            .apply()
        _usbTreeUri.value = treeUri
        _usbFolderLabel.value = label
    }

    /** SMB / NAS connection. Stored on-device only, exactly like provider credentials. */
    private val _smbHost = MutableStateFlow(prefs.getString(KEY_SMB_HOST, "").orEmpty())
    val smbHost: StateFlow<String> = _smbHost.asStateFlow()

    private val _smbShare = MutableStateFlow(prefs.getString(KEY_SMB_SHARE, "").orEmpty())
    val smbShare: StateFlow<String> = _smbShare.asStateFlow()

    /** Sub-folder within the share, e.g. `Recordings`. Blank = share root. */
    private val _smbFolder = MutableStateFlow(prefs.getString(KEY_SMB_FOLDER, "OpenTV").orEmpty())
    val smbFolder: StateFlow<String> = _smbFolder.asStateFlow()

    private val _smbUser = MutableStateFlow(prefs.getString(KEY_SMB_USER, "").orEmpty())
    val smbUser: StateFlow<String> = _smbUser.asStateFlow()

    private val _smbPassword = MutableStateFlow(SecretStore.get(prefs, KEY_SMB_PASS))
    val smbPassword: StateFlow<String> = _smbPassword.asStateFlow()

    fun setRecordingTarget(target: RecordingTarget) {
        prefs.edit().putString(KEY_REC_TARGET, target.name).apply()
        _recordingTarget.value = target
    }

    fun setSmbConfig(host: String, share: String, folder: String, user: String, password: String) {
        prefs.edit()
            .putString(KEY_SMB_HOST, host.trim())
            .putString(KEY_SMB_SHARE, share.trim())
            .putString(KEY_SMB_FOLDER, folder.trim())
            .putString(KEY_SMB_USER, user)
            .also { SecretStore.put(it, KEY_SMB_PASS, password) }
            .apply()
        _smbHost.value = host.trim()
        _smbShare.value = share.trim()
        _smbFolder.value = folder.trim()
        _smbUser.value = user
        _smbPassword.value = password
    }

    /**
     * Online cloud (WebDAV) for recordings — Nextcloud, MagentaCLOUD, pCloud, Koofr, HiDrive, GMX…
     * A recording is captured to a local staging folder first (so it can be watched while it
     * records and never depends on the upload link holding up for hours), then uploaded when it
     * finishes. Credentials live on-device only, like the NAS ones.
     */
    private val _cloudProvider = MutableStateFlow(prefs.getString(KEY_CLOUD_PROVIDER, "nextcloud").orEmpty())
    val cloudProvider: StateFlow<String> = _cloudProvider.asStateFlow()

    private val _cloudUrl = MutableStateFlow(prefs.getString(KEY_CLOUD_URL, "").orEmpty())
    val cloudUrl: StateFlow<String> = _cloudUrl.asStateFlow()

    private val _cloudUser = MutableStateFlow(prefs.getString(KEY_CLOUD_USER, "").orEmpty())
    val cloudUser: StateFlow<String> = _cloudUser.asStateFlow()

    private val _cloudPassword = MutableStateFlow(SecretStore.get(prefs, KEY_CLOUD_PASS))
    val cloudPassword: StateFlow<String> = _cloudPassword.asStateFlow()

    /** Sub-folder in the cloud, created on first upload. */
    private val _cloudFolder = MutableStateFlow(prefs.getString(KEY_CLOUD_FOLDER, "OpenTV").orEmpty())
    val cloudFolder: StateFlow<String> = _cloudFolder.asStateFlow()

    /** Keep the local copy after a successful upload (plays instantly, costs box storage). */
    private val _cloudKeepLocal = MutableStateFlow(prefs.getBoolean(KEY_CLOUD_KEEP_LOCAL, false))
    val cloudKeepLocal: StateFlow<Boolean> = _cloudKeepLocal.asStateFlow()

    fun setCloudConfig(provider: String, url: String, user: String, password: String, folder: String, keepLocal: Boolean) {
        prefs.edit()
            .putString(KEY_CLOUD_PROVIDER, provider)
            .putString(KEY_CLOUD_URL, url.trim())
            .putString(KEY_CLOUD_USER, user.trim())
            .also { SecretStore.put(it, KEY_CLOUD_PASS, password) }
            .putString(KEY_CLOUD_FOLDER, folder.trim())
            .putBoolean(KEY_CLOUD_KEEP_LOCAL, keepLocal)
            .apply()
        _cloudProvider.value = provider
        _cloudUrl.value = url.trim()
        _cloudUser.value = user.trim()
        _cloudPassword.value = password
        _cloudFolder.value = folder.trim()
        _cloudKeepLocal.value = keepLocal
    }

    // ---- "Kanalnummern & Reihenfolge" -------------------------------------------------------

    /** Show the provider's official numbers (LCN). Mirrored into [NumberingPolicy]. */
    private val _lcnNumbers = MutableStateFlow(prefs.getBoolean(KEY_LCN, true))
    val lcnNumbers: StateFlow<Boolean> = _lcnNumbers.asStateFlow()

    /** The user's own numbers override the provider's. Mirrored into [NumberingPolicy]. */
    private val _forceCustomNumbers = MutableStateFlow(prefs.getBoolean(KEY_FORCE_CUSTOM, true))
    val forceCustomNumbers: StateFlow<Boolean> = _forceCustomNumbers.asStateFlow()

    /** Renumber 1…n without gaps whenever the order is saved. */
    private val _closeNumberGaps = MutableStateFlow(prefs.getBoolean(KEY_CLOSE_GAPS, false))
    val closeNumberGaps: StateFlow<Boolean> = _closeNumberGaps.asStateFlow()

    fun setNumbering(lcn: Boolean, forceCustom: Boolean, closeGaps: Boolean) {
        prefs.edit().putBoolean(KEY_LCN, lcn).putBoolean(KEY_FORCE_CUSTOM, forceCustom).putBoolean(KEY_CLOSE_GAPS, closeGaps).apply()
        _lcnNumbers.value = lcn; _forceCustomNumbers.value = forceCustom; _closeNumberGaps.value = closeGaps
        app.opentv.data.model.NumberingPolicy.lcn = lcn
        app.opentv.data.model.NumberingPolicy.forceCustom = forceCustom
    }

    /**
     * Channels behind the parental PIN, keyed `sourceId:streamId` (stable across re-syncs). Like
     * hidden adult categories they drop out of the guide, lists and search until the session is
     * unlocked under Parental controls.
     */
    private val _lockedChannels = MutableStateFlow(prefs.getStringSet(KEY_LOCKED, emptySet())?.toSet() ?: emptySet())
    val lockedChannels: StateFlow<Set<String>> = _lockedChannels.asStateFlow()

    fun setLockedChannels(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_LOCKED, keys).apply()
        _lockedChannels.value = keys
    }

    init {
        app.opentv.recording.SmbClient.requireEncryption = prefs.getBoolean(KEY_SMB_ENCRYPT, true)
        app.opentv.data.model.NumberingPolicy.lcn = _lcnNumbers.value
        app.opentv.data.model.NumberingPolicy.forceCustom = _forceCustomNumbers.value
    }

    // ---- "Speicher & Cloud": sync rules and housekeeping -----------------------------------

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))
    private fun putFlag(key: String, flow: MutableStateFlow<Boolean>, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply(); flow.value = value
    }

    /** Uploads and cloud sync wait for Wi-Fi / Ethernet — never burn mobile data. On by default. */
    private val _syncWifiOnly = flag(KEY_SYNC_WIFI_ONLY, true)
    val syncWifiOnly: StateFlow<Boolean> = _syncWifiOnly.asStateFlow()
    fun setSyncWifiOnly(v: Boolean) = putFlag(KEY_SYNC_WIFI_ONLY, _syncWifiOnly, v)

    /** Finished cloud recordings upload on their own. Off = they wait for "sync now". */
    private val _cloudAutoUpload = flag(KEY_CLOUD_AUTO_UPLOAD, true)
    val cloudAutoUpload: StateFlow<Boolean> = _cloudAutoUpload.asStateFlow()
    fun setCloudAutoUpload(v: Boolean) = putFlag(KEY_CLOUD_AUTO_UPLOAD, _cloudAutoUpload, v)

    /** Keep favourites, watch history and cloud recordings in step across devices via the cloud. */
    private val _cloudLiveSync = flag(KEY_CLOUD_LIVE_SYNC, false)
    val cloudLiveSync: StateFlow<Boolean> = _cloudLiveSync.asStateFlow()
    fun setCloudLiveSync(v: Boolean) = putFlag(KEY_CLOUD_LIVE_SYNC, _cloudLiveSync, v)

    /** Delete the oldest local recordings when free space drops under [cleanupThresholdGb]. */
    private val _autoCleanup = flag(KEY_AUTO_CLEANUP, false)
    val autoCleanup: StateFlow<Boolean> = _autoCleanup.asStateFlow()
    fun setAutoCleanup(v: Boolean) = putFlag(KEY_AUTO_CLEANUP, _autoCleanup, v)
    val cleanupThresholdGb: Int get() = 5

    private val _lastCloudSync = MutableStateFlow(prefs.getLong(KEY_LAST_CLOUD_SYNC, 0L))
    /** When the last sync finished (0 = never) and whether it went through. */
    val lastCloudSync: StateFlow<Long> = _lastCloudSync.asStateFlow()
    private val _lastCloudSyncOk = flag(KEY_LAST_CLOUD_SYNC_OK, true)
    val lastCloudSyncOk: StateFlow<Boolean> = _lastCloudSyncOk.asStateFlow()
    fun markCloudSync(ok: Boolean) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_CLOUD_SYNC, now).putBoolean(KEY_LAST_CLOUD_SYNC_OK, ok).apply()
        _lastCloudSync.value = now
        _lastCloudSyncOk.value = ok
    }

    /**
     * End-to-end encryption of everything uploaded to the cloud, with a password only the user
     * knows (kept Keystore-encrypted here). Same password on every device = same data.
     */
    private val _cloudE2E = MutableStateFlow(prefs.getBoolean(KEY_CLOUD_E2E, false))
    val cloudE2E: StateFlow<Boolean> = _cloudE2E.asStateFlow()
    private val _cloudE2EPassword = MutableStateFlow(SecretStore.get(prefs, KEY_CLOUD_E2E_PASS))
    val cloudE2EPassword: StateFlow<String> = _cloudE2EPassword.asStateFlow()

    fun setCloudE2E(enabled: Boolean, password: String) {
        prefs.edit().putBoolean(KEY_CLOUD_E2E, enabled).also { SecretStore.put(it, KEY_CLOUD_E2E_PASS, password) }.apply()
        _cloudE2E.value = enabled
        _cloudE2EPassword.value = password
    }

    /** Enforce SMB3 encryption + signing to the NAS (mirrored into SmbClient). */
    private val _smbEncrypt = MutableStateFlow(prefs.getBoolean(KEY_SMB_ENCRYPT, true))
    val smbEncrypt: StateFlow<Boolean> = _smbEncrypt.asStateFlow()
    fun setSmbEncrypt(v: Boolean) {
        prefs.edit().putBoolean(KEY_SMB_ENCRYPT, v).apply(); _smbEncrypt.value = v
        app.opentv.recording.SmbClient.requireEncryption = v
    }

    val cloudConfigured: Boolean get() = cloudUrl.value.isNotBlank() && cloudUser.value.isNotBlank()

    fun disconnectCloud() {
        setCloudConfig(cloudProvider.value, "", "", "", cloudFolder.value, cloudKeepLocal.value)
        if (recordingTarget.value == RecordingTarget.CLOUD) setRecordingTarget(RecordingTarget.INTERNAL)
    }

    private fun readRecordingTarget(): RecordingTarget =
        runCatching { RecordingTarget.valueOf(prefs.getString(KEY_REC_TARGET, null) ?: "") }
            .getOrDefault(RecordingTarget.INTERNAL)

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    /**
     * A stable, random id for this install. It names this device's bundle file in the NAS sync
     * folder, so every device writes its own file and reads the others'. Generated once, on first
     * read, then persisted — distinct per device without needing any hardware identifier.
     */
    val syncDeviceId: String
        get() = prefs.getString(KEY_SYNC_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_SYNC_DEVICE_ID, it).apply() }

    /** Whether to run a NAS sync automatically each time the app is opened. Off by default. */
    private val _nasAutoSync = MutableStateFlow(prefs.getBoolean(KEY_NAS_AUTO_SYNC, false))
    val nasAutoSync: StateFlow<Boolean> = _nasAutoSync.asStateFlow()

    fun setNasAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NAS_AUTO_SYNC, enabled).apply()
        _nasAutoSync.value = enabled
    }

    // ---- Catalogue freshness & TMDB ----------------------------------------------------------

    /**
     * When the VOD (movies + series) catalogue was last fetched from the provider, in epoch
     * millis; 0 = never. A provider's 40k-title VOD list is expensive to re-download and
     * re-write, so [app.opentv.ui.VodViewModel.ensureVodLoaded] uses this to skip the sync on a
     * warm launch and show the already-stored rows instantly — the fetch only runs on first load
     * or once this goes stale. Not a flow: it is read once when Movies/Shows is first opened.
     */
    var vodSyncedAtMillis: Long
        get() = prefs.getLong(KEY_VOD_SYNCED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_VOD_SYNCED_AT, value).apply() }

    /**
     * The user's own TMDB API key (v3 auth), stored on-device only exactly like provider
     * credentials, used to fill in artwork/metadata a provider left blank. Blank = the TMDB
     * fallback is off. Each user brings their own key, so no single key carries everyone's traffic.
     */
    private val _tmdbApiKey = MutableStateFlow(prefs.getString(KEY_TMDB_KEY, "").orEmpty())
    val tmdbApiKey: StateFlow<String> = _tmdbApiKey.asStateFlow()

    fun setTmdbApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_TMDB_KEY, trimmed).apply()
        _tmdbApiKey.value = trimmed
    }

    // ---- Stremio add-ons ---------------------------------------------------------------------

    /**
     * The user's Stremio add-ons, stored on-device as JSON. OpenTV ships with none; each entry is a
     * manifest URL the user pasted (a debrid key, if any, is baked into that URL on the add-on's own
     * site — never entered here). Empty = the whole feature stays inert.
     */
    private val _stremioAddons = MutableStateFlow(readStremioAddons())
    val stremioAddons: StateFlow<List<StremioAddon>> = _stremioAddons.asStateFlow()

    fun addStremioAddon(addon: StremioAddon) {
        val deduped = _stremioAddons.value.filterNot { it.manifestUrl.equals(addon.manifestUrl, ignoreCase = true) }
        persistStremioAddons(deduped + addon)
    }

    fun removeStremioAddon(manifestUrl: String) {
        persistStremioAddons(_stremioAddons.value.filterNot { it.manifestUrl == manifestUrl })
    }

    private fun persistStremioAddons(list: List<StremioAddon>) {
        prefs.edit().putString(KEY_STREMIO_ADDONS, addonJson.encodeToString(list)).apply()
        _stremioAddons.value = list
    }

    private fun readStremioAddons(): List<StremioAddon> =
        runCatching {
            prefs.getString(KEY_STREMIO_ADDONS, null)?.let { addonJson.decodeFromString<List<StremioAddon>>(it) }
        }.getOrNull() ?: emptyList()

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CHANNEL_LAYOUT = "channel_layout"
        private const val KEY_SUBTITLES = "subtitles_enabled"
        private const val KEY_PREVIEW_VIDEO = "guide_preview_video"
        private const val KEY_PREVIEW_SOUND = "guide_preview_sound"
        private const val KEY_PIN_HASH = "parental_pin_hash"
        private const val KEY_SETTINGS_LOCK = "settings_pin_lock"
        private const val KEY_HIDDEN_CATS = "hidden_categories"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        private const val KEY_RESUME_LAST = "resume_last_channel"
        private const val KEY_CONTENT_LIVE = "content_live"
        private const val KEY_CONTENT_MOVIES = "content_movies"
        private const val KEY_CONTENT_SERIES = "content_series"
        private const val KEY_LAST_CHANNEL = "last_channel_id"
        private const val KEY_RESIZE_MODE = "player_resize_mode"
        private const val KEY_LANGUAGE = "language_tag"

        /**
         * Reads the saved language tag straight from prefs, for use in attachBaseContext before
         * the settings singleton (or anything else) is initialised. Blank = follow the device.
         */
        fun savedLanguageTag(context: Context): String =
            context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "").orEmpty()
        private const val KEY_REC_TARGET = "recording_target"
        private const val KEY_SMB_HOST = "smb_host"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_FOLDER = "smb_folder"
        private const val KEY_SMB_USER = "smb_user"
        private const val KEY_SMB_PASS = "smb_password"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_LCN = "numbering_lcn"
        private const val KEY_CLOUD_E2E = "cloud_e2e"
        private const val KEY_SMB_ENCRYPT = "smb_encrypt"
        private const val KEY_CLOUD_E2E_PASS = "cloud_e2e_password"
        private const val KEY_FORCE_CUSTOM = "numbering_force_custom"
        private const val KEY_CLOSE_GAPS = "numbering_close_gaps"
        private const val KEY_LOCKED = "locked_channels"
        private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_CLOUD_AUTO_UPLOAD = "cloud_auto_upload"
        private const val KEY_CLOUD_LIVE_SYNC = "cloud_live_sync"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup"
        private const val KEY_LAST_CLOUD_SYNC = "last_cloud_sync"
        private const val KEY_LAST_CLOUD_SYNC_OK = "last_cloud_sync_ok"
        private const val KEY_CLOUD_URL = "cloud_url"
        private const val KEY_CLOUD_USER = "cloud_user"
        private const val KEY_CLOUD_PASS = "cloud_password"
        private const val KEY_CLOUD_FOLDER = "cloud_folder"
        private const val KEY_CLOUD_KEEP_LOCAL = "cloud_keep_local"
        private const val KEY_USB_TREE = "usb_tree_uri"
        private const val KEY_USB_LABEL = "usb_folder_label"
        private const val KEY_SYNC_DEVICE_ID = "sync_device_id"
        private const val KEY_NAS_AUTO_SYNC = "nas_auto_sync"
        private const val KEY_VOD_SYNCED_AT = "vod_synced_at"
        private const val KEY_TMDB_KEY = "tmdb_api_key"
        private const val KEY_STREMIO_ADDONS = "stremio_addons"
        private const val KEY_PAD_START = "rec_pad_start_min"
        private const val KEY_PAD_END = "rec_pad_end_min"
        private const val KEY_REC_AUTOSWITCH = "rec_auto_switch"
        private const val KEY_LIVE_PAUSE = "live_pause_enabled"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
