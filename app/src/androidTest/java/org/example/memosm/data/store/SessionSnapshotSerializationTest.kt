package org.example.memosm.data.store

import kotlin.time.Instant
import org.example.memosm.data.offline.SessionSnapshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotSerializationTest {
    /**
     * Run against the R8-minified canary app with:
     * `./gradlew -PminifiedSnapshotTests=true :app:connectedCanaryAndroidTest
     * -Pandroid.testInstrumentationRunnerArguments.class=org.example.memosm.data.store.SessionSnapshotSerializationTest`
     */
    @Test
    fun readsActivityInstantFromPreMinificationSnapshot() {
        val legacySnapshot = """
            {
              "activities": [{
                "name": "activities/1",
                "createTime": {
                  "epochSeconds": 1800000000,
                  "nanosecondsOfSecond": 123456789
                }
              }]
            }
        """.trimIndent()

        val snapshot = SnapshotGson.gson.fromJson(legacySnapshot, SessionSnapshotData::class.java)

        assertEquals(
            Instant.fromEpochSeconds(1_800_000_000L, 123_456_789L),
            snapshot.activities.single().createTime
        )
        assertTrue(SnapshotGson.gson.toJson(snapshot).contains("\"createTime\":\""))
    }
}
