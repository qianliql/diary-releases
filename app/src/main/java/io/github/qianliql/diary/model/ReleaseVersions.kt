package io.github.qianliql.diary.model

import org.json.JSONArray

object ReleaseVersions {
    fun newer(releases: JSONArray, current: Long): String? {
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft")) return@mapNotNull null
            val match = Regex("^v(\\d+)\\.(\\d{1,2})\\.(\\d{1,2})$").matchEntire(release.optString("tag_name")) ?: return@mapNotNull null
            val major = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            if (major > 200_000) return@mapNotNull null
            val code = 1 + major * 10000 + match.groupValues[2].toLong() * 100 + match.groupValues[3].toLong()
            val url = release.optString("html_url")
            if (code <= current || !url.startsWith("https://github.com/qianliql/diary-releases/releases/tag/")) null else code to url
        }.maxByOrNull { it.first }?.second
    }
}
