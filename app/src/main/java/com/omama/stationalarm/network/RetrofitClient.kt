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

    // Photon is called directly from the device (not via Worker).
    // See PhotonService for explanation of why direct is better here.
    private const val PHOTON_BASE_URL = "https://photon.komoot.io/"

    // ── Shared HTTP client ────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    // ── Dynamic Worker URL (set by Remote Config on startup) ──────────────────

    @Volatile private var workerBaseUrl = DEFAULT_WORKER_URL
    @Volatile private var _locationIqService: LocationIqService? = null

    /**
     * Called by StationAlarmApplication once Remote Config has fetched.
     * If the URL changed, the next call to [locationIqService] rebuilds Retrofit.
     */
    fun setWorkerUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        if (normalized != workerBaseUrl) {
            workerBaseUrl = normalized
            _locationIqService = null
        }
    }

    // ── Service instances ─────────────────────────────────────────────────────

    val locationIqService: LocationIqService
        get() = _locationIqService ?: buildLocationIqService().also { _locationIqService = it }

    private fun buildLocationIqService() = Retrofit.Builder()
        .baseUrl(workerBaseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LocationIqService::class.java)

    val photonService: PhotonService by lazy {
        Retrofit.Builder()
            .baseUrl(PHOTON_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PhotonService::class.java)
    }
}
