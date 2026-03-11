// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val defaultCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1743289650198"
    )

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())

            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/ulke/turkiye"              to "Yerli",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"   to "Dizi Arşivi",
        "${mainUrl}/tur/aile/page/SAYFA/"      to "Aile",
        "${mainUrl}/tur/aksiyon/page/SAYFA"    to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA"  to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA"   to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA" to "Bilimkurgu",
        "${mainUrl}/tur/biyografi/page/SAYFA"  to "Biyografi",
        "${mainUrl}/tur/dram/page/SAYFA"       to "Dram",
        "${mainUrl}/tur/drama/page/SAYFA"      to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA"  to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA"    to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA"      to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA"     to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA"      to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA"     to "Macera",
        "${mainUrl}/tur/muzik/page/SAYFA"      to "Müzik",
        "${mainUrl}/tur/muzikal/page/SAYFA"    to "Müzikal",
        "${mainUrl}/tur/reality-tv/page/SAYFA" to "Reality TV",
        "${mainUrl}/tur/romantik/page/SAYFA"   to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA"      to "Savaş",
        "${mainUrl}/tur/spor/page/SAYFA"       to "Spor",
        "${mainUrl}/tur/suc/page/SAYFA"        to "Suç",
        "${mainUrl}/tur/tarih/page/SAYFA"      to "Tarih",
        "${mainUrl}/tur/western/page/SAYFA"    to "Western",
        "${mainUrl}/tur/yarisma/page/SAYFA"    to "Yarışma"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(url, cookies = defaultCookies, interceptor = interceptor, cacheTime = 60).document

        if (request.name == "Dizi Arşivi") {
            val home = document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
            return newHomePageResponse(request.name, home)
        }

        val home = document.select("article.article-series-poster").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
            }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/?s=${query}",
            cookies = defaultCookies,
            interceptor = interceptor
        ).document

        return document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, cookies = defaultCookies, interceptor = interceptor).document

        val title       = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val description = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe")?.attr("src")

        val episodeList = mutableListOf<Episode>()
        document.select("div#seasons-list a").forEach {
            val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
            val epDoc = app.get(epUrl, cookies = defaultCookies, interceptor = interceptor).document

            epDoc.select("article.grid-box").forEach ep@ { epElem ->
                val epTitle   = epElem.selectFirst("div.post-title a")?.text()?.trim() ?: return@ep
                val epHref    = fixUrlNull(epElem.selectFirst("div.post-title a")?.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun iframeDecode(
        data: String,
        iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        @Suppress("NAME_SHADOWING") var iframe = iframe

        try {
            if (iframe.contains("/player/king/king.php")) {
                return decodeKing(data, iframe, subtitleCallback, callback)
            } else if (iframe.contains("/player/moly/moly.php")) {
                return decodeMoly(data, iframe, subtitleCallback, callback)
            } else if (iframe.contains("/player/haydi.php")) {
                return decodeHaydi(data, iframe, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("DZBX", "iframeDecode error for $iframe: ${e.message}")
        }

        return false
    }

    private suspend fun decodeKing(
        data: String,
        rawIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframe = rawIframe.replace("king.php?v=", "king.php?wmode=opaque&v=")
        Log.d("DZBX", "king » fetching $iframe")

        val subDoc = app.get(iframe, referer = data, cookies = defaultCookies, interceptor = interceptor).document
        val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src")
        Log.d("DZBX", "king » subFrame=$subFrame")
        if (subFrame == null) return false

        val iDoc = app.get(subFrame, referer = "${mainUrl}/").text
        Log.d("DZBX", "king » iDoc length=${iDoc.length}")

        // Try CryptoJS decryption
        val cryptData = Regex("""CryptoJS\.AES\.decrypt\("(.*?)",""").find(iDoc)?.groupValues?.get(1)
        val cryptPass = Regex("""","(.*?)"\)""").find(iDoc)?.groupValues?.get(1)
        Log.d("DZBX", "king » cryptData=${cryptData?.take(60)}, cryptPass=${cryptPass?.take(60)}")

        if (cryptData != null && cryptPass != null) {
            try {
                val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
                // Log decrypted content in chunks to see full structure
                decryptedData.chunked(800).forEachIndexed { i, chunk ->
                    Log.d("DZBX", "king » decrypted[$i]=$chunk")
                }

                // Parse decrypted HTML and extract video source
                val decryptedDoc = Jsoup.parse(decryptedData)

                // Try <source> tag with HLS type or <video> src
                val vidUrl = decryptedDoc.selectFirst("source[type='application/vnd.apple.mpegurl']")?.attr("src")
                    ?: decryptedDoc.selectFirst("source[type*=mpegurl]")?.attr("src")
                    ?: decryptedDoc.selectFirst("video source")?.attr("src")
                    ?: decryptedDoc.selectFirst("video")?.attr("src")
                    ?: Regex("""file:\s*['"]([^'"]+)['"]""").find(decryptedData)?.groupValues?.get(1)
                    ?: Regex("""source:\s*['"]([^'"]+)['"]""").find(decryptedData)?.groupValues?.get(1)
                Log.d("DZBX", "king » vidUrl=$vidUrl")

                if (vidUrl != null) {
                    // The sheila URL may serve m3u8 directly or need further resolution
                    val resolvedUrl = resolveVideoUrl(vidUrl, subFrame)
                    Log.d("DZBX", "king » resolvedUrl=$resolvedUrl")
                    if (resolvedUrl != null) {
                        val linkType = if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains("molystream")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name   = "${this.name} King",
                                url    = resolvedUrl,
                                type   = linkType
                            ) {
                                headers = mapOf(
                                    "Referer" to "https://dbx.molystream.org/",
                                    "Origin"  to "https://dbx.molystream.org"
                                )
                                quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e("DZBX", "king » decrypt error: ${e.message}")
            }
        }

        // Fallback: try direct m3u8/mp4 extraction
        val directUrl = Regex("""(?:file|src|source)\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]""").find(iDoc)?.groupValues?.get(1)
            ?: Regex("""(?:file|src|source)\s*[:=]\s*['"]([^'"]*\.mp4[^'"]*)['"]""").find(iDoc)?.groupValues?.get(1)
        Log.d("DZBX", "king » directUrl=$directUrl")

        if (directUrl != null) {
            val linkType = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(source = this.name, name = "${this.name} King", url = directUrl, type = linkType) {
                    headers = mapOf("Referer" to subFrame)
                    quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // Last resort: loadExtractor on the sub-iframe
        Log.d("DZBX", "king » fallback to loadExtractor on $subFrame")
        loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
        return true
    }

    private suspend fun decodeMoly(
        data: String,
        rawIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframe = rawIframe.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
        Log.d("DZBX", "moly » fetching $iframe")

        val molyResponse = app.get(iframe, referer = data, cookies = defaultCookies, interceptor = interceptor)
        val molyHtml = molyResponse.text
        var subDoc = molyResponse.document
        Log.d("DZBX", "moly » html length=${molyHtml.length}")

        val atobData = Regex("""unescape\("(.*?)"\)""").find(molyHtml)?.groupValues?.get(1)
        Log.d("DZBX", "moly » atobData found=${atobData != null}")

        if (atobData != null) {
            val decodedAtob = atobData.decodeUri()
            val strAtob = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
            Log.d("DZBX", "moly » decoded=${strAtob.take(200)}")
            subDoc = Jsoup.parse(strAtob)
        }

        val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src")
        Log.d("DZBX", "moly » subFrame=$subFrame")
        if (subFrame == null) return false

        // Try to extract video directly from embed page
        try {
            val embedHtml = app.get(subFrame, referer = "${mainUrl}/").text
            Log.d("DZBX", "moly » embed length=${embedHtml.length}")

            val sourceUrl = Regex("""sources:\s*\[\{file:\s*"([^"]+)""").find(embedHtml)?.groupValues?.get(1)
                ?: Regex("""file:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""").find(embedHtml)?.groupValues?.get(1)
                ?: Regex("""src:\s*['"]([^'"]*\.m3u8[^'"]*)['"]""").find(embedHtml)?.groupValues?.get(1)
            Log.d("DZBX", "moly » sourceUrl=$sourceUrl")

            if (sourceUrl != null) {
                val linkType = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(source = this.name, name = "${this.name} Moly", url = sourceUrl, type = linkType) {
                        headers = mapOf(
                            "Referer" to subFrame,
                            "Origin"  to "https://vidmoly.biz"
                        )
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.e("DZBX", "moly » embed error: ${e.message}")
        }

        // Fallback to loadExtractor
        Log.d("DZBX", "moly » fallback to loadExtractor on $subFrame")
        loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
        return true
    }

    private suspend fun decodeHaydi(
        data: String,
        rawIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframe = rawIframe.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
        Log.d("DZBX", "haydi » fetching $iframe")

        val haydiResponse = app.get(iframe, referer = data, cookies = defaultCookies, interceptor = interceptor)
        val haydiHtml = haydiResponse.text
        var subDoc = haydiResponse.document
        Log.d("DZBX", "haydi » html length=${haydiHtml.length}")

        val atobData = Regex("""unescape\("(.*?)"\)""").find(haydiHtml)?.groupValues?.get(1)
        Log.d("DZBX", "haydi » atobData found=${atobData != null}")

        if (atobData != null) {
            val decodedAtob = atobData.decodeUri()
            val strAtob = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
            Log.d("DZBX", "haydi » decoded=${strAtob.take(200)}")
            subDoc = Jsoup.parse(strAtob)
        }

        val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src")
        Log.d("DZBX", "haydi » subFrame=$subFrame")
        if (subFrame == null) return false

        // For ok.ru, try to extract HLS manifest directly
        if (subFrame.contains("ok.ru")) {
            try {
                val okHtml = app.get(subFrame, referer = "${mainUrl}/").text
                Log.d("DZBX", "haydi » ok.ru html length=${okHtml.length}")

                // Log relevant parts of ok.ru HTML
                val dataOptionsMatch = Regex("""data-options="([^"]{0,500})""").find(okHtml)
                Log.d("DZBX", "haydi » data-options=${dataOptionsMatch?.groupValues?.get(1)?.take(300)}")

                // Search for any video-related JSON
                val jsonMatch = Regex("""flashvars[^{]*(\{[^}]{0,500})""", RegexOption.IGNORE_CASE).find(okHtml)
                Log.d("DZBX", "haydi » flashvars=${jsonMatch?.groupValues?.get(1)?.take(300)}")

                // Try broader patterns for HLS
                val hlsUrl = Regex("""hlsManifestUrl\\{0,2}":\\{0,2}"([^"\\]+)""").find(okHtml)?.groupValues?.get(1)
                    ?: Regex("""hlsMasterPlaylistUrl\\{0,2}":\\{0,2}"([^"\\]+)""").find(okHtml)?.groupValues?.get(1)
                    ?: Regex("""data-options="[^"]*?hlsManifestUrl&quot;:&quot;([^&]+)""").find(okHtml)?.groupValues?.get(1)
                    ?: Regex("""hlsManifestUrl[^h]+(https?://[^\s"'\\&]+)""").find(okHtml)?.groupValues?.get(1)
                    ?: Regex("""manifestUrl[^h]+(https?://[^\s"'\\&]+)""").find(okHtml)?.groupValues?.get(1)
                Log.d("DZBX", "haydi » ok.ru hlsUrl=$hlsUrl")

                if (hlsUrl != null) {
                    val cleanUrl = hlsUrl.replace("\\u0026", "&").replace("\\\\u0026", "&")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = "${this.name} OK.ru",
                            url    = cleanUrl,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf("Referer" to "https://ok.ru/")
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.e("DZBX", "haydi » ok.ru error: ${e.message}")
            }
        }

        // Fallback to loadExtractor
        Log.d("DZBX", "haydi » fallback to loadExtractor on $subFrame")
        loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
        return true
    }

    private suspend fun resolveVideoUrl(url: String, referer: String): String? {
        // If URL already points to m3u8/mp4, use directly
        if (url.contains(".m3u8") || url.contains(".mp4")) return url

        try {
            // Fetch the URL and check what we get back
            val response = app.get(url, referer = referer, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            ))
            val text = response.text
            Log.d("DZBX", "resolve » url=$url, status=${response.code}, length=${text.length}, first200=${text.take(200)}")

            // Check if response is an m3u8 manifest
            if (text.trimStart().startsWith("#EXTM3U")) {
                return url
            }

            // Check if it's HTML/JS with a video source
            val m3u8Url = Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""").find(text)?.groupValues?.get(1)
            if (m3u8Url != null) {
                Log.d("DZBX", "resolve » found m3u8 in response: $m3u8Url")
                return m3u8Url
            }

            val mp4Url = Regex("""(https?://[^\s'"<>]+\.mp4[^\s'"<>]*)""").find(text)?.groupValues?.get(1)
            if (mp4Url != null) {
                Log.d("DZBX", "resolve » found mp4 in response: $mp4Url")
                return mp4Url
            }

            // Check for redirect in response URL
            val finalUrl = response.url
            if (finalUrl != url) {
                Log.d("DZBX", "resolve » redirected to $finalUrl")
                return finalUrl
            }

        } catch (e: Exception) {
            Log.e("DZBX", "resolve » error: ${e.message}")
        }

        // Return original URL as last resort
        return url
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZBX", "data » $data")
        val document = app.get(data, cookies = defaultCookies, interceptor = interceptor).document

        var iframe = document.selectFirst("div#video-area iframe")?.attr("src") ?: return false
        Log.d("DZBX", "iframe » $iframe")

        iframeDecode(data, iframe, subtitleCallback, callback)

        document.select("div.video-toolbar option[value]").forEach {
            val altLink = it.attr("value")
            if (altLink.isBlank()) return@forEach
            try {
                val subDoc = app.get(altLink, cookies = defaultCookies, interceptor = interceptor).document
                val altIframe = subDoc.selectFirst("div#video-area iframe")?.attr("src") ?: return@forEach
                Log.d("DZBX", "iframe » $altIframe")
                iframeDecode(data, altIframe, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("DZBX", "alt source error: ${e.message}")
            }
        }

        return true
    }
}
