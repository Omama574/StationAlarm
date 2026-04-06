package com.omama.stationalarm.network

import com.omama.stationalarm.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object GeocodingClient {

    // ── Cloudflare Worker URL ─────────────────────────────────────────────────
    // TODO: Replace with your deployed Worker URL once Cloudflare is set up.
    // When Firebase Remote Config is integrated, this will be fetched at runtime via:
    //   Remote Config key: geocoding_backend_url
    // The URL being public is fine — the LocationIQ API key lives only in Cloudflare secrets.
    private const val WORKER_BASE_URL = "https://stationalarm-geo.mohammedomama2005.workers.dev/"

    // Photon is called directly from the device (not via Worker).
    // See PhotonService for explanation of why direct is better here.
    private const val PHOTON_BASE_URL = "https://photon.komoot.io/"

    // ── Shared HTTP client ────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    // ── Service instances ─────────────────────────────────────────────────────

    val locationIqService: LocationIqService by lazy {
        Retrofit.Builder()
            .baseUrl(WORKER_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LocationIqService::class.java)
    }

    val photonService: PhotonService by lazy {
        Retrofit.Builder()
            .baseUrl(PHOTON_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PhotonService::class.java)
    }
}
