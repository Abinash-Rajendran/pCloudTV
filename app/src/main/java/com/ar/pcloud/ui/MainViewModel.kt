package com.ar.pcloud.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ar.pcloud.data.api.PCloudException
import com.ar.pcloud.data.model.Breadcrumb
import com.ar.pcloud.data.model.ItemMetadata
import com.ar.pcloud.data.model.MediaLink
import com.ar.pcloud.data.model.PCloudFileType
import com.ar.pcloud.data.model.ServerRegion
import com.ar.pcloud.data.model.findSubtitlesFor
import com.ar.pcloud.data.repository.PCloudRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A subtitle sidecar with its stream URL resolved, ready to hand to ExoPlayer. */
data class ResolvedSubtitle(
    val url: String,
    val label: String,
    val language: String?,
    val mimeType: String
)

sealed interface Screen {
    data object Login : Screen
    data object Browser : Screen
    data class VideoPlayer(
        val item: ItemMetadata,
        val link: MediaLink,
        /** Sidecar subtitle files found alongside the video; first one is shown by default. */
        val subtitles: List<ResolvedSubtitle> = emptyList()
    ) : Screen

    data class AudioPlayer(
        val queue: List<ItemMetadata>,
        val index: Int,
        val link: MediaLink,
        /**
         * True once this track fell back to pCloud's mp3 transcode. That stream is
         * chunked with no length, so the player must not expect a duration from it
         * and must not ask for the fallback a second time.
         */
        val isTranscoded: Boolean = false
    ) : Screen

    data class ImageViewer(
        val items: List<ItemMetadata>,
        val index: Int
    ) : Screen
}

data class UiState(
    val isLoggedIn: Boolean = false,
    val userEmail: String? = null,
    val selectedRegion: ServerRegion = ServerRegion.US,
    /** Total account storage in bytes, 0 when not yet known. */
    val quotaBytes: Long = 0,
    val usedQuotaBytes: Long = 0,
    /** Browser layout: tiles (false) or one row per item (true). Persisted. */
    val isListView: Boolean = false,
    val currentScreen: Screen = Screen.Login,
    val breadcrumbs: List<Breadcrumb> = listOf(ROOT_CRUMB),
    val items: List<ItemMetadata> = emptyList(),
    /** fileid -> thumbnail URL, filled in after the grid is already on screen. */
    val thumbnails: Map<Long, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isResolvingMedia: Boolean = false,
    val errorMessage: String? = null,
    val loginError: String? = null,
    val isLoggingIn: Boolean = false,
    /** Set when pCloud asks for a 2FA code, so the login screen can reveal the field. */
    val needsTwoFactor: Boolean = false
) {
    /**
     * Whether Back has somewhere to go. False in the root folder with no error
     * showing, so Back there leaves the app rather than doing nothing.
     */
    val canHandleBack: Boolean
        get() = currentScreen == Screen.Browser &&
            (breadcrumbs.size > 1 || errorMessage != null)

    companion object {
        val ROOT_CRUMB = Breadcrumb(0L, "pCloud Drive")
    }
}

