package com.nick.stackcam

import org.json.JSONObject

/** Pure logic for the in-app updater (no Android APIs besides org.json), unit-tested on the JVM. */
object UpdateCore {

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkSize: Long,
        val changelog: String,
    )

    /** "v2.1" / "V2.1.0" / "2.1-beta" -> [2, 1] / [2, 1, 0] / [2, 1]. Returns null if no number found. */
    fun parseVersion(raw: String?): List<Int>? {
        if (raw == null) return null
        val s = raw.trim().removePrefix("v").removePrefix("V")
        val m = Regex("""^\d+(\.\d+)*""").find(s) ?: return null
        return m.value.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Numeric compare, missing parts are 0 (2.1 == 2.1.0, 2.10 > 2.9). */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a) ?: emptyList()
        val pb = parseVersion(b) ?: emptyList()
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(remoteTag: String, installedVersionName: String): Boolean =
        parseVersion(remoteTag) != null && compareVersions(remoteTag, installedVersionName) > 0

    /** Parses the body of GET /repos/{owner}/{repo}/releases/latest. */
    fun parseRelease(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val tag = o.optString("tag_name", "")
        require(tag.isNotBlank()) { "release has no tag_name" }
        var url: String? = null
        var name: String? = null
        var size = -1L
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                val u = a.optString("browser_download_url", "")
                if (n.endsWith(".apk", ignoreCase = true) && u.isNotBlank()) {
                    url = u; name = n; size = a.optLong("size", -1L); break
                }
            }
        }
        val body = if (o.isNull("body")) "" else o.optString("body", "")
        val version = tag.trim().removePrefix("v").removePrefix("V")
        return ReleaseInfo(tag, version, url, name, size, body.replace("\r\n", "\n").trim())
    }
}
