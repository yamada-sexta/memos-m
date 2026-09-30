package org.example.memosm.data.sync

/**
 * Line-level diff between two memo versions (local edit vs server version),
 * used by the conflict dialog to let the user see exactly what changed.
 */
enum class DiffLineType {
    SAME,    // Present in both versions
    ADDED,   // Only in the server version
    REMOVED  // Only in the local version
}

data class DiffLine(
    val text: String,
    val type: DiffLineType
)

/**
 * Computes a line-level diff of [local] vs [server] using a longest-common-
 * subsequence over line indices (no content hashing needed; memo bodies are
 * short, so the O(n*m) DP table is trivially small).
 *
 * Output order follows the classic unified-diff convention: for each block,
 * REMOVED (local-only) lines come first, then ADDED (server-only) lines, then
 * the common SAME lines.
 */
fun computeLineDiff(local: String, server: String): List<DiffLine> {
    val a = local.split("\n")
    val b = server.split("\n")
    val n = a.size
    val m = b.size

    // dp[i][j] = LCS length of a[i..] and b[j..]
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                result += DiffLine(a[i], DiffLineType.SAME)
                i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                result += DiffLine(a[i], DiffLineType.REMOVED)
                i++
            }
            else -> {
                result += DiffLine(b[j], DiffLineType.ADDED)
                j++
            }
        }
    }
    while (i < n) {
        result += DiffLine(a[i], DiffLineType.REMOVED)
        i++
    }
    while (j < m) {
        result += DiffLine(b[j], DiffLineType.ADDED)
        j++
    }
    return result
}
