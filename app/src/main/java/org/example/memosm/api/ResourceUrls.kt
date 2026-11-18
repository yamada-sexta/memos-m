package org.example.memosm.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resource paths are relative to the configured server, including its subpath. */
fun resolveResourceUrl(
    hostUrl: String,
    resource: String?,
): String? {
    if (resource.isNullOrBlank()) return null
    resource.toHttpUrlOrNull()?.let { return it.toString() }
    return (hostUrl.trimEnd('/') + "/")
        .toHttpUrlOrNull()
        ?.resolve(resource.trimStart('/'))
        ?.toString()
}

internal fun sameOrigin(
    server: String,
    resource: String,
): Boolean {
    val first = server.toHttpUrlOrNull() ?: return false
    val second = resource.toHttpUrlOrNull() ?: return false
    return first.scheme == second.scheme && first.host == second.host && first.port == second.port
}

internal fun resourceAuthorization(
    server: String,
    resource: Any?,
    token: String?,
): String? = token?.takeIf { it.isNotBlank() && resource != null && sameOrigin(server, resource.toString()) }?.let { "Bearer $it" }

/** A network interceptor checks every hop, including redirects and playlist subrequests. */
internal fun resourceAuthentication(
    server: String,
    token: String?,
): okhttp3.Interceptor =
    okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder().removeHeader("Authorization")
        resourceAuthorization(server, chain.request().url, token)?.let { request.header("Authorization", it) }
        chain.proceed(request.build())
    }
