package org.example.memosm.api

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import okio.ByteString.Companion.toByteString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.example.memosm.model.IdentityProvider
import org.example.memosm.model.SSOCredentials

enum class SsoFailure { INVALID_PROVIDER, INVALID_CALLBACK, EXPIRED, DENIED, BROWSER_UNAVAILABLE, SIGN_IN, TOKEN }

class SsoException(val failure: SsoFailure) : Exception(failure.name)

/** In-memory, single-use OAuth transaction. No credentials are persisted across process death. */
class SsoTransaction private constructor(
    val baseUrl: String,
    private val providerName: String,
    val redirectUri: String,
    val state: String,
    private val verifier: String,
    val authorizationUrl: String,
    private val createdAt: Long
) {
    private var consumed = false

    @Synchronized
    fun consume(callback: String, now: Long = System.currentTimeMillis()): SSOCredentials {
        if (consumed) throw SsoException(SsoFailure.INVALID_CALLBACK)
        consumed = true
        if (now < createdAt || now - createdAt >= EXPIRY_MS) throw SsoException(SsoFailure.EXPIRED)
        if (!matchesRedirect(callback, redirectUri)) throw SsoException(SsoFailure.INVALID_CALLBACK)
        val query = try {
            URI(callback).rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }.groupBy({ it.first }, { it.second })
        } catch (_: Exception) {
            throw SsoException(SsoFailure.INVALID_CALLBACK)
        }
        if (query["state"] != listOf(state)) throw SsoException(SsoFailure.INVALID_CALLBACK)
        if (query.containsKey("error")) throw SsoException(SsoFailure.DENIED)
        val code = query["code"]?.singleOrNull()?.takeIf(String::isNotBlank)
            ?: throw SsoException(SsoFailure.INVALID_CALLBACK)
        return SSOCredentials(providerName, code, redirectUri, verifier)
    }

    companion object {
        const val EXPIRY_MS = 10 * 60 * 1000L

        fun create(
            baseUrl: String, provider: IdentityProvider, redirectUri: String,
            now: Long = System.currentTimeMillis()
        ): SsoTransaction {
            val normalizedUrl = normalizeLoginServerUrl(baseUrl) ?: throw SsoException(SsoFailure.INVALID_PROVIDER)
            val name = provider.name.orEmpty()
            val config = provider.config.oauth2Config
            val authUrl = config?.authUrl?.toHttpUrlOrNull()
            if (provider.type != "OAUTH2" || !name.startsWith("identity-providers/") ||
                name.removePrefix("identity-providers/").let { it.isBlank() || '/' in it } ||
                config == null || config.clientId.isBlank() || authUrl == null ||
                authUrl.username.isNotEmpty() || authUrl.password.isNotEmpty() || authUrl.fragment != null
            ) throw SsoException(SsoFailure.INVALID_PROVIDER)
            val state = randomValue()
            val verifier = randomValue()
            val url = authUrl.newBuilder()
                .setQueryParameter("client_id", config.clientId)
                .setQueryParameter("redirect_uri", redirectUri)
                .setQueryParameter("response_type", "code")
                .setQueryParameter("scope", config.scopes.orEmpty().joinToString(" "))
                .setQueryParameter("state", state)
                .setQueryParameter("code_challenge", challenge(verifier))
                .setQueryParameter("code_challenge_method", "S256")
                .build().toString()
            return SsoTransaction(normalizedUrl, name, redirectUri, state, verifier, url, now)
        }

        internal fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII)).toByteString().base64Url().trimEnd('=')

        private fun randomValue(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .toByteString().base64Url().trimEnd('=')

        fun matchesRedirect(callback: String, redirectUri: String): Boolean = try {
            val actual = URI(callback)
            val expected = URI(redirectUri)
            actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority &&
                actual.rawPath == expected.rawPath && actual.rawFragment == null
        } catch (_: Exception) { false }
    }
}
