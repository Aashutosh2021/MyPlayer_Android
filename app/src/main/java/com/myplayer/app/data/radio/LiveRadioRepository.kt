package com.myplayer.app.data.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class LiveRadioStation(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String?,
    val country: String,
    val state: String,
    val tags: List<String>,
    val bitrateKbps: Int
)

/**
 * OpenTune Live Radio Directory (radio-browser.info, 50,000+ Free Stations) ported to MyPlayer.
 */
@Singleton
class LiveRadioRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private val MIRRORS = listOf(
            "https://de1.api.radio-browser.info",
            "https://fr1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info"
        )
        private const val USER_AGENT = "MyPlayer/6.8 (https://myplayer-660ee.web.app)"
    }

    suspend fun getTopCountryStations(
        countryCode: String = Locale.getDefault().country.ifBlank { "IN" },
        limit: Int = 40
    ): List<LiveRadioStation> = queryStations(
        path = "/json/stations/search",
        params = mapOf(
            "countrycode" to countryCode.uppercase(Locale.ROOT),
            "order" to "clickcount",
            "reverse" to "true",
            "limit" to limit.toString()
        )
    )

    suspend fun searchStations(query: String, tag: String? = null, limit: Int = 40): List<LiveRadioStation> {
        val params = mutableMapOf(
            "order" to "clickcount",
            "reverse" to "true",
            "limit" to limit.toString()
        )
        if (query.isNotBlank()) params["name"] = query.trim()
        if (!tag.isNullOrBlank()) params["tag"] = tag.trim()
        return queryStations("/json/stations/search", params)
    }

    private suspend fun queryStations(path: String, params: Map<String, String>): List<LiveRadioStation> =
        withContext(Dispatchers.IO) {
            for (mirror in MIRRORS) {
                try {
                    val urlBuilder = "$mirror$path".toHttpUrlOrNull()?.newBuilder() ?: continue
                    urlBuilder.addQueryParameter("hidebroken", "true")
                    for ((k, v) in params) urlBuilder.addQueryParameter(k, v)

                    val req = Request.Builder()
                        .url(urlBuilder.build())
                        .header("User-Agent", USER_AGENT)
                        .build()

                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val parsed = parseStationArray(JSONArray(body))
                        if (parsed.isNotEmpty()) return@withContext parsed
                    }
                } catch (_: Exception) {}
            }
            emptyList()
        }

    private fun parseStationArray(arr: JSONArray): List<LiveRadioStation> {
        val out = mutableListOf<LiveRadioStation>()
        val seenUrls = HashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url_resolved").ifBlank { obj.optString("url") }.trim()
            if (url.isBlank() || !seenUrls.add(url)) continue
            val name = obj.optString("name", "").trim()
            if (name.isBlank()) continue
            out.add(
                LiveRadioStation(
                    uuid = obj.optString("stationuuid", url),
                    name = name,
                    streamUrl = url,
                    faviconUrl = obj.optString("favicon", "").takeIf { it.startsWith("http") },
                    country = obj.optString("country", ""),
                    state = obj.optString("state", ""),
                    tags = obj.optString("tags", "").split(",").map { it.trim() }.filter { it.isNotBlank() }.take(4),
                    bitrateKbps = obj.optInt("bitrate", 128)
                )
            )
        }
        return out
    }
}
