package com.omama.stationalarm.network

import com.omama.stationalarm.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object GeocodingClient {

    // ── Cloudflare Worker URL ─────────────────────────────────────────────────
    // Default is the hardcoded Worker URL. Firebase Remote Config overwrites this
    // at startup via setWorkerUrl() — allows backend swaps without an app update.
    // Remote Config key: geocoding_backend_url
    private const val DEFAULT_WORKER_URL = "https://stationalarm-geo.mohammedomama2005.workers.dev/"

    // ── Shared HTTP client ────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .apply {
            // Never add the logging interceptor in release — request/response bodies
            // can contain user-entered location queries, which must not leak to Logcat.
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
        .build()

    // ── Dynamic Worker URL (set by Remote Config on startup) ──────────────────

    @Volatile private var workerBaseUrl = DEFAULT_WORKER_URL
    @Volatile private var _geocodingService: GeocodingService? = null

    /**
     * Called by StationAlarmApplication once Remote Config has fetched.
     * If the URL changed, the next call to [geocodingService] rebuilds Retrofit.
     */
    fun setWorkerUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        if (normalized != workerBaseUrl) {
            workerBaseUrl = normalized
            _geocodingService = null
        }
    }

    // ── Service instances ─────────────────────────────────────────────────────

    val geocodingService: GeocodingService
        get() = _geocodingService ?: buildGeocodingService().also { _geocodingService = it }

    private fun buildGeocodingService() = Retrofit.Builder()
        .baseUrl(workerBaseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeocodingService::class.java)
}
