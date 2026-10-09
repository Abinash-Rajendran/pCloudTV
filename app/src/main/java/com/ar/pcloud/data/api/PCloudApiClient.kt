package com.ar.pcloud.data.api

import android.util.Log
import com.ar.pcloud.BuildConfig
import com.ar.pcloud.data.model.ServerRegion
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object PCloudApiClient {

    /**
     * Sent as the `device` login parameter and the User-Agent. The docs note the
     * device identifies the issued token, so it shows up in `listtokens`.
     */
    const val DEVICE_NAME = "pCloud TV (Android TV)"

    /** `os` value pCloud's own clients send for Android. */
    const val OS_ANDROID = 4

    private const val TAG = "PCloudHttp"

    /** Query parameters that must never reach logcat in the clear. */
    private val SENSITIVE_PARAM =
        Regex("(?<![a-z])(auth|password|passworddigest|digest|code|username|token|deviceid)=[^&\\s]*")

    /**
     * pCloud passes credentials as query parameters, so the request *line* alone
     * leaks the auth token. Logcat is readable by other processes on many TV boxes,
     * so debug logging stays at BASIC (method, path, status — no bodies) and every
     * sensitive value is masked on the way out.
     */
    private val redactedLogging = HttpLoggingInterceptor { message ->
        Log.d(TAG, SENSITIVE_PARAM.replace(message) { "${it.groupValues[1]}=***" })
    }.apply { level = HttpLoggingInterceptor.Level.BASIC }

    /**
     * How many times a name lookup is retried before the call is allowed to fail.
     *
     * A TV that has just woken, or whose Wi-Fi has just reassociated, routinely
     * fails its first DNS query and answers the second. OkHttp's own
     * `retryOnConnectionFailure` does not help: with no address there is no route
     * to try, so UnknownHostException propagates on the first attempt.
     *
     * This only covers the transient case. A resolver that is genuinely down stays
     * down, and the viewer gets the "check this TV's network" message instead.
     */
    private const val DNS_RETRIES = 2
    private const val DNS_RETRY_DELAY_MS = 600L

    /** @see DNS_RETRIES */
    private val dnsRetryInterceptor = Interceptor { chain ->
        for (attempt in 0 until DNS_RETRIES) {
            try {
                return@Interceptor chain.proceed(chain.request())
            } catch (error: UnknownHostException) {
                if (chain.call().isCanceled()) throw error
                Log.w(TAG, "DNS lookup failed for ${chain.request().url.host}; retrying")
                // Interceptors run on OkHttp's own thread, never the main one.
                try {
                    Thread.sleep(DNS_RETRY_DELAY_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
        // Last attempt: whatever it throws is what the caller sees.
        chain.proceed(chain.request())
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(redactedLogging)
                }
            }
            .addInterceptor(dnsRetryInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", DEVICE_NAME)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Transcoded HLS/mp3 links can take a while to be produced server-side.
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** One Retrofit instance per region, reusing the single shared connection pool. */
    private val services = mutableMapOf<ServerRegion, PCloudApiService>()

    @Synchronized
    fun createService(region: ServerRegion): PCloudApiService =
        services.getOrPut(region) {
            Retrofit.Builder()
                .baseUrl(region.baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PCloudApiService::class.java)
        }
}
