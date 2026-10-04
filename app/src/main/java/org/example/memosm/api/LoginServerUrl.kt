package org.example.memosm.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Accept an instance URL or API root, preserving reverse-proxy path prefixes. */
fun normalizeLoginServerUrl(value: String): String? {
    val input = value.trim()
    if (input.isEmpty()) return null
    val url = (if (input.contains("://")) input else "https://$input").toHttpUrlOrNull()
        ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = url.encodedPath.trimEnd('/').removeSuffix("/api/v1") + "/"
    return url.newBuilder().encodedPath(path).build().toString()
}
