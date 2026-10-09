package com.ar.pcloud.data.repository

import java.security.MessageDigest
import java.util.Locale

/**
 * pCloud digest authentication, so the password itself never goes over the wire.
 *
 * The docs give exactly one formula:
 *
 *     passworddigest = sha1( password + sha1( lowercase(username) ) + digest )
 *
 * with every sha1 "40 byte hex with lowercase letters". Kept separate from the
 * repository so it can be unit-tested without an Android Context.
 */
object PCloudDigest {

    fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun passwordDigest(username: String, password: String, digest: String): String =
        sha1Hex(password + sha1Hex(username.lowercase(Locale.US)) + digest)
}
