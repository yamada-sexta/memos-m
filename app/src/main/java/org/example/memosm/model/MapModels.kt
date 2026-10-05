package org.example.memosm.model

enum class MapCapability { UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class MapScope { MEMOS, EXPLORE, ALL }

/** A pin may represent one place or all the places in a cluster. */
data class MapPlace(val location: Location, val memoLocations: List<Location> = listOf(location))

fun memosAtMapPlace(memos: List<Memo>, place: MapPlace): List<Memo> {
    val coordinates = place.memoLocations.filter { it.hasValidCoordinates() }
        .map { it.latitude to it.longitude }.toSet()
    return memos.distinctBy { it.name }.filter {
        it.location.hasValidCoordinates() && (it.location!!.latitude to it.location.longitude) in coordinates
    }
}

/** A successful capability check belongs to an instance, never just an API adapter. */
data class MapSupport(
    val hostUrl: String,
    val version: String?,
    val capability: MapCapability
)

fun normalizeMapHost(host: String): String = host.trim().trimEnd('/').removeSuffix("/api/v1")

fun Location?.hasValidCoordinates(): Boolean {
    val latitude = this?.latitude ?: return false
    val longitude = this.longitude ?: return false
    return latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

fun Memo.belongsOnMap(scope: MapScope, creator: String?): Boolean =
    !name.isNullOrBlank() && location.hasValidCoordinates() && parent.isNullOrBlank() &&
        state != MemoState.ARCHIVED && when (scope) {
            MapScope.MEMOS -> creator != null && this.creator == creator
            MapScope.EXPLORE -> visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED
            MapScope.ALL -> (creator != null && this.creator == creator) ||
                visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED
        }

/** Keep the newest memo first; pins and the selection panel share this ordering. */
fun sortedMapMemos(memos: Collection<Memo>): List<Memo> = memos.distinctBy { it.name }
    .sortedWith(compareByDescending<Memo> { it.displayTime ?: it.createTime ?: it.updateTime }.thenBy { it.name })

fun sharedMapLabel(memos: List<Memo>): String? {
    val labels = memos.map { it.location?.placeholder?.trim().orEmpty() }
    return if (memos.map { it.creator }.distinct().size <= 1 || labels.distinct().size == 1)
        labels.firstOrNull()?.takeIf { it.isNotBlank() } else null
}

/** New writing must not borrow another author's place label. */
fun ownMapLocation(location: Location, memos: List<Memo>, creator: String?): Location = location.copy(
    placeholder = memos.firstOrNull {
        creator != null && it.creator == creator && it.location?.latitude == location.latitude &&
            it.location?.longitude == location.longitude && !it.location?.placeholder.isNullOrBlank()
    }?.location?.placeholder
)
