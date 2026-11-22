package com.cleantv.phone

import android.content.Context

enum class SortBy(val label: String, val forPictures: Boolean = true) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    BIGGEST("Size: largest first"),
    SMALLEST("Size: smallest first"),
    LONGEST("Length: longest first", forPictures = false),
    SHORTEST("Length: shortest first", forPictures = false),
}

enum class Show(val label: String) {
    ALL("All"),
    MOVIES("Movies (40 min+)"),
    CLIPS("Short videos (< 40 min)"),
    UNWATCHED("Not started"),
    IN_PROGRESS("Continue watching"),
    WATCHED("Watched"),
}

/** Current sort / category / filter / search choice for one tab group (videos or pictures). */
data class Filter(
    val sort: SortBy = SortBy.NEWEST,
    val folder: String? = null,
    val show: Show = Show.ALL,
    val query: String = "",
) {
    val isDefault get() = this == Filter()

    fun apply(items: List<Media>, history: Map<String, Progress>, keepOrder: Boolean = false): List<Media> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = items.filter { m ->
            (folder == null || m.folder == folder) &&
                words.all { m.name.lowercase().contains(it) } &&
                when (show) {
                    Show.ALL -> true
                    Show.MOVIES -> m.durationMs >= MOVIE_MS
                    Show.CLIPS -> m.durationMs < MOVIE_MS
                    Show.UNWATCHED -> history[m.key] == null
                    Show.IN_PROGRESS -> history[m.key]?.resumable == true
                    Show.WATCHED -> history[m.key]?.finished == true
                }
        }
        if (keepOrder) return filtered
        return when (sort) {
            SortBy.NEWEST -> filtered.sortedByDescending { it.dateAdded }
            SortBy.OLDEST -> filtered.sortedBy { it.dateAdded }
            SortBy.NAME_AZ -> filtered.sortedBy { it.name.lowercase() }
            SortBy.NAME_ZA -> filtered.sortedByDescending { it.name.lowercase() }
            SortBy.BIGGEST -> filtered.sortedByDescending { it.size }
            SortBy.SMALLEST -> filtered.sortedBy { it.size }
            SortBy.LONGEST -> filtered.sortedByDescending { it.durationMs }
            SortBy.SHORTEST -> filtered.sortedBy { it.durationMs }
        }
    }

    fun save(ctx: Context, group: String) {
        ctx.getSharedPreferences("filters", Context.MODE_PRIVATE).edit()
            .putString("$group.sort", sort.name)
            .putString("$group.folder", folder)
            .putString("$group.show", show.name)
            .apply()
    }

    companion object {
        const val MOVIE_MS = 40 * 60 * 1000L

        /** Restores the saved sort/category/filter (search text is not remembered). */
        fun load(ctx: Context, group: String): Filter {
            val sp = ctx.getSharedPreferences("filters", Context.MODE_PRIVATE)
            return Filter(
                sort = runCatching { SortBy.valueOf(sp.getString("$group.sort", null)!!) }.getOrDefault(SortBy.NEWEST),
                folder = sp.getString("$group.folder", null),
                show = runCatching { Show.valueOf(sp.getString("$group.show", null)!!) }.getOrDefault(Show.ALL),
            )
        }
    }
}