private val ROOT_CRUMB = UiState.ROOT_CRUMB

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PCloudRepository(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var folderJob: Job? = null
    private var thumbnailJob: Job? = null
    private var mediaJob: Job? = null

    init {
        restoreSession()
    }

    private fun restoreSession() {
        val region = repository.savedRegion()
        val listView = repository.savedListView()
        if (repository.isLoggedIn) {
            val quota = repository.savedQuota()
            _uiState.update {
                it.copy(
                    isLoggedIn = true,
                    userEmail = repository.savedEmail(),
                    selectedRegion = region,
                    usedQuotaBytes = quota?.first ?: 0L,
                    quotaBytes = quota?.second ?: 0L,
                    isListView = listView,
                    currentScreen = Screen.Browser
                )
            }
            openFolder(ROOT_CRUMB, reset = true)
            refreshAccount()
        } else {
            _uiState.update {
                it.copy(
                    selectedRegion = region,
                    isListView = listView,
                    currentScreen = Screen.Login
                )
            }
        }
    }

    /** Flips the browser between the tile grid and the list, and remembers the choice. */
    fun toggleListView() {
        val next = !_uiState.value.isListView
        repository.saveListView(next)
        _uiState.update { it.copy(isListView = next) }
    }

    /**
     * Refreshes the storage figures shown in the menu. Silent on failure — the
     * cached numbers are good enough and this must never interrupt browsing.
     */
    private fun refreshAccount() {
        viewModelScope.launch {
            val (used, total) = repository.refreshAccount() ?: return@launch
            _uiState.update { it.copy(usedQuotaBytes = used, quotaBytes = total) }
        }
    }

    // --------------------------------------------------------------- login

    fun setRegion(region: ServerRegion) {
        _uiState.update { it.copy(selectedRegion = region) }
    }

    fun loginWithCredentials(username: String, password: String, code: String?) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(loginError = "Enter your pCloud email and password.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            repository.login(username, password, code, _uiState.value.selectedRegion)
                .onSuccess { onLoginSuccess(it.email ?: username) }
                .onFailure { onLoginFailure(it) }
        }
    }

    fun loginWithToken(token: String) {
        if (token.isBlank()) {
            _uiState.update { it.copy(loginError = "Paste an API auth token.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            repository.loginWithToken(token, _uiState.value.selectedRegion)
                .onSuccess { onLoginSuccess(it.email ?: "pCloud user") }
                .onFailure { onLoginFailure(it) }
        }
    }

    private fun onLoginSuccess(email: String) {
        val quota = repository.savedQuota()
        _uiState.update {
            it.copy(
                isLoggedIn = true,
                isLoggingIn = false,
                needsTwoFactor = false,
                loginError = null,
                userEmail = email,
                selectedRegion = repository.savedRegion(),
                usedQuotaBytes = quota?.first ?: 0L,
                quotaBytes = quota?.second ?: 0L,
                isListView = repository.savedListView(),
                currentScreen = Screen.Browser
            )
        }
        openFolder(ROOT_CRUMB, reset = true)
    }

    private fun onLoginFailure(error: Throwable) {
        val pcloud = error as? PCloudException
        _uiState.update {
            it.copy(
                isLoggingIn = false,
                needsTwoFactor = pcloud?.needsTwoFactor ?: it.needsTwoFactor,
                loginError = friendlyMessage(error, "Sign-in failed.")
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            folderJob?.cancel()
            thumbnailJob?.cancel()
            mediaJob?.cancel()
            _uiState.value = UiState(
                selectedRegion = repository.savedRegion(),
                isListView = repository.savedListView(),
                currentScreen = Screen.Login
            )
        }
    }

    // ------------------------------------------------------------- browsing

    /**
     * Loads a folder. If the crumb is already in the trail we truncate to it
     * (going up), otherwise we append (going down).
     */
    fun openFolder(crumb: Breadcrumb, reset: Boolean = false) {
        folderJob?.cancel()
        thumbnailJob?.cancel()

        val trail = when {
            reset -> listOf(ROOT_CRUMB)
            else -> {
                val existing = _uiState.value.breadcrumbs
                val at = existing.indexOfFirst { it.folderId == crumb.folderId }
                if (at >= 0) existing.take(at + 1) else existing + crumb
            }
        }

        folderJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    breadcrumbs = trail,
                    items = emptyList(),
                    thumbnails = emptyMap()
                )
            }

            repository.listFolder(crumb.folderId)
                .onSuccess { items ->
                    _uiState.update { it.copy(isLoading = false, items = items) }
                    loadThumbnails(items)
                }
                .onFailure { error ->
                    if (error is PCloudException && error.isAuthFailure) {
                        forceReLogin(error)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = friendlyMessage(error, "Could not open that folder.")
                            )
                        }
                    }
                }
        }
    }

    /**
     * Re-reads the folder on screen, for when files have been added from another
     * device. Re-opening the current crumb is enough: [openFolder] finds it already
     * in the trail and truncates to it, leaving the breadcrumbs untouched.
     */
    fun refreshCurrentFolder() {
        _uiState.value.breadcrumbs.lastOrNull()?.let { openFolder(it) }
        refreshAccount()
    }

    /** Thumbnails arrive after the grid renders, so browsing is never blocked on them. */
    private fun loadThumbnails(items: List<ItemMetadata>) {
        thumbnailJob = viewModelScope.launch {
            val urls = repository.thumbnails(items)
            if (urls.isNotEmpty()) {
                _uiState.update { state ->
                    // Only apply if the user has not moved on to another folder.
                    if (state.items === items) state.copy(thumbnails = urls) else state
                }
            }
        }
    }

    fun onItemSelected(item: ItemMetadata) {
        if (item.isfolder) {
            val folderId = item.folderid ?: return
            openFolder(Breadcrumb(folderId, item.name))
            return
        }
        when (item.fileType) {
            PCloudFileType.VIDEO -> playVideo(item)
            PCloudFileType.AUDIO -> playAudio(item)
            PCloudFileType.IMAGE -> openImages(item)
            else -> _uiState.update {
                it.copy(errorMessage = "${item.name} is not a video, photo or music file.")
            }
        }
    }

    // ------------------------------------------------------------ playback

    private fun playVideo(item: ItemMetadata) {
        val fileId = item.fileid ?: return
        val siblings = _uiState.value.items
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            _uiState.update { it.copy(isResolvingMedia = true, errorMessage = null) }
            repository.originalLink(fileId)
                .onSuccess { link ->
                    val subtitles = resolveSubtitles(item, siblings)
                    _uiState.update {
                        it.copy(
                            isResolvingMedia = false,
                            currentScreen = Screen.VideoPlayer(item, link, subtitles)
                        )
                    }
                }
                .onFailure { onMediaFailure(it, "Could not start ${item.name}.") }
        }
    }

    /**
     * Resolves stream URLs for any subtitle files sitting next to the video.
     *
     * Fetched in parallel and individually optional: a sidecar whose link fails is
     * dropped rather than allowed to hold up — let alone fail — the film itself.
     */
    private suspend fun resolveSubtitles(
        item: ItemMetadata,
        folder: List<ItemMetadata>
    ): List<ResolvedSubtitle> = coroutineScope {
        findSubtitlesFor(item, folder)
            .map { track ->
                async {
                    repository.originalLink(track.fileId).getOrNull()?.let { link ->
                        ResolvedSubtitle(
                            url = link.url,
                            label = track.label,
                            language = track.language,
                            mimeType = track.mimeType
                        )
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Second attempt for a video the device could not decode: ask pCloud to
     * transcode it to HLS. Capped at 1280x960 by the API — the explicit trade for
     * always-playable. Invoked by the player on a playback error.
     */
    fun retryVideoWithHls(item: ItemMetadata, onUnavailable: (String) -> Unit) {
        val fileId = item.fileid ?: return
        // Carry the already-resolved sidecars across, or they vanish on the swap.
        val subtitles = (_uiState.value.currentScreen as? Screen.VideoPlayer)
            ?.subtitles
            .orEmpty()
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            repository.hlsLink(fileId)
                .onSuccess { link ->
                    _uiState.update {
                        it.copy(currentScreen = Screen.VideoPlayer(item, link, subtitles))
                    }
                }
                .onFailure { error ->
                    onUnavailable(
                        friendlyMessage(error, "This video cannot be played on this device.")
                    )
                }
        }
    }

    private fun playAudio(item: ItemMetadata) {
        val queue = _uiState.value.items.filter { it.fileType == PCloudFileType.AUDIO }
        val index = queue.indexOfFirst { it.fileid == item.fileid }.coerceAtLeast(0)
        startAudioAt(queue.ifEmpty { listOf(item) }, index)
    }

    /**
     * Loads the track at [index] and shows (or updates) the now-playing screen.
     *
     * The *original* file is streamed, not `getaudiolink`'s mp3 transcode. The
     * transcode is produced on the fly, so it arrives chunked with no Content-Length
     * and no Xing header: ExoPlayer can never work out how long it is, which is why
     * the progress bar sat at "Loading…" forever. The original carries a real length
     * and a seek table. [retryAudioWithTranscode] is the way back for the formats a
     * TV box genuinely cannot decode.
     */
    fun startAudioAt(queue: List<ItemMetadata>, index: Int) {
        val track = queue.getOrNull(index) ?: return
        val fileId = track.fileid ?: return
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            _uiState.update { it.copy(isResolvingMedia = true, errorMessage = null) }
            repository.originalLink(fileId)
                .onSuccess { link ->
                    _uiState.update {
                        it.copy(
                            isResolvingMedia = false,
                            currentScreen = Screen.AudioPlayer(queue, index, link)
                        )
                    }
                }
                .onFailure { onMediaFailure(it, "Could not play ${track.displayTitle}.") }
        }
    }

    /**
     * Second attempt for a track this device cannot decode (FLAC, ALAC, Opus on a
     * narrow TV box): ask pCloud for the mp3 transcode. Invoked by the player on a
     * playback error, mirroring the video screen's HLS fallback.
     */
    fun retryAudioWithTranscode(onUnavailable: (String) -> Unit) {
        val screen = _uiState.value.currentScreen as? Screen.AudioPlayer ?: return
        val track = screen.queue.getOrNull(screen.index) ?: return
        val fileId = track.fileid ?: return
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            repository.audioLink(fileId)
                .onSuccess { link ->
                    _uiState.update {
                        it.copy(
                            currentScreen = screen.copy(link = link, isTranscoded = true)
                        )
                    }
                }
                .onFailure { error ->
                    onUnavailable(
                        friendlyMessage(error, "${track.displayTitle} cannot be played here.")
                    )
                }
        }
    }

    fun skipTrack(delta: Int) {
        val screen = _uiState.value.currentScreen as? Screen.AudioPlayer ?: return
        val next = screen.index + delta
        if (next in screen.queue.indices) startAudioAt(screen.queue, next)
    }

    /** Album art for the now-playing screen; null when the file has no thumbnail. */
    fun loadAlbumArt(item: ItemMetadata, onResult: (String?) -> Unit) {
        val fileId = item.fileid
        if (fileId == null || !item.thumb) {
            onResult(null)
            return
        }
        viewModelScope.launch { onResult(repository.thumbnail(fileId)) }
    }

    private fun openImages(item: ItemMetadata) {
        val images = _uiState.value.items.filter { it.fileType == PCloudFileType.IMAGE }
        val index = images.indexOfFirst { it.fileid == item.fileid }.coerceAtLeast(0)
        _uiState.update {
            it.copy(currentScreen = Screen.ImageViewer(images.ifEmpty { listOf(item) }, index))
        }
    }

    /** Full-size photo URL via getfilelink. */
    fun resolveImage(item: ItemMetadata, onResult: (String?) -> Unit) {
        val fileId = item.fileid ?: return onResult(null)
        viewModelScope.launch {
            onResult(repository.originalLink(fileId).getOrNull()?.url)
        }
    }

    private fun onMediaFailure(error: Throwable, fallback: String) {
        if (error is PCloudException && error.isAuthFailure) {
            forceReLogin(error)
            return
        }
        _uiState.update {
            it.copy(isResolvingMedia = false, errorMessage = friendlyMessage(error, fallback))
        }
    }

    // ----------------------------------------------------------- navigation

    /** @return true if the back press was consumed. */
    fun navigateBack(): Boolean {
        val state = _uiState.value
        return when (state.currentScreen) {
            is Screen.VideoPlayer, is Screen.AudioPlayer, is Screen.ImageViewer -> {
                mediaJob?.cancel()
                _uiState.update { it.copy(currentScreen = Screen.Browser) }
                true
            }

            Screen.Browser -> {
                val crumbs = state.breadcrumbs
                // An error with items behind it (a file that would not open) just
                // needs dismissing — the grid is still usable underneath.
                if (state.errorMessage != null && state.items.isNotEmpty()) {
                    clearError()
                    return true
                }
                // Otherwise the folder itself failed to load, so go up; openFolder
                // clears the error on its way.
                if (crumbs.size > 1) {
                    openFolder(crumbs[crumbs.size - 2])
                    true
                } else {
                    // Root with nothing to show: let Back leave the app.
                    if (state.errorMessage != null) clearError()
                    false
                }
            }

            Screen.Login -> false
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, loginError = null) }
    }

    private fun forceReLogin(error: Throwable) {
        _uiState.update {
            UiState(
                selectedRegion = repository.savedRegion(),
                currentScreen = Screen.Login,
                loginError = friendlyMessage(error, "Your session expired. Sign in again.")
            )
        }
        viewModelScope.launch { repository.logout() }
    }

    /**
     * What the viewer sees when something fails.
     *
     * pCloud's own errors are already written for a human. Connectivity failures
     * are rewritten into something a person in a living room can act on — the raw
     * `Unable to resolve host "eapi.pcloud.com": No address associated with
     * hostname` names a server the viewer has never heard of and reads like the
     * app is broken, when the answer is almost always the TV's own network.
     * Anything else is an internal fault whose stack-trace-grade text tells the
     * viewer nothing; those get the plain sentence and the detail goes to logcat.
     */
    private fun friendlyMessage(error: Throwable, fallback: String): String = when (error) {
        is PCloudException -> error.message

        // DNS. Transient on a TV that has just woken up, and self-healing, so the
        // wording points at the retry rather than at pCloud.
        is UnknownHostException -> "$fallback Could not reach pCloud — check this " +
            "TV's network connection, then try again."

        is SocketTimeoutException -> "$fallback pCloud took too long to respond. Try again."

        is IOException -> error.localizedMessage?.takeIf { it.isNotBlank() }
            ?.let { "$fallback ($it)" }
            ?: fallback

        else -> {
            Log.w(TAG, fallback, error)
            fallback
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
