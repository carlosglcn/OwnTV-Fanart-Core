package tv.own.owntv.player

import android.view.KeyEvent

/**
 * glcn: the last remote presses, for the playback report — which key code each button sends, from which remote,
 * and how long it was held. Remotes differ (some TVs send their own codes, or none for a button), and this lets
 * someone show it without ADB or another app. Memory only; nothing is kept once the app closes.
 */
object KeyLog {

    private data class Press(val atMs: Long, val code: Int, val scan: Int, val device: String, val source: Int, var heldMs: Long = -1, var repeats: Int = 0)

    private val presses = ArrayDeque<Press>()
    private val lock = Any()

    fun onKey(event: KeyEvent) {
        synchronized(lock) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        val dev = event.device
                        val name = dev?.let { d -> "${d.name} (${hex(d.vendorId)}:${hex(d.productId)})" } ?: "?"
                        presses.addLast(Press(System.currentTimeMillis(), event.keyCode, event.scanCode, name, event.source))
                        while (presses.size > MAX) presses.removeFirst()
                    } else {
                        presses.lastOrNull { it.code == event.keyCode && it.heldMs < 0 }?.let { it.repeats = event.repeatCount }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val open = presses.lastOrNull { it.code == event.keyCode && it.heldMs < 0 }
                    if (open != null) open.heldMs = event.eventTime - event.downTime
                    else presses.addLast(Press(System.currentTimeMillis(), event.keyCode, event.scanCode, event.device?.name ?: "?", event.source, event.eventTime - event.downTime))
                }
            }
        }
    }

    /** The report's lines, oldest first; blank when no key was pressed yet. */
    fun snapshot(): String = synchronized(lock) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        presses.joinToString("\n") { p ->
            val name = KeyEvent.keyCodeToString(p.code).removePrefix("KEYCODE_")
            val held = if (p.heldMs >= 0) "${p.heldMs} ms" else "held"
            "${stamp.format(java.util.Date(p.atMs))}  $name (${p.code}) scan=${p.scan} · $held" +
                (if (p.repeats > 0) " · repeats=${p.repeats}" else "") + " · ${p.device} · src=0x${Integer.toHexString(p.source)}"
        }
    }

    private fun hex(v: Int) = String.format(java.util.Locale.ROOT, "%04x", v)

    private const val MAX = 120
}
