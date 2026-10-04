package org.example.memosm.data.cache

/** Lightweight identity/freshness snapshot for a remote-existence check. */
data class CachedMemoCandidate(val name: String, val cachedAt: Long)
