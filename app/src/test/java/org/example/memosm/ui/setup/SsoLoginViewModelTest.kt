package org.example.memosm.ui.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.memosm.api.SsoException
import org.example.memosm.api.SsoFailure
import org.example.memosm.api.SsoTransaction
import org.example.memosm.model.FieldMapping
import org.example.memosm.model.IdentityProvider
import org.example.memosm.model.IdentityProviderConfig
import org.example.memosm.model.OAuth2Config
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SsoLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val redirect = "org.example.memosm.debug://oauth/callback"

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun transaction() = SsoTransaction.create("https://memos.example/", IdentityProvider(
        name = "identity-providers/company", type = "OAUTH2", title = "Company",
        config = IdentityProviderConfig(OAuth2Config(clientId = "client", authUrl = "https://idp.example/auth",
            tokenUrl = "", userInfoUrl = "", scopes = listOf("openid"), fieldMapping = FieldMapping("sub", "", "", "")))
    ), redirect)
    private fun callback(transaction: SsoTransaction) = "$redirect?state=${transaction.state}&code=code"

    @Test fun `callback exchanges once and returns personal token for original instance`() = runTest(dispatcher) {
        val complete = CompletableDeferred<String>()
        var exchanges = 0
        val model = SsoLoginViewModel { pending, credentials ->
            exchanges++
            assertEquals("https://memos.example/", pending.baseUrl)
            assertEquals("identity-providers/company", credentials.idpName)
            complete.await()
        }
        val transaction = transaction()
        model.begin(transaction)
        model.callback(callback(transaction))
        model.callback(callback(transaction))
        runCurrent()
        assertTrue(model.exchanging)
        assertEquals(1, exchanges)
        complete.complete("personal-token")
        runCurrent()
        assertEquals(SsoOutcome.Success("https://memos.example/", "personal-token"), model.outcome.value)
        assertNull(model.transaction)
        model.callback(callback(transaction))
        assertEquals(1, exchanges)
    }

    @Test fun `process death loses transaction and rejects callback`() {
        val previous = transaction()
        val freshModel = SsoLoginViewModel { _, _ -> error("No pending state must not authenticate") }
        freshModel.callback(callback(previous))
        assertEquals(SsoOutcome.Failure(SsoFailure.EXPIRED), freshModel.outcome.value)
    }

    @Test fun `invalid callback clears transaction without authenticating`() {
        val model = SsoLoginViewModel { _, _ -> error("Must validate state first") }
        model.begin(transaction())
        model.callback("$redirect?state=wrong&code=code")
        assertEquals(SsoOutcome.Failure(SsoFailure.INVALID_CALLBACK), model.outcome.value)
        assertNull(model.transaction)
    }

    @Test fun `cancellation stops exchange and prevents account success`() = runTest(dispatcher) {
        var cancelled = false
        val model = SsoLoginViewModel { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        val pending = transaction()
        model.begin(pending)
        model.callback(callback(pending))
        runCurrent()
        model.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(model.exchanging)
        assertNull(model.transaction)
        assertEquals(SsoOutcome.Cancelled, model.outcome.value)
    }

    @Test fun `exchange failures are retryable and never return session credentials`() = runTest(dispatcher) {
        listOf(SsoFailure.SIGN_IN, SsoFailure.TOKEN).forEach { reason ->
            val model = SsoLoginViewModel { _, _ -> throw SsoException(reason) }
            val pending = transaction()
            model.begin(pending)
            model.callback(callback(pending))
            runCurrent()
            assertEquals(SsoOutcome.Failure(reason), model.outcome.value)
            assertNull(model.transaction)
        }
    }
}
