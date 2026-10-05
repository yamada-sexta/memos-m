package org.example.memosm.api

import org.example.memosm.model.Memo
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoDeserializationTest {
    private val gson = GsonProvider.gson

    @Test
    fun missingOrInvalidVisibilityUsesMemoConstructorDefault() {
        val values = listOf(null, "null", "\"\"", "\"unknown\"")
        for (value in values) {
            val visibility = value?.let { ",\"visibility\":$it" }.orEmpty()
            val memo = gson.fromJson("{\"content\":\"text\"$visibility}", Memo::class.java)
            assertEquals(Visibility.PRIVATE, memo.visibility)
            assertEquals("text", memo.content)
        }
    }

    @Test
    fun allRecognizedVisibilityValuesRoundTrip() {
        for (visibility in Visibility.entries) {
            val memo = Memo(content = "text", visibility = visibility)
            assertEquals(memo, gson.fromJson(gson.toJson(memo), Memo::class.java))
        }
    }

    @Test
    fun optionalUserSettingVisibilityStaysAbsent() {
        val settings = gson.fromJson("{\"memoVisibility\":null}", UserGeneralSetting::class.java)
        assertNull(settings.memoVisibility)
    }
}
