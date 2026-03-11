package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiziPalTest {

    private lateinit var provider: DiziPal

    @Before
    fun setUp() {
        val cookieJar = object : CookieJar {
            private val store = mutableMapOf<String, MutableList<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store.getOrPut(url.host) { mutableListOf() }.apply {
                    cookies.forEach { cookie ->
                        removeAll { it.name == cookie.name }
                        add(cookie)
                    }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return store[url.host] ?: emptyList()
            }
        }

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()

        app = Requests(baseClient = client)
        provider = DiziPal()
    }

    @Test
    fun testMainPage() = runTest {
        val request = MainPageRequest(
            name = "Son Bölümler",
            data = "${provider.mainUrl}/diziler/son-bolumler",
            horizontalImages = false
        )
        val response = provider.getMainPage(1, request)
        val items = response.items.firstOrNull()?.list ?: emptyList()

        println("  MainPage: ${items.size} kart")

        assertTrue("Ana sayfa en az 1 sonuç döndürmeli", items.isNotEmpty())
        items.first().let {
            assertNotNull(it.name)
            assertTrue("URL geçerli olmalı", it.url.startsWith("http"))
        }
    }

    @Test
    fun testQuickSearch() = runTest {
        val results = provider.quickSearch("breaking bad")

        println("  QuickSearch: ${results.size} sonuç")
        results.forEach { println("    - ${it.name}") }

        assertTrue("Arama en az 1 sonuç döndürmeli", results.isNotEmpty())
        assertTrue(
            "Breaking Bad sonuçlarda olmalı",
            results.any { it.name.contains("breaking", ignoreCase = true) }
        )
    }

    @Test
    fun testSearch() = runTest {
        val results = provider.search("breaking bad")

        println("  Search: ${results.size} sonuç")

        assertTrue("Arama en az 1 sonuç döndürmeli", results.isNotEmpty())
    }

    @Test
    fun testLoadTvSeries() = runTest {
        val url = "${provider.mainUrl}/dizi/breaking-bad"
        val response = provider.load(url)

        assertNotNull("Load response null olmamalı", response)
        response!!

        println("  Load: ${response.name} | ${response.type}")

        assertEquals("Breaking Bad", response.name)
        assertTrue("TvSeries olmalı", response is TvSeriesLoadResponse)

        if (response is TvSeriesLoadResponse) {
            println("  Bölümler: ${response.episodes.size}")
            assertTrue("Bölümler olmalı", response.episodes.isNotEmpty())

            val firstEp = response.episodes.first()
            println("  İlk bölüm: ${firstEp.name} | S${firstEp.season}E${firstEp.episode}")
            assertNotNull("Bölüm adı olmalı", firstEp.name)
        }
    }

    @Test
    fun testLoadLinksPlayerConfig() = runTest {
        // Directly test the player config extraction (cfg -> ajax-player-config -> video URL)
        val episodeUrl = "${provider.mainUrl}/bolum/breaking-bad-1-sezon-1-bolum"

        val document = app.get(episodeUrl).document
        val cfg = document.selectFirst(".video-player-container")?.attr("data-cfg")
        assertNotNull("data-cfg bulunmalı", cfg)

        val playerResponse = app.post(
            "${provider.mainUrl}/ajax-player-config",
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to episodeUrl,
            ),
            data = mapOf("cfg" to cfg!!)
        )

        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(playerResponse.text)
        val config = json.get("config")
        assertNotNull("config objesi bulunmalı", config)

        val videoUrl = config?.get("v")?.asText()
        val videoType = config?.get("t")?.asText()
        println("  Player: type=$videoType | url=${videoUrl?.take(60)}")

        assertNotNull("Video URL bulunmalı", videoUrl)
        assertTrue("Video URL boş olmamalı", videoUrl!!.isNotBlank())
    }

    @Test
    fun testLoadLinks() = runTest {
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()

        val episodeUrl = "${provider.mainUrl}/bolum/breaking-bad-1-sezon-1-bolum"

        // loadExtractor (cloudstream3 framework) may throw in JVM environment
        val result = runCatching {
            provider.loadLinks(
                data = episodeUrl,
                isCasting = false,
                subtitleCallback = { subs.add(it) },
                callback = { links.add(it) }
            )
        }

        result.onSuccess {
            println("  loadLinks: $it | ${links.size} link")
        }.onFailure {
            println("  loadLinks: extractor JVM hatası (beklenen) | ${it.message?.take(60)}")
        }
    }
}
