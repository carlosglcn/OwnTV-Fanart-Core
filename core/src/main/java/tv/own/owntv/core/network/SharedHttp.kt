package tv.own.owntv.core.network

import okhttp3.OkHttpClient

/**
 * glcn: a builder from the app's one OkHttpClient, for code that cannot take it by injection (the Emby
 * bridge, the WebDAV walker). A bare `OkHttpClient.Builder()` misses what the shared client carries — the
 * Let's Encrypt roots the device lacks (ISRG Root YE / YR, X2), the custom DNS and the proxy — and a home
 * server whose certificate moved to the newer roots then answered "could not reach the server" on the TV
 * while the phone, with a newer trust store, still reached it.
 */
internal object SharedHttp {
    fun builder(): OkHttpClient.Builder =
        runCatching { org.koin.core.context.GlobalContext.get().get<OkHttpClient>().newBuilder() }
            .getOrElse { OkHttpClient.Builder() }
}
