package tv.own.owntv.core.epg

import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: the addresses the app has handed out as channel logos ([displayLogoUrl]). The apps' image
 * loaders look here to give logos — and only logos — their own treatment (a plain white backdrop
 * taken away), so posters and fanart are never touched.
 */
object ChannelLogoUrls {
    private const val MAX = 20_000
    private val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun note(url: String?) {
        if (url.isNullOrBlank() || urls.size >= MAX) return
        urls.add(url)
    }

    fun has(url: String): Boolean = url in urls
}
