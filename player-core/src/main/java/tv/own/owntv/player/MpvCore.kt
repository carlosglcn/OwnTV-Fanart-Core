package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib

/**
 * The libmpv calls [OwnTVPlayer] makes on one mpv core. The real one ([LibMpvCore]) passes each call
 * straight to the engine library; a unit test supplies its own, so the player's handling of mpv's
 * events can be driven without the native library.
 */
internal interface MpvCore {
    fun init()
    fun destroy()
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun command(cmd: Array<String>)
    fun setOptionString(name: String, value: String): Int
    fun getPropertyInt(property: String): Int?
    fun setPropertyInt(property: String, value: Int)
    fun getPropertyDouble(property: String): Double?
    fun setPropertyDouble(property: String, value: Double)
    fun getPropertyBoolean(property: String): Boolean?
    fun setPropertyBoolean(property: String, value: Boolean)
    fun getPropertyString(property: String): String?
    fun setPropertyString(property: String, value: String)
    fun observeProperty(property: String, format: Int)
    fun addObserver(o: MPVLib.EventObserver)
    fun removeObserver(o: MPVLib.EventObserver)
    fun addLogObserver(o: MPVLib.LogObserver)
    fun removeLogObserver(o: MPVLib.LogObserver)
}

/** [MpvCore] on the engine library, one call to one call. */
internal class LibMpvCore private constructor(private val lib: MPVLib) : MpvCore {
    override fun init() = lib.init()
    override fun destroy() = lib.destroy()
    override fun attachSurface(surface: Surface) = lib.attachSurface(surface)
    override fun detachSurface() = lib.detachSurface()
    override fun command(cmd: Array<String>) = lib.command(cmd)
    override fun setOptionString(name: String, value: String): Int = lib.setOptionString(name, value)
    override fun getPropertyInt(property: String): Int? = lib.getPropertyInt(property)
    override fun setPropertyInt(property: String, value: Int) = lib.setPropertyInt(property, value)
    override fun getPropertyDouble(property: String): Double? = lib.getPropertyDouble(property)
    override fun setPropertyDouble(property: String, value: Double) = lib.setPropertyDouble(property, value)
    override fun getPropertyBoolean(property: String): Boolean? = lib.getPropertyBoolean(property)
    override fun setPropertyBoolean(property: String, value: Boolean) = lib.setPropertyBoolean(property, value)
    override fun getPropertyString(property: String): String? = lib.getPropertyString(property)
    override fun setPropertyString(property: String, value: String) = lib.setPropertyString(property, value)
    override fun observeProperty(property: String, format: Int) = lib.observeProperty(property, format)
    override fun addObserver(o: MPVLib.EventObserver) = lib.addObserver(o)
    override fun removeObserver(o: MPVLib.EventObserver) = lib.removeObserver(o)
    override fun addLogObserver(o: MPVLib.LogObserver) = lib.addLogObserver(o)
    override fun removeLogObserver(o: MPVLib.LogObserver) = lib.removeLogObserver(o)

    companion object {
        /** A new core, or null when the library could not create one (as [MPVLib.create]). */
        fun create(context: Context): MpvCore? = MPVLib.create(context)?.let { LibMpvCore(it) }
    }
}
