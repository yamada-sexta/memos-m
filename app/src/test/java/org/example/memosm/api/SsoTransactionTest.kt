package org.example.memosm.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.example.memosm.model.IdentityProvider
import org.example.memosm.model.IdentityProviderConfig
import org.example.memosm.model.OAuth2Config
import org.example.memosm.model.FieldMapping
import org.junit.Assert.*
import org.junit.Test

class SsoTransactionTest {
    private val redirect = "org.example.memosm.debug://oauth/callback"
    private val provider = IdentityProvider(
        name = "identity-providers/company", type = "OAUTH2", title = "Company",
        config = IdentityProviderConfig(OAuth2Config(
            clientId = "client +&", authUrl = "https://idp.example/authorize?audience=memos&state=old",
            tokenUrl = "", userInfoUrl = "", scopes = listOf("openid", "profile"),
            fieldMapping = FieldMapping("sub", "name", "email", "picture")
        ))
    )
    private fun transaction() = SsoTransaction.create("https://memos.example/notes/api/v1/", provider, redirect, 1000)

    @Test fun `authorization preserves provider parameters and encodes OAuth values`() {
        val transaction = transaction()
        val url = transaction.authorizationUrl.toHttpUrl()
        assertEquals("memos", url.queryParameter("audience"))
        assertEquals("client +&", url.queryParameter("client_id"))
        assertEquals(redirect, url.queryParameter("redirect_uri"))
        assertEquals("openid profile", url.queryParameter("scope"))
        assertEquals(listOf(transaction.state), url.queryParameterValues("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("https://memos.example/notes/", transaction.baseUrl)
        val credentials = transaction.consume("$redirect?state=${transaction.state}&code=code%2B%26", 1001)
        assertEquals("code+&", credentials.code)
        assertEquals("identity-providers/company", credentials.idpName)
        assertEquals(43, credentials.codeVerifier.length)
        assertTrue(credentials.codeVerifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(SsoTransaction.challenge(credentials.codeVerifier), url.queryParameter("code_challenge"))
    }

    @Test fun `PKCE matches RFC 7636 test vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SsoTransaction.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `state and verifier are fresh for every attempt`() {
        val first = transaction()
        val second = transaction()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.authorizationUrl.toHttpUrl().queryParameter("code_challenge"),
            second.authorizationUrl.toHttpUrl().queryParameter("code_challenge"))
    }

    @Test fun `callback is consumed once even after invalid state`() {
        val transaction = transaction()
        fails(SsoFailure.INVALID_CALLBACK) { transaction.consume("$redirect?state=wrong&code=code", 1001) }
        fails(SsoFailure.INVALID_CALLBACK) { transaction.consume("$redirect?state=${transaction.state}&code=code", 1001) }
        val valid = transaction()
        valid.consume("$redirect?state=${valid.state}&code=code", 1001)
        fails(SsoFailure.INVALID_CALLBACK) { valid.consume("$redirect?state=${valid.state}&code=code", 1001) }
    }

    @Test fun `malformed and ambiguous callbacks are rejected`() {
        listOf(
            "?code=code", "?state=STATE", "?state=STATE&state=STATE&code=code",
            "?state=STATE&code=one&code=two", "?state=STATE&code=", "?state=STATE&code=%ZZ"
        ).forEach { suffix ->
            val transaction = transaction()
            fails(SsoFailure.INVALID_CALLBACK) { transaction.consume(redirect + suffix.replace("STATE", transaction.state), 1001) }
        }
        listOf(
            "org.example.memosm://oauth/callback", "$redirect/extra", "org.example.memosm.debug://other/callback",
            "org.example.memosm.debug://user@oauth/callback", "org.example.memosm.debug://oauth:123/callback"
        ).forEach { wrong ->
            val transaction = transaction()
            fails(SsoFailure.INVALID_CALLBACK) { transaction.consume("$wrong?state=${transaction.state}&code=code", 1001) }
        }
        assertFalse(SsoTransaction.matchesRedirect("$redirect?code=code#fragment", redirect))
    }

    @Test fun `expired and denied flows never produce credentials`() {
        val expired = transaction()
        fails(SsoFailure.EXPIRED) { expired.consume("$redirect?state=${expired.state}&code=code", 1000 + SsoTransaction.EXPIRY_MS) }
        val denied = transaction()
        fails(SsoFailure.DENIED) { denied.consume("$redirect?state=${denied.state}&error=access_denied", 1001) }
        val forgedError = transaction()
        fails(SsoFailure.INVALID_CALLBACK) { forgedError.consume("$redirect?state=wrong&error=access_denied", 1001) }
    }

    @Test fun `invalid provider configuration is rejected`() {
        listOf(provider.copy(type = "SAML"), provider.copy(name = null),
            provider.copy(config = IdentityProviderConfig()),
            provider.copy(config = IdentityProviderConfig(provider.config.oauth2Config!!.copy(authUrl = "javascript:alert(1)")))
        ).forEach { invalid ->
            fails(SsoFailure.INVALID_PROVIDER) { SsoTransaction.create("https://memos.example/", invalid, redirect) }
        }
    }

    @Test fun `server URL normalization preserves prefixes and accepts API roots`() {
        assertEquals("https://memos.example/", normalizeLoginServerUrl(" memos.example "))
        assertEquals("http://localhost:8080/notes/", normalizeLoginServerUrl("http://localhost:8080/notes/api/v1"))
        assertEquals("https://memos.example/a%20b/", normalizeLoginServerUrl("https://memos.example/a%20b/"))
        listOf("", "ftp://memos.example", "https://user:password@memos.example", "https://memos.example/?token=secret",
            "https://memos.example/#fragment").forEach { assertNull(normalizeLoginServerUrl(it)) }
    }

    private fun fails(expected: SsoFailure, block: () -> Unit) {
        val error = assertThrows(SsoException::class.java, block)
        assertEquals(expected, error.failure)
    }
}
