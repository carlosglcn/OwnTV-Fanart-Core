package tv.own.owntv.player

import android.os.Debug

/**
 * glcn: where this process's memory is, as `dumpsys meminfo` sums it — Java heap, native heap, graphics (pictures held
 * by the GPU, video surfaces, the players' frame buffers), code and the total PSS the system judges us by. The
 * performance meter shows it and the playback report records it whenever Android warns of memory pressure, so a
 * report says what was large instead of only that memory ran short. Reading it costs a few milliseconds: call it
 * off the main thread, now and then.
 */
data class MemoryBreakdown(val javaMb: Int, val nativeMb: Int, val graphicsMb: Int, val codeMb: Int, val totalPssMb: Int) {

    /** One line for the log: "pss 512 MB = java 48 · native 160 · graphics 210 · code 60". */
    fun line(): String = "pss $totalPssMb MB = java $javaMb · native $nativeMb · graphics $graphicsMb · code $codeMb"

    companion object {
        fun read(): MemoryBreakdown? = runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            fun stat(name: String) = (info.getMemoryStat(name)?.toIntOrNull() ?: 0) / 1024
            MemoryBreakdown(
                javaMb = stat("summary.java-heap"),
                nativeMb = stat("summary.native-heap"),
                graphicsMb = stat("summary.graphics"),
                codeMb = stat("summary.code"),
                totalPssMb = info.totalPss / 1024,
            )
        }.getOrNull()
    }
}
