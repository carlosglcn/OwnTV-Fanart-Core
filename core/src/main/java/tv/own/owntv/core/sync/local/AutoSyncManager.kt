package tv.own.owntv.core.sync.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionServerState

/**
 * glcn: keeps two paired devices (the TV and the phone) in step by itself, with no screen to open.
 *
 * While the app is in front, each device answers to the devices it is already paired with and, at
 * start and every few minutes, pulls whatever the other one has newer — favourites, history,
 * continue-watching and manual order. Both do the same, so whichever was used last effectively hands
 * its data to the older one; the merge itself is the existing one (the newest record wins, and a
 * deletion is not undone), so pulling is always safe in either direction.
 *
 * Deliberately narrow: playlists, their logins and device settings never travel here — those still
 * need the user's confirmation on the sync screen. Only paired devices (they hold a secret) can read
 * anything, and the listener runs only while the app is open.
 */
class AutoSyncManager(
    context: Context,
    private val sync: LocalSyncManager,
    private val paired: PairedDeviceStore,
    private val discovery: LocalSyncDiscovery,
) {
    private val prefs = context.applicationContext.getSharedPreferences("glcn_autosync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var foreground = false
    @Volatile private var hostingByUs = false
    private var loop: Job? = null

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) release() else if (foreground) onForeground()
        }

    /** The app came to the front: start answering and pulling. Safe to call repeatedly. */
    fun onForeground() {
        foreground = true
        if (!enabled || loop?.isActive == true) return
        loop = scope.launch {
            // Let the app settle first: nothing here competes with the first screen.
            delay(START_DELAY_MS)
            while (foreground && enabled) {
                runCatching { cycle() }.onFailure { Log.w(TAG, "Auto sync cycle failed", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    /** The app left the front: stop pulling at once, stop answering after a short grace. */
    fun onBackground() {
        foreground = false
        loop?.cancel()
        loop = null
        scope.launch {
            delay(GRACE_MS)
            if (!foreground) release()
        }
    }

    private fun release() {
        if (hostingByUs) {
            hostingByUs = false
            sync.stopHosting()
        }
    }

    private suspend fun host(): Boolean =
        sync.startHosting(sections = SECTIONS).isSuccess.also { hostingByUs = it }

    private suspend fun cycle() {
        val devices = paired.current()
        if (devices.isEmpty()) return
        if (!mutex.tryLock()) return
        try {
            // Another companion flow (a manual sync, a remote form) owns the listener: not ours to take.
            if (!hostingByUs && sync.hostState.firstOrNull().let { it != null && it !is CompanionServerState.Idle }) return
            if (!host()) return
            var changed = false
            for (device in devices) {
                val target = resolve(device)
                val payload = sync.fetch(target, SECTIONS).getOrNull() ?: continue
                try {
                    if (!payload.preview.isEmpty) {
                        sync.apply(payload.file, SECTIONS, payload.password).onSuccess { changed = true }
                    }
                } finally {
                    runCatching { payload.file.delete() }
                }
            }
            sync.clearProgress()
            // What was just received is now ours to hand on: answer with the merged state.
            if (changed && hostingByUs) host()
        } finally {
            mutex.unlock()
        }
    }

    /** The paired devices, for a screen that lets the user pick where to send something. */
    val devices = paired.devices

    /**
     * Sends [command] (a link to open) to [target], or to the first paired device that answers.
     * The device is looked up on the network first, so a changed address does not matter.
     */
    suspend fun sendCommand(command: String, target: PairedDevice? = null): Result<PairedDevice> {
        val candidates = target?.let { listOf(it) } ?: paired.current()
        if (candidates.isEmpty()) return Result.failure(IllegalStateException("not paired"))
        var last: Throwable? = null
        for (device in candidates) {
            val where = resolve(device)
            val r = sync.sendCommand(where, command)
            if (r.isSuccess) {
                paired.touch(where.id, where.address, where.port, device.lastSyncAt)
                return Result.success(where)
            }
            last = r.exceptionOrNull()
        }
        return Result.failure(last ?: IllegalStateException("unreachable"))
    }

    /** glcn: where the last quick command went — a remote's keys go straight there, without looking it up each time. */
    @Volatile private var quickTarget: PairedDevice? = null

    /**
     * glcn: the phone as a remote — [command] to the device that took the last one, straight to its address; only
     * when that fails is the network searched again ([sendCommand]).
     */
    suspend fun sendQuick(command: String): Result<PairedDevice> {
        quickTarget?.let { t -> if (sync.sendCommand(t, command).isSuccess) return Result.success(t) }
        return sendCommand(command).onSuccess { quickTarget = it }
    }

    /** [sendCommand] that outlives the screen that asked for it; [onDone] runs on the main thread. */
    fun sendCommandInBackground(command: String, onDone: (Result<PairedDevice>) -> Unit) {
        scope.launch {
            val result = sendCommand(command)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** The device at its current address when it can be found on the network, else as last seen. */
    private suspend fun resolve(device: PairedDevice): PairedDevice {
        val found = withTimeoutOrNull(DISCOVER_MS) {
            var hit: DiscoveredDevice? = null
            discovery.discover().firstOrNull { d ->
                (d.deviceId == device.id).also { if (it) hit = d }
            }
            hit
        }
        return if (found != null) device.copy(address = found.address, port = found.port) else device
    }

    companion object {
        private const val TAG = "AutoSync"
        private const val KEY_ENABLED = "enabled"
        private const val START_DELAY_MS = 8_000L
        private const val INTERVAL_MS = 5 * 60_000L
        private const val GRACE_MS = 2 * 60_000L
        private const val DISCOVER_MS = 2_500L

        /** User data only — never playlists, logins or hardware settings. */
        val SECTIONS = setOf(
            BackupManager.Section.FAVORITES,
            BackupManager.Section.HISTORY,
            BackupManager.Section.RESUME,
            BackupManager.Section.MANUAL_REORDER,
        )
    }
}
