// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal2031.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"    to "Son Bölümler",
        "${mainUrl}/diziler"                 to "Yeni Diziler",
        "${mainUrl}/filmler"                 to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"       to "Netflix",
        "${mainUrl}/koleksiyon/exxen"         to "Exxen",
        "${mainUrl}/koleksiyon/blutv"         to "BluTV",
        "${mainUrl}/koleksiyon/disney"        to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"  to "Amazon Prime",
        "${mainUrl}/koleksiyon/tod-bein"      to "TOD (beIN)",
        "${mainUrl}/koleksiyon/gain"          to "Gain",
        "${mainUrl}/tur/mubi"                to "Mubi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val home = document.select("a.card-link").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h3.card-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        val type = if (href.contains("/film/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/arama?q=${query}",
            interceptor = interceptor,
            headers     = getHeaders(mainUrl)
        ).document

        return document.select("a.card-link").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        try {
            val response = app.get(
                "${mainUrl}/ajax-search?q=${query}",
                interceptor = interceptor,
                headers     = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json",
                    "User-Agent"       to USER_AGENT,
                )
            )
            val mapper = jacksonObjectMapper()
            val json   = mapper.readTree(response.text)

            if (json.get("success")?.asBoolean() != true) return search(query)

            val results = json.get("results") ?: return search(query)

            return results.mapNotNull { item ->
                val title  = item.get("title")?.asText() ?: return@mapNotNull null
                val href   = fixUrlNull(item.get("url")?.asText()) ?: return@mapNotNull null
                val poster = fixUrlNull(item.get("poster")?.asText())
                val type   = item.get("type")?.asText()

                if (type == "film") {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                }
            }
        } catch (e: Exception) {
            return search(query)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = getHeaders(mainUrl)).document

        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst(".series-hero img")?.attr("src"))
        val description = document.selectFirst(".series-description")?.text()?.trim()

        val infoRows    = document.select(".info-row")
        var year: Int?       = null
        var tags: List<String>? = null
        var duration: Int?   = null

        for (row in infoRows) {
            val label = row.selectFirst(".info-label")?.text()?.trim() ?: continue
            val value = row.selectFirst(".info-value")?.text()?.trim() ?: continue

            when {
                label.contains("Yıl", ignoreCase = true)         -> year = value.toIntOrNull()
                label.contains("Kategori", ignoreCase = true)    -> tags = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                label.contains("Süre", ignoreCase = true)        -> duration = Regex("(\\d+)").find(value)?.value?.toIntOrNull()
            }
        }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text() ?: return null

            val episodes = document.select("a.detail-episode-item").mapNotNull {
                val epTitle    = it.selectFirst(".detail-episode-title")?.text()?.trim() ?: return@mapNotNull null
                val epHref     = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epSubtitle = it.selectFirst(".detail-episode-subtitle")?.text()?.trim() ?: ""

                val epSeason  = Regex("(\\d+)\\. Sezon").find(epSubtitle)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("(\\d+)\\. Bölüm").find(epSubtitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epTitle
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        } else {
            val title = document.selectFirst("h1.series-title")?.text()
                ?: document.selectFirst("h1")?.text()
                ?: return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "data » $data")

        // First visit the page to establish session cookies
        val pageResponse = app.get(data, headers = getHeaders(mainUrl))
        val document = pageResponse.document

        val cfg = document.selectFirst(".video-player-container")?.attr("data-cfg") ?: return false
        Log.d("DZP", "cfg » $cfg")

        // Use cookies from the page visit for the API call
        val cookies = pageResponse.cookies
        Log.d("DZP", "cookies » $cookies")

        val playerResponse = app.post(
            "${mainUrl}/ajax-player-config",
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to data,
                "User-Agent"       to USER_AGENT,
            ),
            cookies = cookies,
            data = mapOf("cfg" to cfg)
        )
        Log.d("DZP", "playerResponse » ${playerResponse.text}")

        val mapper = jacksonObjectMapper()
        val json   = mapper.readTree(playerResponse.text)
        val config = json.get("config") ?: return false

        val videoUrl  = config.get("v")?.asText() ?: return false
        val videoType = config.get("t")?.asText() ?: "embed"

        Log.d("DZP", "videoUrl » $videoUrl, type » $videoType")

        when (videoType) {
            "m3u8" -> {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = videoUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf("Referer" to "${mainUrl}/")
                        quality = Qualities.Unknown.value
                    }
                )
            }
            "mp4" -> {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = videoUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        headers = mapOf("Referer" to "${mainUrl}/")
                        quality = Qualities.Unknown.value
                    }
                )
            }
            "embed", "iframe" -> {
                val iframeUrl = if (videoUrl.contains("<iframe")) {
                    Regex("""src=["']([^"']+)""").find(videoUrl)?.groupValues?.get(1)
                } else {
                    videoUrl
                }
                if (iframeUrl != null) {
                    extractFromEmbed(iframeUrl, data, subtitleCallback, callback)
                }
            }
            else -> {
                loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("DZP", "extractFromEmbed » $embedUrl")
        val embedResponse = app.get(embedUrl, headers = mapOf(
            "Referer" to "${mainUrl}/",
            "User-Agent" to USER_AGENT,
        ))
        val embedHtml = embedResponse.text

        // Extract m3u8/mp4 sources from JWPlayer setup: sources: [{file:"..."}]
        val sourceRegex = Regex("""sources:\s*\[\{file:"([^"]+)""")
        val sourceUrl = sourceRegex.find(embedHtml)?.groupValues?.get(1)
        Log.d("DZP", "sourceUrl » $sourceUrl")

        if (sourceUrl != null) {
            val linkType = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = sourceUrl,
                    type   = linkType
                ) {
                    headers = mapOf("Referer" to embedUrl)
                    quality = Qualities.Unknown.value
                }
            )
        }

        // Extract subtitles: tracks: [{file: "...", label: "...", kind: "captions"}]
        val trackRegex = Regex("""\{file:\s*"([^"]+)",\s*label:\s*"([^"]+)",\s*kind:\s*"captions"""")
        trackRegex.findAll(embedHtml).forEach { match ->
            val subUrl   = match.groupValues[1]
            val subLabel = match.groupValues[2]
            Log.d("DZP", "subtitle » $subLabel: $subUrl")
            subtitleCallback.invoke(
                SubtitleFile(subLabel, subUrl)
            )
        }

        // Fallback to loadExtractor if no sources found
        if (sourceUrl == null) {
            Log.d("DZP", "No sources in embed, falling back to loadExtractor")
            loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
    }
}


