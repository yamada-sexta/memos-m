package org.example.memosm.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.testcontainers.containers.GenericContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class MemosApiIntegrationTest(private val dockerImageName: String) {

    private lateinit var container: GenericContainer<*>
    private lateinit var baseUrl: String

    companion object {
        const val TEST_USERNAME = "admin"
        const val TEST_PASSWORD = "nsDevM5ETS8"
        const val TEST_DISPLAY_NAME = "Admin Display"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<String>> {
            return listOf(
                arrayOf("neosmemo/memos:canary"),
                arrayOf("neosmemo/memos:26.10.0"),
                arrayOf("neosmemo/memos:stable"),
                arrayOf("neosmemo/memos:0.30.0"),
                arrayOf("neosmemo/memos:0.29.0"),
                arrayOf("neosmemo/memos:0.28.0"),
                arrayOf("neosmemo/memos:0.27.1"),
                arrayOf("neosmemo/memos:0.27.0"),
                arrayOf("neosmemo/memos:0.26"),
                arrayOf("neosmemo/memos:0.26.2"),
                arrayOf("neosmemo/memos:0.26.1"),
                arrayOf("neosmemo/memos:0.26.0")
            )
        }
    }

    @Before
    fun setUp() {
        val logFile = java.io.File("/tmp/memos_debug.log")
        // Reset log file
        logFile.writeText("DEBUG: setUp() started for $dockerImageName\n")

        fun log(msg: String) {
            logFile.appendText("$msg\n")
            println(msg) // Also print to stdout just in case
        }

        log("DEBUG: CWD = ${java.io.File(".").absolutePath}")
        log("DEBUG: User = ${System.getProperty("user.name")}")

        try {
            // Start Memos container
            container = GenericContainer(dockerImageName)
            log("DEBUG: Attempting container.start() for $dockerImageName")

            container.withExposedPorts(5230).withEnv("MEMOS_DRIVER", "sqlite")
            if (dockerImageName == "neosmemo/memos:canary") {
                container.withEnv(
                    "MEMOS_DSN",
                    "file:/var/opt/memos/memos_prod.db?_txlock=immediate&"
                )
            }
            container.start()

            log("DEBUG: container.start() returned")

        } catch (e: Exception) {
            log("ERROR: Exception caught in setUp: ${e.message}")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            log(sw.toString())
            throw e
        }

        baseUrl = "http://${container.host}:${container.getMappedPort(5230)}/"
    }

    @After
    fun tearDown() {
        container.stop()
    }


    private var sharedApi: MemosApi? = null

    private suspend fun getAuthenticatedApi(): MemosApi {
        if (sharedApi != null) {
            return sharedApi!!
        }

        val api = MemosApiFactory.create(baseUrl, OkHttpClient())
        // The first registered user becomes the administrator on every supported version.
        api.createUser(org.example.memosm.model.UserSnapshot(
            username = TEST_USERNAME,
            password = TEST_PASSWORD,
            displayName = TEST_DISPLAY_NAME
        ))
        return createAuthenticatedApi(TEST_USERNAME, TEST_PASSWORD).also { sharedApi = it }
    }

    private suspend fun createAuthenticatedApi(
        username: String,
        password: String
    ): MemosApi {
        val logging = okhttp3.logging.HttpLoggingInterceptor { message ->
            println(message)
        }.apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
        }

        val client =
            OkHttpClient.Builder().addInterceptor(logging).readTimeout(30, TimeUnit.SECONDS).build()
        val api = MemosApiFactory.create(baseUrl, client)
        val token = loginAndCreateToken(api, baseUrl, username, password)

        val authClient = OkHttpClient.Builder().addInterceptor(logging).addInterceptor { chain ->
            val request =
                chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }.readTimeout(30, TimeUnit.SECONDS).build()

        return MemosApiFactory.create(baseUrl, authClient)
    }

    @Test
    fun testMemoLifecycle() = runBlocking {
        println("Running testMemoLifecycle")
        val api = getAuthenticatedApi()

        // 1. Create Memos
        val memo1 = api.createMemo(org.example.memosm.model.Memo(content = "Memo 1"))
        println("Created Memo 1: ${memo1.name}")
        assertNotNull(memo1.name)
        assertEquals("Memo 1", memo1.content)

        val memo2 = api.createMemo(org.example.memosm.model.Memo(content = "Memo 2"))
        println("Created Memo 2: ${memo2.name}")
        assertNotNull(memo2.name)
        assertEquals("Memo 2", memo2.content)

        // 2. List Memos
        val listResponse = api.listMemos()
        println("List response: ${listResponse.memos?.size} memos")
        val memos = listResponse.memos ?: emptyList()
        assertTrue(memos.any { it.name == memo1.name })
        assertTrue(memos.any { it.name == memo2.name })

        // 3. Edit Memo 1
        // Note: UpdateMask is required for some APIs, usually field paths comma separated
        val updatedMemo1 = api.updateMemo(
            memo1.name!!, org.example.memosm.model.Memo(content = "Memo 1 Updated"), "content"
        )
        println("Updated Memo 1 content: ${updatedMemo1.content}")
        assertEquals("Memo 1 Updated", updatedMemo1.content)

        // 4. List to verify update
        val listResponse2 = api.listMemos()
        val memos2 = listResponse2.memos ?: emptyList()
        val fetchedMemo1 = memos2.find { it.name == memo1.name }
        assertNotNull(fetchedMemo1)
        assertEquals("Memo 1 Updated", fetchedMemo1?.content)

        // 5. Delete Memo 2
        api.deleteMemo(memo2.name!!)
        println("Deleted Memo 2: ${memo2.name}")

        // 6. Verify Deletion
        val listResponse3 = api.listMemos()
        val memos3 = listResponse3.memos ?: emptyList()
        assertTrue("Memo 2 should be deleted", memos3.none { it.name == memo2.name })
        assertTrue("Memo 1 should still exist", memos3.any { it.name == memo1.name })
    }

    @Test
    fun testMemoSearchFilters() = runBlocking {
        val api = getAuthenticatedApi()
        val matching = api.createMemo(org.example.memosm.model.Memo(content = "Search needle #search #second"))
        val missingTag = api.createMemo(org.example.memosm.model.Memo(content = "Another needle #search"))
        val differentText = api.createMemo(org.example.memosm.model.Memo(content = "Other content #search #second"))
        val literal = api.createMemo(org.example.memosm.model.Memo(content = "Literal \"quoted\" C:\\notes\nsecond line #literal"))

        suspend fun assertSearch(query: String, tags: Set<String>, expected: Set<String?>) {
            val filter = api.buildMemoSearchFilter(MemoSearchFilter(query = query, tags = tags))
            val result = api.listMemos(filter = filter).memos.orEmpty().map { it.name }.toSet()
            assertEquals("Search on $dockerImageName using $filter", expected, result)
        }

        assertSearch("needle", emptySet(), setOf(matching.name, missingTag.name))
        assertSearch("", setOf("search", "second"), setOf(matching.name, differentText.name))
        assertSearch("needle", setOf("search", "second"), setOf(matching.name))
        assertSearch("absent", setOf("search"), emptySet())
        assertSearch("\"quoted\" C:\\notes", setOf("literal"), setOf(literal.name))
        assertSearch("notes\nsecond", emptySet(), setOf(literal.name))

        val filter = api.buildMemoSearchFilter(MemoSearchFilter(query = "needle", tags = setOf("search")))
        val firstPage = api.listMemos(pageSize = 1, filter = filter,
            orderBy = api.resolveMemoOrderBy(MemoOrderBy.NEWEST))
        assertEquals(1, firstPage.memos.orEmpty().size)
        assertTrue("Expected another search page on $dockerImageName", !firstPage.nextPageToken.isNullOrBlank())
        val secondPage = api.listMemos(pageSize = 1, pageToken = firstPage.nextPageToken, filter = filter,
            orderBy = api.resolveMemoOrderBy(MemoOrderBy.NEWEST))
        assertEquals(1, secondPage.memos.orEmpty().size)
        assertEquals("Search pagination on $dockerImageName",
            setOf(matching.name, missingTag.name),
            (firstPage.memos.orEmpty() + secondPage.memos.orEmpty()).map { it.name }.toSet())
    }

    @Test
    fun testMemoSearchDateRanges() = runBlocking {
        val api = getAuthenticatedApi()
        val memo = api.createMemo(org.example.memosm.model.Memo(content = "Date needle #search"))
        val created = requireNotNull(memo.createTime).toEpochMilliseconds() / 1000 * 1000
        val dayMillis = 86_400_000L
        val dayStart = created / dayMillis * dayMillis

        suspend fun assertRange(start: Long?, end: Long?, matches: Boolean) {
            val filter = api.buildMemoSearchFilter(MemoSearchFilter(
                query = "needle", tags = setOf("search"), startDateMillis = start, endDateMillis = end
            ))
            val names = api.listMemos(filter = filter).memos.orEmpty().map { it.name }.toSet()
            assertEquals("Date search on $dockerImageName using $filter",
                if (matches) setOf(memo.name) else emptySet<String?>(), names)
        }

        assertRange(dayStart, dayStart, true)
        assertRange(created, null, true) // Inclusive lower bound.
        assertRange(created + 1000, null, false)
        assertRange(null, dayStart, true) // Includes the rest of the selected day.
        assertRange(null, created - dayMillis, false) // Exclusive upper bound.
        assertRange(dayStart + dayMillis, dayStart + dayMillis, false)
        assertRange(dayStart - dayMillis, dayStart - dayMillis, false)
    }

    @Test
    fun testCurrentUserNameCompatibility() = runBlocking {
        println("Running testCurrentUserNameCompatibility")
        val api = getAuthenticatedApi()
        val currentUser = api.getCurrentSession().user
        assertNotNull("Expected current session user for $dockerImageName", currentUser)
        val user = currentUser!!

        val currentUserName = api.getUserResourceName(user)
        assertNotNull("Expected current session user name for $dockerImageName", currentUserName)
        assertEquals(user.name, currentUserName)
        assertEquals(TEST_USERNAME, user.username)
        assertEquals(TEST_DISPLAY_NAME, user.displayName)
        assertTrue(
            "Expected user resource name for $dockerImageName, got $currentUserName",
            currentUserName!!.startsWith("users/")
        )

        val creatorFilter = api.buildMemoCreatorFilter(user)
        assertNotNull("Expected creator filter for $dockerImageName", creatorFilter)

        val fetchedUser = api.getUser(currentUserName)
        assertEquals(currentUserName, fetchedUser.name)
        assertEquals(TEST_USERNAME, fetchedUser.username)
        assertEquals(TEST_DISPLAY_NAME, fetchedUser.displayName)

        val listedUser = api.listUsers().users.orEmpty().find { it.username == TEST_USERNAME }
        assertNotNull("Expected listUsers to include $TEST_USERNAME for $dockerImageName", listedUser)
        assertEquals(currentUserName, listedUser?.name)
        assertEquals(TEST_DISPLAY_NAME, listedUser?.displayName)

        val validDataUri =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        val userWithAvatar = api.updateUser(
            currentUserName,
            org.example.memosm.model.UserSnapshot(avatarUrl = validDataUri),
            api.constants.userMaskAvatarUrl
        )
        assertNotNull("Expected avatar update to return avatar URL for $dockerImageName", userWithAvatar.avatarUrl)

        val memo = api.createMemo(org.example.memosm.model.Memo(content = "User Name Memo"))
        assertEquals(
            "Expected created memo owner to match current user name for $dockerImageName",
            currentUserName,
            memo.creator
        )

        val postAuthor = api.getUser(memo.creator!!)
        assertEquals("Expected post author resource lookup to use memo.creator for $dockerImageName", memo.creator, postAuthor.name)
        assertEquals("Expected post author username for $dockerImageName", TEST_USERNAME, postAuthor.username)
        assertEquals("Expected post author display name for $dockerImageName", TEST_DISPLAY_NAME, postAuthor.displayName)
        assertEquals("Expected post author avatar URL for $dockerImageName", userWithAvatar.avatarUrl, postAuthor.avatarUrl)

        val filteredMemos = api.listMemos(filter = creatorFilter)
        assertTrue(
            "Expected creator filter to find memo for $dockerImageName using $creatorFilter",
            filteredMemos.memos.orEmpty().any { it.name == memo.name }
        )
    }

    @Test
    fun testNonCurrentMemoAuthorUserCompatibility() = runBlocking {
        println("Running testNonCurrentMemoAuthorUserCompatibility")
        val adminApi = getAuthenticatedApi()
        val otherUsername = "other"
        val otherPassword = "otherPassword123"
        val otherDisplayName = "Other Display"
        val validDataUri =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

        val createdOtherUser = adminApi.createUser(
            org.example.memosm.model.UserSnapshot(
                username = otherUsername,
                password = otherPassword,
                displayName = otherDisplayName
            )
        )
        val otherUserName = adminApi.getUserResourceName(createdOtherUser)
        assertNotNull("Expected created non-current user resource name for $dockerImageName", otherUserName)

        val otherWithAvatar = adminApi.updateUser(
            otherUserName!!,
            org.example.memosm.model.UserSnapshot(avatarUrl = validDataUri),
            adminApi.constants.userMaskAvatarUrl
        )

        val otherApi = createAuthenticatedApi(otherUsername, otherPassword)
        val otherMemo = otherApi.createMemo(
            org.example.memosm.model.Memo(
                content = "Non-current author memo",
                visibility = org.example.memosm.model.Visibility.PUBLIC
            )
        )
        assertEquals(
            "Expected memo creator to be the non-current user for $dockerImageName",
            otherUserName,
            otherMemo.creator
        )

        val fetchedAuthor = adminApi.getUser(otherMemo.creator!!)
        assertEquals(
            "Expected fetched non-current author resource name for $dockerImageName",
            otherMemo.creator,
            fetchedAuthor.name
        )
        assertEquals(
            "Expected fetched non-current author username for $dockerImageName",
            otherUsername,
            fetchedAuthor.username
        )
        assertEquals(
            "Expected fetched non-current author display name for $dockerImageName",
            otherDisplayName,
            fetchedAuthor.displayName
        )
        assertEquals(
            "Expected fetched non-current author avatar URL for $dockerImageName",
            otherWithAvatar.avatarUrl,
            fetchedAuthor.avatarUrl
        )
        assertTrue(
            "Expected fetched non-current author avatar URL to be non-empty for $dockerImageName",
            !fetchedAuthor.avatarUrl.isNullOrBlank()
        )

        val parentMemo = adminApi.createMemo(
            org.example.memosm.model.Memo(
                content = "Parent memo for non-current comment",
                visibility = org.example.memosm.model.Visibility.PUBLIC
            )
        )
        val parentMemoName = parentMemo.name!!
        val otherComment = otherApi.createMemoComment(
            parentMemoName,
            org.example.memosm.model.Memo(
                content = "Non-current author comment",
                visibility = org.example.memosm.model.Visibility.PUBLIC
            )
        )
        val listedComment = adminApi.listMemoComments(parentMemoName)
            .memos
            .orEmpty()
            .firstOrNull { it.name == otherComment.name }
        assertNotNull(
            "Expected non-current author comment to be listed for $dockerImageName",
            listedComment
        )

        val fetchedCommentAuthor = adminApi.getUser(listedComment!!.creator!!)
        assertEquals(
            "Expected fetched non-current comment author resource name for $dockerImageName",
            listedComment.creator,
            fetchedCommentAuthor.name
        )
        assertEquals(
            "Expected fetched non-current comment author display name for $dockerImageName",
            otherDisplayName,
            fetchedCommentAuthor.displayName
        )
        assertEquals(
            "Expected fetched non-current comment author avatar URL for $dockerImageName",
            otherWithAvatar.avatarUrl,
            fetchedCommentAuthor.avatarUrl
        )
    }

    @Test
    fun testHomeMemoListFetchCompatibility() = runBlocking {
        println("Running testHomeMemoListFetchCompatibility")
        val api = getAuthenticatedApi()
        val currentUser = api.getCurrentSession().user
        val creatorFilter = api.buildMemoCreatorFilter(currentUser)
        assertNotNull("Expected current session user to produce a creator filter", creatorFilter)

        val memo = api.createMemo(org.example.memosm.model.Memo(content = "Home Memo"))
        val orderBy = api.resolveMemoOrderBy(MemoOrderBy.PINNED_DESC)
        val response = api.listMemos(
            pageSize = 20,
            filter = creatorFilter,
            orderBy = orderBy
        )

        assertTrue(
            "Expected home memo list fetch to include created memo for $dockerImageName using filter=$creatorFilter orderBy=$orderBy",
            response.memos.orEmpty().any { it.name == memo.name }
        )
    }

    @Test
    fun testListMemosCreatorFilterPaginationCompatibility() = runBlocking {
        println("Running testListMemosCreatorFilterPaginationCompatibility")
        val api = getAuthenticatedApi()
        val currentUser = api.getCurrentSession().user

        val creatorFilter = api.buildMemoCreatorFilter(currentUser)
        assertNotNull("Expected current session user to produce a creator filter", creatorFilter)

        val expectedMemoNames = listOf(
            api.createMemo(org.example.memosm.model.Memo(content = "Paged Memo 1")).name,
            api.createMemo(org.example.memosm.model.Memo(content = "Paged Memo 2")).name
        ).filterNotNull().toSet()

        val collectedMemoNames = linkedSetOf<String>()
        val orderBy = api.resolveMemoOrderBy(MemoOrderBy.PINNED_DESC)
        var nextPageToken: String? = null
        var pageCount = 0

        do {
            val response = api.listMemos(
                pageSize = 1,
                pageToken = nextPageToken,
                filter = creatorFilter,
                orderBy = orderBy
            )

            response.memos.orEmpty()
                .mapNotNullTo(collectedMemoNames) { it.name }

            nextPageToken = response.nextPageToken?.takeIf { it.isNotBlank() }
            pageCount += 1
        } while (nextPageToken != null && pageCount < 10 && collectedMemoNames.size < expectedMemoNames.size)

        assertTrue(
            "Expected pagination to return created memos for $dockerImageName using filter=$creatorFilter, collected=$collectedMemoNames",
            collectedMemoNames.containsAll(expectedMemoNames)
        )
        assertTrue(
            "Expected at least one follow-up page for $dockerImageName",
            pageCount >= 2 || expectedMemoNames.size <= 1
        )
    }

    @Test
    fun testUserStatsAndProfile() = runBlocking {
        println("Running testUserStatsAndProfile")
        val api = getAuthenticatedApi()

        // ---------------------------------------------------------
        // 1. Setup & Stats (Existing Logic)
        // ---------------------------------------------------------
        api.createMemo(org.example.memosm.model.Memo(content = "Stats Memo 1 #tag1"))
        api.createMemo(org.example.memosm.model.Memo(content = "Stats Memo 2 #tag2"))

        val session = api.getCurrentSession()
        val user = session.user!!
        val userName = user.name!!

        println("Fetching stats for $userName")
        val stats = api.getUserStats(userName)

        println("User Stats: ${stats.totalMemoCount} memos")
        assertTrue((stats.totalMemoCount ?: 0) >= 2)
        val timestamps = stats.memoDisplayTimestamps.orEmpty()
        assertTrue("Expected non-empty stats timestamps for $dockerImageName", timestamps.isNotEmpty())
        assertTrue(
            "Expected at least one parseable stats timestamp for $dockerImageName: $timestamps",
            timestamps.any { parseStatsDate(it) != null }
        )
        assertNotNull(stats.memoTypeStats)
        assertNotNull(stats.tagCount)
        assertTrue(
            "Expected stats tagCount to include created tags for $dockerImageName: ${stats.tagCount}",
            stats.tagCount?.keys?.containsAll(listOf("tag1", "tag2")) == true
        )

        // ---------------------------------------------------------
        // 2. Test User Profile Masks (display_name, description, avatar_url)
        // ---------------------------------------------------------
        println("--- Testing User Profile Masks ---")

        // A. Mask: display_name
        val newDisplayName = "Updated Admin"
        val userWithNewName = api.updateUser(
            userName,
            org.example.memosm.model.UserSnapshot(displayName = newDisplayName),
            api.constants.userMaskDisplayName
        )
        assertEquals(newDisplayName, userWithNewName.displayName)

        // B. Mask: description
        val newDescription = "Kotlin Test Description"
        val userWithNewDesc = api.updateUser(
            userName,
            org.example.memosm.model.UserSnapshot(description = newDescription),
            api.constants.userMaskDescription
        )
        assertEquals(newDescription, userWithNewDesc.description)
        val validDataUri =
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        println("Testing Avatar Update with Data URI...")
        val userWithNewAvatar = api.updateUser(
            userName,
            org.example.memosm.model.UserSnapshot(avatarUrl = validDataUri),
            api.constants.userMaskAvatarUrl
        )
        println("Returned Avatar URL: ${userWithNewAvatar.avatarUrl}")

        val updatedAvatarUrl = userWithNewAvatar.avatarUrl
        assertNotNull(updatedAvatarUrl)
        assertTrue(
            "Avatar URL should start with /file/",
            updatedAvatarUrl!!.startsWith("/file/")
        )
        assertTrue(
            "Avatar URL should end with /avatar", updatedAvatarUrl.endsWith("/avatar")
        )

        // Revert Display Name (Clean up)
        api.updateUser(
            userName,
            org.example.memosm.model.UserSnapshot(displayName = TEST_DISPLAY_NAME),
            api.constants.userMaskDisplayName
        )

        // Note: Masks for 'password', 'email', and 'username' are available but
        // are skipped here as they may invalidate the current auth session.

        // ---------------------------------------------------------
        // 3. Test User Settings Masks (locale, memo_visibility)
        // ---------------------------------------------------------
        println("--- Testing User Settings Masks ---")
        val generalSettingKey = api.constants.userSettingGeneralKey

        // A. Mask: locale
        val newLocale = "zh-CN"
        val settingUpdateLocale = org.example.memosm.model.UserSetting(
            generalSetting = org.example.memosm.model.UserGeneralSetting(locale = newLocale)
        )
        val updatedLocaleSetting = api.updateUserSetting(
            userName, generalSettingKey, settingUpdateLocale, api.constants.userSettingLocaleMask
        )
        assertEquals(newLocale, updatedLocaleSetting.generalSetting?.locale)

        // B. Mask: memo_visibility
        val newVisibility = org.example.memosm.model.Visibility.PRIVATE
        val settingUpdateVis = org.example.memosm.model.UserSetting(
            generalSetting = org.example.memosm.model.UserGeneralSetting(memoVisibility = newVisibility)
        )
        val updatedVisSetting = api.updateUserSetting(
            userName,
            generalSettingKey,
            settingUpdateVis,
            api.constants.userSettingMemoVisibilityMask
        )
        assertEquals(newVisibility, updatedVisSetting.generalSetting?.memoVisibility)

        // ---------------------------------------------------------
        // 4. Test Shortcut Masks (title, filter)
        // ---------------------------------------------------------
        println("--- Testing Shortcut Masks ---")

        // Create initial shortcut
        val initialShortcut = org.example.memosm.model.Shortcut(
            title = "Original Title", filter = "tag in ['test']"
        )
        val createdShortcut = api.createShortcut(userName, initialShortcut)
        val shortcutId = createdShortcut.name?.substringAfterLast("/")!!

        // A. Update Mask: title
        val newTitle = "Updated Title"
        val updateTitleObj = org.example.memosm.model.Shortcut(title = newTitle)
        // Assuming updateShortcut signature: (user, id, body, mask)
        val updatedShortcutTitle = api.updateShortcut(
            userName, shortcutId, updateTitleObj, api.constants.shortcutMaskTitle
        )
        assertEquals(newTitle, updatedShortcutTitle.title)
        assertEquals("tag in ['test']", updatedShortcutTitle.filter) // Ensure filter didn't change

        // B. Update Mask: filter
        val newFilter = "tag in ['updated']"
        val updateFilterObj = org.example.memosm.model.Shortcut(filter = newFilter)
        val updatedShortcutFilter = api.updateShortcut(
            userName, shortcutId, updateFilterObj, api.constants.shortcutMaskFilter
        )
        assertEquals(newFilter, updatedShortcutFilter.filter)

        // Cleanup
        api.deleteShortcut(userName, shortcutId)

        // ---------------------------------------------------------
        // 5. Test Webhook Masks (display_name, url)
        // ---------------------------------------------------------
        println("--- Testing Webhook Masks ---")

        // Create initial webhook
        val initialWebhook = org.example.memosm.model.UserWebhook(
            url = "https://example.com/original", displayName = "Original Webhook"
        )
        val createdWebhook = api.createUserWebhook(userName, initialWebhook)
        val webhookId = createdWebhook.name?.substringAfterLast("/")!!

        // A. Update Mask: display_name
        val newWebhookName = "Updated Webhook Name"
        val updateWebhookNameObj = org.example.memosm.model.UserWebhook(
            url = "https://ignored.com", displayName = newWebhookName
        )
        // Assuming updateUserWebhook signature: (user, id, body, mask)
        val updatedWebhookName = api.updateUserWebhook(
            userName, webhookId, updateWebhookNameObj, api.constants.webhookMaskDisplayName
        )
        assertEquals(newWebhookName, updatedWebhookName.displayName)
        assertEquals(
            "https://example.com/original", updatedWebhookName.url
        ) // Ensure URL didn't change

        // B. Update Mask: url
        val newWebhookUrl = "https://example.com/updated"
        val updateWebhookUrlObj = org.example.memosm.model.UserWebhook(url = newWebhookUrl)
        val updatedWebhookUrl = api.updateUserWebhook(
            userName, webhookId, updateWebhookUrlObj, api.constants.webhookMaskUrl
        )
        assertEquals(newWebhookUrl, updatedWebhookUrl.url)

        // Cleanup
        try {
            api.deleteUserWebhook(userName, webhookId)
            println("Cleaned up webhook: $webhookId")
        } catch (e: Exception) {
            println("Warning: Failed to delete webhook $webhookId")
        }
    }

    private fun parseStatsDate(timestamp: String): LocalDate? {
        return try {
            ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
            } catch (_: DateTimeParseException) {
                try {
                    Instant.parse(timestamp).atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
                    } catch (_: DateTimeParseException) {
                        try {
                            LocalDate.parse(timestamp.take(10))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
    }
}
