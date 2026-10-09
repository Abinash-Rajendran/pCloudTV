package com.ar.pcloud.data.api

import com.ar.pcloud.data.model.PCloudResponse

/**
 * A failed pCloud call. Carries the numeric code so callers can branch on it
 * (region redirect, 2FA, thumb-not-supported) instead of matching on strings.
 */
class PCloudException(
    val code: Int,
    override val message: String,
    val serverMessage: String? = null,
    /**
     * Set when the server asked for a 2FA code by message rather than by one of
     * [TWO_FACTOR_CODES] — see [looksLikeTwoFactorPrompt].
     */
    private val twoFactorByMessage: Boolean = false
) : Exception(message) {

    val isAuthFailure: Boolean get() = code in AUTH_FAILURE_CODES
    val needsTwoFactor: Boolean get() = code in TWO_FACTOR_CODES || twoFactorByMessage
    val isWrongRegion: Boolean get() = code == CODE_USER_IN_EU || code == CODE_USER_IN_US
    val isRetryable: Boolean get() = code == CODE_NO_SERVERS || code == CODE_INTERNAL_ERROR

    companion object {
        const val CODE_LOGIN_REQUIRED = 1000
        const val CODE_NO_PATH_OR_FOLDERID = 1002
        const val CODE_NO_FILEID_OR_PATH = 1004
        const val CODE_THUMB_UNSUPPORTED_TYPE = 1014
        const val CODE_INVALID_THUMB_SIZE = 1015
        const val CODE_NO_FILEIDS = 1038
        const val CODE_LOGIN_FAILED = 2000
        const val CODE_ACCESS_DENIED = 2003
        const val CODE_DIR_NOT_FOUND = 2005
        const val CODE_FILE_NOT_FOUND = 2009
        const val CODE_USER_IN_EU = 2011
        const val CODE_USER_IN_US = 2012
        const val CODE_NOT_A_VIDEO = 2044
        const val CODE_TFA_REQUIRED = 2064
        const val CODE_TFA_INVALID = 2229
        const val CODE_THUMB_FAILED = 3001
        const val CODE_TOO_MANY_TRIES = 4000
        const val CODE_INTERNAL_ERROR = 5000
        const val CODE_NO_SERVERS = 5002

        private val AUTH_FAILURE_CODES = setOf(CODE_LOGIN_REQUIRED, CODE_LOGIN_FAILED)
        private val TWO_FACTOR_CODES = setOf(CODE_TFA_REQUIRED, CODE_TFA_INVALID)

        /**
         * pCloud does not always use 2064 to ask for a 2FA code. Accounts with
         * two-factor enabled can instead be refused with a 1xxx "missing required
         * parameter" error whose message names the `code` parameter — the same
         * shape as 1002/1004/1038, but meaning "send the 6-digit code", not
         * "this app built a bad request". Matching the message is the only signal
         * available, so it is kept narrow: it must mention the `code` parameter.
         */
        private val MISSING_CODE_MESSAGE =
            Regex("""provide\s+'?code'?""", RegexOption.IGNORE_CASE)

        private fun looksLikeTwoFactorPrompt(code: Int, serverMessage: String?): Boolean =
            code in 1000..1999 &&
                serverMessage != null &&
                MISSING_CODE_MESSAGE.containsMatchIn(serverMessage)

        /**
         * @param onLoginPath 2011/2012 are overloaded: on getfilelink they mean
         *   "speed limit too low", on userinfo they mean "wrong data center".
         */
        fun from(response: PCloudResponse, onLoginPath: Boolean = false): PCloudException =
            of(response.result, response.error, onLoginPath)

        fun of(code: Int, serverMessage: String?, onLoginPath: Boolean = false): PCloudException {
            if (looksLikeTwoFactorPrompt(code, serverMessage)) {
                return PCloudException(
                    code = code,
                    message = "This account uses two-factor authentication. Enter your " +
                        "6-digit code, or sign in with an API auth token.",
                    serverMessage = serverMessage,
                    twoFactorByMessage = true
                )
            }

            val message = when (code) {
                CODE_LOGIN_REQUIRED -> "Your session has expired. Please sign in again."
                CODE_LOGIN_FAILED -> "Wrong email or password."
                CODE_TFA_REQUIRED ->
                    "This account uses two-factor authentication. Enter your 6-digit code, " +
                        "or sign in with an API auth token."
                CODE_TFA_INVALID -> "That 6-digit code is not valid. Try the current one."
                CODE_TOO_MANY_TRIES ->
                    "Too many sign-in attempts from this network. Wait a few minutes and retry."
                CODE_ACCESS_DENIED -> "You do not have permission to open this."
                CODE_DIR_NOT_FOUND -> "That folder no longer exists."
                CODE_FILE_NOT_FOUND -> "That file no longer exists."
                CODE_NOT_A_VIDEO -> "This file is not a video pCloud can stream."
                CODE_THUMB_UNSUPPORTED_TYPE, CODE_THUMB_FAILED ->
                    "No preview available for this file."
                CODE_INVALID_THUMB_SIZE -> "Invalid thumbnail size requested."
                CODE_NO_SERVERS -> "pCloud has no storage server available right now. Try again."
                CODE_INTERNAL_ERROR -> "pCloud reported an internal error. Try again."
                CODE_USER_IN_EU ->
                    if (onLoginPath) "This account lives in the Europe data center. Switching region."
                    else "Requested speed limit is too low."
                CODE_USER_IN_US ->
                    if (onLoginPath) "This account lives in the United States data center. Switching region."
                    else serverMessage ?: "pCloud error $code"
                CODE_NO_PATH_OR_FOLDERID, CODE_NO_FILEID_OR_PATH, CODE_NO_FILEIDS ->
                    "Internal request error (pCloud $code)."
                else -> serverMessage ?: "pCloud error $code"
            }
            return PCloudException(code, message, serverMessage)
        }
    }
}
