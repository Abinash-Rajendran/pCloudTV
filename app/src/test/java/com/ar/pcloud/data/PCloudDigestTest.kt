package com.ar.pcloud.data

import com.ar.pcloud.data.repository.PCloudDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * The digest formula is the one thing in the auth path that is either exactly right
 * or silently broken: a wrong digest looks like "wrong password" from the outside.
 *
 * Docs: passworddigest = sha1( password + sha1( lowercase(username) ) + digest ),
 * each sha1 as 40 lowercase hex chars.
 */
class PCloudDigestTest {

    @Test
    fun `sha1Hex matches known vectors in lowercase hex`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", PCloudDigest.sha1Hex(""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PCloudDigest.sha1Hex("abc"))
    }

    @Test
    fun `sha1Hex is always 40 chars of lowercase hex`() {
        val hash = PCloudDigest.sha1Hex("pCloud TV")
        assertEquals(40, hash.length)
        assertEquals(hash.lowercase(), hash)
        assertEquals(true, hash.all { it in "0123456789abcdef" })
    }

    /** Independently recomputes the documented formula. */
    @Test
    fun `passwordDigest follows the documented formula`() {
        val username = "User@Example.COM"
        val password = "s3cret"
        val digest = "abcdef0123456789"

        val expected = sha1(password + sha1(username.lowercase()) + digest)

        assertEquals(expected, PCloudDigest.passwordDigest(username, password, digest))
    }

    @Test
    fun `username is lowercased before hashing`() {
        val lower = PCloudDigest.passwordDigest("user@example.com", "pw", "d")
        val upper = PCloudDigest.passwordDigest("USER@EXAMPLE.COM", "pw", "d")
        assertEquals(lower, upper)
    }

    /**
     * Guards against the bug this replaced: sha1(sha1(password + userhash) + digest).
     * That extra inner hash made every digest login fail.
     */
    @Test
    fun `is not the double-hashed variant`() {
        val username = "user@example.com"
        val password = "pw"
        val digest = "deadbeef"

        val wrong = sha1(sha1(password + sha1(username)) + digest)

        assertNotEquals(wrong, PCloudDigest.passwordDigest(username, password, digest))
    }

    @Test
    fun `different digests produce different results`() {
        val a = PCloudDigest.passwordDigest("user@example.com", "pw", "nonce-a")
        val b = PCloudDigest.passwordDigest("user@example.com", "pw", "nonce-b")
        assertNotEquals(a, b)
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
