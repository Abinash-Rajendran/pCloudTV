package com.ar.pcloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ar.pcloud.data.api.PCloudApiClient
import com.ar.pcloud.data.api.PCloudApiService
import com.ar.pcloud.data.api.PCloudException
import com.ar.pcloud.data.model.FileLinkResponse
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.data.model.ServerRegion
import com.ar.pcloud.data.model.UserInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * All pCloud access. Endpoint semantics follow docs/PCLOUD_API.md.
 *
 * Region handling: an account lives in exactly one data center and its token only
 * works on that host. Login resolves the right host from the response `locationid`
 * and persists it, so later calls never guess.
 */
class PCloudRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val linkCache = LinkCache()

    /**
     * Bound to the account's data center. Replaced whenever login resolves a
     * different region, so no call can ever go to the wrong host.
     */
    @Volatile
    private var api: PCloudApiService = PCloudApiClient.createService(savedRegion())

    // ------------------------------------------------------------- session

    fun savedAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    fun savedEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun savedRegion(): ServerRegion {
        val name = prefs.getString(KEY_REGION, null) ?: return ServerRegion.US
        return runCatching { ServerRegion.valueOf(name) }.getOrDefault(ServerRegion.US)
    }

    /** (used, total) bytes from the last login or [refreshAccount], or null if unknown. */
    fun savedQuota(): Pair<Long, Long>? {
        val total = prefs.getLong(KEY_QUOTA, 0L)
        if (total <= 0L) return null
        return prefs.getLong(KEY_USED_QUOTA, 0L) to total
    }

    /** Grid (false) or list (true) in the browser. Survives sign-out: it is a display taste. */
    fun savedListView(): Boolean = prefs.getBoolean(KEY_LIST_VIEW, false)

    fun saveListView(isListView: Boolean) {
        prefs.edit().putBoolean(KEY_LIST_VIEW, isListView).apply()
    }

    val isLoggedIn: Boolean get() = !savedAuthToken().isNullOrEmpty()

    private fun persistSession(
        token: String,
        email: String?,
        newRegion: ServerRegion,
        quota: Long?,
        usedQuota: Long?
    ) {
        prefs.edit()
            .putString(KEY_AUTH_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_REGION, newRegion.name)
            .putLong(KEY_QUOTA, quota ?: 0L)
            .putLong(KEY_USED_QUOTA, usedQuota ?: 0L)
            .apply()
        api = PCloudApiClient.createService(newRegion)
        // Links are signed per storage host and tied to the old token.
        linkCache.clear()
    }

    /**
     * Re-reads the account's storage figures. Best effort: used quota drifts as
     * files are added from other devices, but nothing in the app depends on it, so
     * a failure is simply ignored and the last known numbers stand.
     *
     * @return (used, total) bytes, or null when the call failed or quota was absent.
     */
    suspend fun refreshAccount(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val token = savedAuthToken()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val response = runCatching { api.getUserInfo(token) }.getOrNull()
            ?: return@withContext null
        if (response.result != 0) return@withContext null

        val total = response.quota ?: return@withContext null
        val used = response.usedquota ?: 0L
        prefs.edit()
            .putLong(KEY_QUOTA, total)
            .putLong(KEY_USED_QUOTA, used)
            .apply()
        used to total
    }

    private fun clearSession() {
        // The view preference is about this TV, not about the account, so signing
        // out and back in should not silently throw it away.
        val listView = savedListView()
        prefs.edit().clear().putBoolean(KEY_LIST_VIEW, listView).apply()
        linkCache.clear()
    }

    /** Token, or a typed 1000 failure so callers can send the user back to login. */
    private fun requireToken(): String =
        savedAuthToken()?.takeIf { it.isNotEmpty() }
            ?: throw PCloudException.of(PCloudException.CODE_LOGIN_REQUIRED, null)

    // ---------------------------------------------------------------- auth

    /**
     * Signs in. Tries digest first (password never leaves the device), then plain
     * password over TLS. If pCloud says the account belongs to the other data
     * center, retries there.
     */
    suspend fun login(
        username: String,
        password: String,
        twoFactorCode: String? = null,
        preferredRegion: ServerRegion
    ): Result<UserInfoResponse> = withContext(Dispatchers.IO) {
        val code = twoFactorCode?.trim()?.takeIf { it.isNotBlank() }
        val email = username.trim()

        val primary = runCatching { authenticate(email, password, code, preferredRegion) }
        primary.getOrNull()?.let { return@withContext Result.success(it) }

        val failure = primary.exceptionOrNull()
        val pcloudError = failure as? PCloudException

        // 2FA and rate limiting are terminal — retrying the other region is noise.
        if (pcloudError?.needsTwoFactor == true || pcloudError?.code == PCloudException.CODE_TOO_MANY_TRIES) {
            return@withContext Result.failure(failure)
        }

        val alternate = if (preferredRegion == ServerRegion.US) ServerRegion.EU else ServerRegion.US
        Log.d(TAG, "Login failed on ${preferredRegion.name}; retrying on ${alternate.name}")

        runCatching { authenticate(email, password, code, alternate) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(failure ?: it) }
            )
    }

    private suspend fun authenticate(
        username: String,
        password: String,
        code: String?,
        attemptRegion: ServerRegion
    ): UserInfoResponse {
        val service = PCloudApiClient.createService(attemptRegion)
        val deviceId = deviceId()

        // 1. Digest: the password itself is never transmitted.
        val digestResult = runCatching {
            val digestResponse = service.getDigest()
            val digest = digestResponse.digest
            if (digestResponse.result != 0 || digest.isNullOrEmpty()) {
                throw PCloudException.from(digestResponse, onLoginPath = true)
            }
            service.loginWithDigest(
                username = username,
                digest = digest,
                passwordDigest = PCloudDigest.passwordDigest(username, password, digest),
                deviceId = deviceId,
                device = PCloudApiClient.DEVICE_NAME,
                os = PCloudApiClient.OS_ANDROID
            )
        }

        digestResult.getOrNull()?.let { response ->
            if (response.result == 0 && !response.auth.isNullOrEmpty()) {
                return finishLogin(response, username, attemptRegion)
            }
            // 2FA account: the password was accepted but the login is only half
            // done. Finish it on tfa_login with the code.
            response.tfaToken?.takeIf { it.isNotEmpty() }?.let { token ->
                return completeTwoFactor(service, token, code, username, attemptRegion)
            }
            // A real credential/2FA rejection — do not retry with the plain password.
            val error = PCloudException.from(response, onLoginPath = true)
            if (error.isAuthFailure || error.needsTwoFactor || error.isWrongRegion) throw error
        }
        digestResult.exceptionOrNull()?.let { if (it is PCloudException) throw it }

        // 2. Plain password over TLS, for the case where getdigest itself failed.
        val response = service.loginWithPassword(
            username = username,
            password = password,
            deviceId = deviceId,
            device = PCloudApiClient.DEVICE_NAME,
            os = PCloudApiClient.OS_ANDROID
        )
        if (response.result == 0 && !response.auth.isNullOrEmpty()) {
            return finishLogin(response, username, attemptRegion)
        }
        response.tfaToken?.takeIf { it.isNotEmpty() }?.let { token ->
            return completeTwoFactor(service, token, code, username, attemptRegion)
        }
        throw PCloudException.from(response, onLoginPath = true)
    }

    /**
     * Stable per-install id, so `trustdevice` on the 2FA step has something to
     * remember and the user is not asked for a code on every sign-in. Generated
     * once and kept in prefs; it identifies the install, not the user.
     */
    private fun deviceId(): String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    /**
     * Second leg of a 2FA login.
     *
     * `userinfo` has no 2FA flow at all — it accepts credentials but ignores
     * `code`, which is why a correct code still came back as "Please provide
     * 'code'". `login` is the endpoint that starts one: on a 2FA account it
     * returns a short-lived `token` for the pending attempt, and `tfa_login`
     * exchanges that token plus the code for the real auth token.
     */
    private suspend fun completeTwoFactor(
        service: PCloudApiService,
        token: String,
        code: String?,
        username: String,
        attemptRegion: ServerRegion
    ): UserInfoResponse {
        // No code typed yet: tell the UI to ask for one rather than burning the
        // token on an empty guess.
        if (code.isNullOrBlank()) {
            throw PCloudException.of(PCloudException.CODE_TFA_REQUIRED, null, onLoginPath = true)
        }

        val response = service.tfaLogin(
            token = token,
            code = code,
            // Remember this TV, so the code is only needed the first time.
            trustDevice = 1,
            deviceId = deviceId(),
            device = PCloudApiClient.DEVICE_NAME
        )
        if (response.result == 0 && !response.auth.isNullOrEmpty()) {
            return finishLogin(response, username, attemptRegion)
        }
        throw PCloudException.from(response, onLoginPath = true)
    }

    /** Signs in with a token pasted from pCloud's web console (bypasses 2FA). */
    suspend fun loginWithToken(
        token: String,
        preferredRegion: ServerRegion
    ): Result<UserInfoResponse> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            return@withContext Result.failure(
                PCloudException.of(PCloudException.CODE_LOGIN_FAILED, "Empty token")
            )
        }

        val order = buildList {
            add(preferredRegion)
            add(if (preferredRegion == ServerRegion.US) ServerRegion.EU else ServerRegion.US)
        }

        var lastError: Throwable? = null
        for (attemptRegion in order) {
            val attempt = runCatching {
                val response = PCloudApiClient.createService(attemptRegion).getUserInfo(cleanToken)
                if (response.result != 0) throw PCloudException.from(response, onLoginPath = true)
                response
            }
            attempt.onSuccess { response ->
                val resolved = ServerRegion.fromLocationId(response.locationid) ?: attemptRegion
                persistSession(
                    token = cleanToken,
                    email = response.email ?: "pCloud user",
                    newRegion = resolved,
                    quota = response.quota,
                    usedQuota = response.usedquota
                )
                return@withContext Result.success(response)
            }
            lastError = attempt.exceptionOrNull()
        }

        Result.failure(
            lastError ?: PCloudException.of(PCloudException.CODE_LOGIN_FAILED, "Invalid token")
        )
    }

    private fun finishLogin(
        response: UserInfoResponse,
        username: String,
        attemptRegion: ServerRegion
    ): UserInfoResponse {
        // The account's real home, which may differ from the region we guessed.
        val resolved = ServerRegion.fromHostname(response.apiserver)
            ?: ServerRegion.fromLocationId(response.locationid)
            ?: attemptRegion
        persistSession(
            token = response.auth!!,
            email = response.email ?: username,
            newRegion = resolved,
            quota = response.quota,
            usedQuota = response.usedquota
        )
        Log.d(TAG, "Signed in on ${resolved.name}")
        return response
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        savedAuthToken()?.let { token ->
            // Best effort: the local session is cleared either way.
            runCatching { api.logout(token) }
        }
        clearSession()
    }

    // ------------------------------------------------------------- folders

    /** Folder contents, folders first then files, each group alphabetical. */
    suspend fun listFolder(folderId: Long): Result<List<ItemMetadata>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.listFolder(auth = requireToken(), folderId = folderId)
            if (response.result != 0) throw PCloudException.from(response)
            response.metadata?.contents
                .orEmpty()
                .filterNot { it.isdeleted }
                .sortedWith(
                    compareByDescending<ItemMetadata> { it.isfolder }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
        }
    }

    // ----------------------------------------------------------- streaming

    /**
     * Original, untranscoded stream — full quality, no resolution cap. The primary
     * video path and the full-size photo path.
     */
    suspend fun originalLink(fileId: Long): Result<MediaLink> = withContext(Dispatchers.IO) {
        runCatching {
            cachedOrFetch(fileId, LinkCache.Kind.FILE) {
                api.getFileLink(auth = requireToken(), fileId = fileId)
            }
        }
    }

    /**
     * Adaptive HLS. The fallback when the original will not decode on this device.
     * `resolution` is capped at 1280x960 by the API, so there is no point asking
     * for more.
     */
    suspend fun hlsLink(
        fileId: Long,
        resolution: String = HLS_MAX_RESOLUTION
    ): Result<MediaLink> = withContext(Dispatchers.IO) {
        runCatching {
            cachedOrFetch(fileId, LinkCache.Kind.HLS, isHls = true) {
                api.getHlsLink(auth = requireToken(), fileId = fileId, resolution = resolution)
            }
        }
    }

    /**
     * mp3 stream. Lets FLAC/ALAC/OPUS play on TV boxes with a narrow decoder set,
     * and can pull the audio track out of a video.
     */
    suspend fun audioLink(
        fileId: Long,
        bitrateKbit: Int = AUDIO_BITRATE_KBIT
    ): Result<MediaLink> = withContext(Dispatchers.IO) {
        runCatching {
            cachedOrFetch(fileId, LinkCache.Kind.AUDIO) {
                api.getAudioLink(auth = requireToken(), fileId = fileId, audioBitrate = bitrateKbit)
            }
        }
    }

    private inline fun cachedOrFetch(
        fileId: Long,
        kind: LinkCache.Kind,
        isHls: Boolean = false,
        fetch: () -> FileLinkResponse
    ): MediaLink {
        linkCache.get(fileId, kind)?.let { return it }

        val response = fetch()
        if (response.result != 0) throw PCloudException.from(response)

        val urls = response.urls
        if (urls.isEmpty()) {
            throw PCloudException.of(
                PCloudException.CODE_NO_SERVERS,
                "pCloud returned no storage host for this file."
            )
        }

        val link = MediaLink(
            url = urls.first(),
            fallbackUrls = urls.drop(1),
            expiresAtMillis = LinkCache.parseExpires(response.expires),
            isHls = isHls
        )
        linkCache.put(fileId, kind, link)
        return link
    }

    // ---------------------------------------------------------- thumbnails

    /**
     * Batch thumbnails for a grid. The docs prefer getthumbslinks over N calls to
     * getthumblink because it "connects to multiple storage serves simultaneously".
     *
     * Only items with `thumb == true` are requested — anything else returns 1014.
     * Per-entry `result` is checked individually: the top-level result can be 0
     * while a single file failed.
     *
     * @return fileid -> thumbnail URL, for the files that produced one.
     */
    suspend fun thumbnails(
        items: List<ItemMetadata>,
        size: String = GRID_THUMB_SIZE,
        crop: Boolean = true
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        val token = savedAuthToken() ?: return@withContext emptyMap()
        val ids = items.filter { it.thumb && !it.isfolder }.mapNotNull { it.fileid }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()

        val resolved = mutableMapOf<Long, String>()
        for (batch in ids.chunked(THUMB_BATCH_SIZE)) {
            val response = runCatching {
                api.getThumbsLinks(
                    auth = token,
                    fileIds = batch.joinToString(","),
                    size = size,
                    crop = if (crop) 1 else null
                )
            }.getOrNull() ?: continue

            if (response.result != 0) {
                Log.w(TAG, "getthumbslinks failed: ${response.result} ${response.error}")
                continue
            }

            response.thumbs.orEmpty().forEach { entry ->
                val fileId = entry.fileid
                val url = entry.url
                if (entry.result == 0 && fileId != null && url != null) {
                    resolved[fileId] = url
                    linkCache.put(
                        fileId,
                        LinkCache.Kind.THUMB,
                        MediaLink(url, expiresAtMillis = LinkCache.parseExpires(entry.expires))
                    )
                }
            }
        }
        resolved
    }

    /** Single large thumbnail, for album art on the now-playing screen. */
    suspend fun thumbnail(
        fileId: Long,
        size: String = ART_THUMB_SIZE,
        crop: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        linkCache.get(fileId, LinkCache.Kind.THUMB)?.let { return@withContext it.url }
        val token = savedAuthToken() ?: return@withContext null

        val response = runCatching {
            api.getThumbLink(
                auth = token,
                fileId = fileId,
                size = size,
                crop = if (crop) 1 else null
            )
        }.getOrNull() ?: return@withContext null

        if (response.result != 0) return@withContext null
        val url = response.downloadUrl ?: return@withContext null

        linkCache.put(
            fileId,
            LinkCache.Kind.THUMB,
            MediaLink(url, expiresAtMillis = LinkCache.parseExpires(response.expires))
        )
        url
    }

    companion object {
        private const val TAG = "PCloudRepository"
        private const val PREFS_NAME = "pcloud_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_REGION = "server_region"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_QUOTA = "quota_bytes"
        private const val KEY_USED_QUOTA = "used_quota_bytes"
        private const val KEY_LIST_VIEW = "browser_list_view"

        /** API hard cap for gethlslink/getvideolink is 1280x960. */
        const val HLS_MAX_RESOLUTION = "1280x960"
        const val AUDIO_BITRATE_KBIT = 256

        /**
         * Thumbnail sizes must have width 16-2048, height 16-1024, each divisible
         * by 4 or 5. 320x180 is 16:9 and satisfies both rules.
         */
        const val GRID_THUMB_SIZE = "320x180"

        /** Square album art; 480 is divisible by 4 and within the height limit. */
        const val ART_THUMB_SIZE = "480x480"

        private const val THUMB_BATCH_SIZE = 60
    }
}
