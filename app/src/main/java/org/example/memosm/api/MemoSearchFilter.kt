package org.example.memosm.api

/** Search criteria kept independently of the connected server's CEL syntax. */
data class MemoSearchFilter(
    val query: String = "",
    val tags: Set<String> = emptySet(),
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null
)

/** Builds a CEL search filter using the active server's timestamp syntax. */
fun MemosApi.buildMemoSearchFilter(
    filter: MemoSearchFilter = MemoSearchFilter()
): String? {
    fun timestamp(millis: Long): String {
        val seconds = millis / 1000
        return celTimestamp(seconds)
    }

    val filters = buildList {
        if (filter.query.isNotBlank()) add("content.contains(${GsonProvider.gson.toJson(filter.query)})")
        filter.tags.forEach { add("tag in [${GsonProvider.gson.toJson(it)}]") }
        filter.startDateMillis?.let { add("created_ts >= ${timestamp(it)}") }
        // Include the selected end date by excluding the following midnight.
        filter.endDateMillis?.let { add("created_ts < ${timestamp(it + 86_400_000L)}") }
    }
    return filters.takeIf { it.isNotEmpty() }?.joinToString(" && ")
}
