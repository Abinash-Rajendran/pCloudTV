package com.ar.pcloud.data.api

import com.ar.pcloud.data.model.DigestResponse
import com.ar.pcloud.data.model.FileLinkResponse
import com.ar.pcloud.data.model.FolderResponse
import com.ar.pcloud.data.model.ThumbsLinksResponse
import com.ar.pcloud.data.model.UserInfoResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * pCloud HTTP/JSON API. Endpoint and parameter names follow docs/PCLOUD_API.md,
 * which was scraped from https://docs.pcloud.com/.
 *
 * All streaming/thumbnail endpoints "can't be used from web applications. Referrer
 * is restricted to pcloud.com." — fine for a native client, but these links must
 * never be loaded through a WebView.
 */
interface PCloudApiService {

    // ---------------------------------------------------------------- auth

    /** Nonce for digest login. Pair with `userinfo?digest=&passworddigest=`. */
    @GET("getdigest")
    suspend fun getDigest(): DigestResponse

    /**
     * Credential login — the endpoint pCloud's own clients use, and the only one
     * that can start a two-factor login.
     *
     * `userinfo` is not usable here despite accepting the same credentials: it
     * has no notion of a 2FA challenge, so on a 2FA account it simply fails and
     * ignores any `code`. `login` instead hands back a short-lived `token`
     * standing for the pending attempt, which [tfaLogin] exchanges for a real
     * `auth` token.
     *
     * Digest form: passworddigest = sha1(password + sha1(lowercase(username)) +
     * digest), each sha1 rendered as 40 lowercase hex chars, so the password
     * itself never leaves the device.
     */
    @GET("login")
    suspend fun loginWithDigest(
        @Query("username") username: String,
        @Query("digest") digest: String,
        @Query("passworddigest") passwordDigest: String,
        @Query("deviceid") deviceId: String? = null,
        @Query("device") device: String? = null,
        @Query("os") os: Int? = null,
        @Query("getauth") getAuth: Int = 1,
        @Query("logout") logout: Int = 1
    ): UserInfoResponse

    /** Plain-password login. Docs: "should only be used over SSL connections." */
    @GET("login")
    suspend fun loginWithPassword(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("deviceid") deviceId: String? = null,
        @Query("device") device: String? = null,
        @Query("os") os: Int? = null,
        @Query("getauth") getAuth: Int = 1,
        @Query("logout") logout: Int = 1
    ): UserInfoResponse

    /**
     * Second leg of a two-factor login: the `token` from [loginWithDigestV2] plus
     * the 6-digit code. This is what actually issues the `auth` token.
     *
     * The parameter is `token`, not `tfatoken` — sending the latter is ignored
     * and the call fails as though no login were in progress.
     *
     * @param trustDevice 1 remembers this device so later sign-ins skip the code.
     *   Requires a stable `deviceid` on the login call for that to mean anything.
     */
    @GET("tfa_login")
    suspend fun tfaLogin(
        @Query("token") token: String,
        @Query("code") code: String,
        @Query("trustdevice") trustDevice: Int? = null,
        @Query("deviceid") deviceId: String? = null,
        @Query("device") device: String? = null,
        @Query("getauth") getAuth: Int = 1
    ): UserInfoResponse

    /** Validates an existing token and returns the account's `locationid`. */
    @GET("userinfo")
    suspend fun getUserInfo(
        @Query("auth") auth: String
    ): UserInfoResponse

    @GET("logout")
    suspend fun logout(
        @Query("auth") auth: String
    ): UserInfoResponse

    // -------------------------------------------------------------- folders

    /** Root folder is always folderid 0. */
    @GET("listfolder")
    suspend fun listFolder(
        @Query("auth") auth: String,
        @Query("folderid") folderId: Long = 0,
        @Query("nofiles") noFiles: Int? = null,
        @Query("recursive") recursive: Int? = null
    ): FolderResponse

    // ------------------------------------------------------------ streaming

    /**
     * Original bytes, no transcode — the primary video path and the full-size photo
     * path. No resolution cap, so 4K stays 4K.
     */
    @GET("getfilelink")
    suspend fun getFileLink(
        @Query("auth") auth: String,
        @Query("fileid") fileId: Long,
        @Query("forcedownload") forceDownload: Int? = null,
        @Query("skipfilename") skipFilename: Int? = null,
        @Query("maxspeed") maxSpeed: Int? = null
    ): FileLinkResponse

    /**
     * Adaptive HLS, used as the fallback when the original container or codec will
     * not play. `path` ends in .m3u8. Server-side transcode, so `resolution` is
     * capped at 1280x960 by the API.
     */
    @GET("gethlslink")
    suspend fun getHlsLink(
        @Query("auth") auth: String,
        @Query("fileid") fileId: Long,
        @Query("resolution") resolution: String? = null,
        @Query("vbitrate") videoBitrate: Int? = null,
        @Query("abitrate") audioBitrate: Int? = null,
        @Query("skipfilename") skipFilename: Int? = null
    ): FileLinkResponse

    /**
     * mp3 stream, transcoded. Docs suggest it "to play FLAC and other new formats on
     * devices that only support mp3 playback" — exactly the TV-box situation.
     * Default bitrate 192 kbit. Can also extract the audio track from a video.
     */
    @GET("getaudiolink")
    suspend fun getAudioLink(
        @Query("auth") auth: String,
        @Query("fileid") fileId: Long,
        @Query("abitrate") audioBitrate: Int? = null,
        @Query("forcedownload") forceDownload: Int? = null
    ): FileLinkResponse

    // ----------------------------------------------------------- thumbnails

    /**
     * Batch thumbnails — the docs call this "preferable than multiple calls to
     * getthumblink (even if pipelined)".
     *
     * @param fileIds comma-separated fileids
     * @param size WIDTHxHEIGHT; width 16-2048, height 16-1024, each divisible by 4 or 5
     * @param crop 1 for exactly the requested size (symmetric trim first)
     */
    @GET("getthumbslinks")
    suspend fun getThumbsLinks(
        @Query("auth") auth: String,
        @Query("fileids") fileIds: String,
        @Query("size") size: String,
        @Query("crop") crop: Int? = null,
        @Query("type") type: String? = null
    ): ThumbsLinksResponse

    /** Single thumbnail — used for album art, where there is only one file. */
    @GET("getthumblink")
    suspend fun getThumbLink(
        @Query("auth") auth: String,
        @Query("fileid") fileId: Long,
        @Query("size") size: String,
        @Query("crop") crop: Int? = null,
        @Query("type") type: String? = null
    ): FileLinkResponse
}
