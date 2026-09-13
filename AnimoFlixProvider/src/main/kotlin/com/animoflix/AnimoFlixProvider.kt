package com.animoflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class AnimoFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimoFlixProvider())
        // Custom extractor for ansembed.net (VidMoly clone used by AnimoFlix)
        registerExtractorAPI(AnsEmbed())
    }
}

/**
 * ansembed.net is a VidMoly clone (JWPlayer) used by AnimoFlix as a video host.
 * Extracts `sources: [{ file: 'https://.../master.m3u8' }]` from the embed page.
 */
class AnsEmbed : ExtractorApi() {
    override val name = "AnsEmbed"
    override val mainUrl = "https://ansembed.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "user-agent" to USER_AGENT,
            "Sec-Fetch-Dest" to "iframe"
        )
        val document = app.get(url, headers = headers, referer = referer).document
        val script = document.select("script")
            .firstOrNull { it.data().contains("sources:") }
            ?.data()
            ?: throw ErrorLoadingException("No JWPlayer sources found")

        JwPlayerHelper.extractStreamLinks(script, name, mainUrl, callback, subtitleCallback)
    }
}

/** /search-autocomplete.php?q=... response item */
data class SearchSuggestion(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("title2") val title2: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("cover") val cover: String? = null,
)

/** /catalogue/?...&ajax=1 response */
data class CatalogueAjaxResponse(
    @JsonProperty("cards") val cards: String? = null,
    @JsonProperty("pagination") val pagination: String? = null,
)

class AnimoFlixProvider : MainAPI() {

    override var mainUrl = "https://animoflix.to"
    override var name = "AnimoFlix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    // The site sits behind Cloudflare: this interceptor solves the challenge via WebView (on device).
    private val cfKiller by lazy { CloudflareKiller() }

    // ---------- URL helpers ----------

    /** Strips domain / query / fragment from an href and returns a clean path like "/anime/one-piece/saison-1/vostfr/episode-12" */
    private fun pathOf(href: String): String = href
        .substringBefore("#")
        .substringBefore("?")
        .removePrefix(mainUrl)
        .trimEnd('/')

    /** Returns the anime slug for any /anime/... link (anime page, season page, episode page), or null. */
    private fun slugOf(href: String): String? {
        val path = pathOf(href)
        if (!path.startsWith("/anime/")) return null
        val slug = path.removePrefix("/anime/").substringBefore('/').lowercase()
        if (slug.isEmpty() || !slug.matches(Regex("[a-z0-9-]+"))) return null
        // Navigation links such as "/anime/vf/scan/" are not anime pages
        if (slug == "vf" || slug == "vostfr") return null
        return slug
    }

    private fun animeUrlOf(href: String): String? =
        slugOf(href)?.let { "$mainUrl/anime/$it/" }

    /** Best-effort title extraction from any card <a> element. */
    private fun titleOf(a: Element): String? {
        val title = a.selectFirst("h1, h2, h3, h4")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim().takeIf { it.isNotBlank() && !it.startsWith("Voir") }
            ?: a.ownText().trim()
        return title.takeIf { it.isNotBlank() && it.length <= 120 }
    }

    /** Converts a card <a> into a SearchResponse pointing to the anime page. */
    private fun toCard(a: Element): SearchResponse? {
        val url = animeUrlOf(a.attr("href")) ?: return null
        val title = titleOf(a) ?: return null
        val poster = a.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { fixUrlNull(it) }
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseCards(root: Element): List<SearchResponse> =
        root.select("a[href]").mapNotNull { toCard(it) }.distinctBy { it.url }

    // ---------- Main page ----------

    override val mainPage = mainPageOf(
        "vostfr" to "Derniers épisodes VOSTFR",
        "vf" to "Derniers épisodes VF",
        "ajouts" to "Derniers ajouts",
        "catalogue" to "Catalogue",
    )

    /** Finds the <div class="section"> whose header contains [needle]. */
    private fun Document.findSection(needle: String): Element? =
        select("div.section").firstOrNull { section ->
            (section.selectFirst("header h1, header h2, h1, h2")?.text() ?: "")
                .uppercase()
                .contains(needle)
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        when (request.data) {
            "catalogue" -> {
                val doc = app.get(
                    "$mainUrl/catalogue/",
                    params = mapOf("page" to page.toString()),
                    interceptor = cfKiller
                ).document
                val items = doc.select("a.cat-card-link").mapNotNull { toCard(it) }.distinctBy { it.url }
                val hasNext = doc.selectFirst("a[href*=page=${page + 1}]") != null
                return newHomePageResponse(request.name, items, hasNext)
            }

            "vostfr", "vf" -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val needle = if (request.data == "vf") "PISODES VF" else "PISODES VOSTFR"
                val doc = app.get("$mainUrl/", interceptor = cfKiller).document
                val section = doc.findSection(needle)
                val items = (section ?: doc).select("a[href*=episode-]")
                    .filter { pathOf(it.attr("href")).contains("/${request.data}/") }
                    .mapNotNull { toCard(it) }
                    .distinctBy { it.url }
                return newHomePageResponse(request.name, items, false)
            }

            else -> { // "ajouts"
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                val doc = app.get("$mainUrl/", interceptor = cfKiller).document
                val items = doc.findSection("AJOUTS")?.let { parseCards(it) } ?: parseCards(doc)
                return newHomePageResponse(request.name, items, false)
            }
        }
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        // 1) Server-side catalogue search: /catalogue/?search=…&ajax=1 returns {cards: "<html>"}
        val cards = runCatching {
            val json = app.get(
                "$mainUrl/catalogue/",
                params = mapOf("search" to query, "ajax" to "1"),
                interceptor = cfKiller
            ).text
            AppUtils.tryParseJson<CatalogueAjaxResponse>(json)?.cards
        }.getOrNull()

        if (!cards.isNullOrBlank()) {
            val doc = Jsoup.parseBodyFragment(cards, mainUrl)
            val results = doc.select("a.cat-card-link").mapNotNull { toCard(it) }.distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }

        // 2) Autocomplete fallback (also matches alternative titles)
        val suggestions = runCatching {
            val json = app.get(
                "$mainUrl/search-autocomplete.php",
                params = mapOf("q" to query),
                interceptor = cfKiller
            ).text
            AppUtils.tryParseJson<Array<SearchSuggestion>>(json)
        }.getOrNull()

        return suggestions?.mapNotNull { suggestion ->
            val title = suggestion.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = suggestion.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl/anime/$slug/", TvType.Anime) {
                this.posterUrl = suggestion.cover?.takeIf { it.isNotBlank() }?.let { fixUrlNull("/covers/$it") }
            }
        } ?: emptyList()
    }

    // ---------- Detail page ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller).document
        val slug = slugOf(url) ?: url.trimEnd('/').substringAfterLast('/')

        val title = doc.selectFirst("h1.hero-title, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: fixUrlNull("/covers/$slug.webp")

        val altTitle = doc.selectFirst(".hero-title-alt")?.text()?.trim()?.takeIf { it.isNotBlank() }

        val plot = doc.selectFirst(".hero-synopsis .synopsis-inner")?.text()?.trim()
            ?: doc.selectFirst(".hero-synopsis")?.text()?.trim()

        val tags = doc.select(".hero-genres .genre-pill")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 25 }
            .distinct()

        val label = doc.selectFirst(".hero-label")?.text()?.trim() ?: ""
        val type = when {
            label.contains("Film", true) -> TvType.AnimeMovie
            label.contains("OAV", true) || label.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val infoText = (doc.selectFirst(".hero")?.text() ?: "") + " " +
            doc.select(".hero-badges, .stat-row").joinToString(" ") { it.text() }
        val status = when {
            infoText.contains("en cours", true) -> ShowStatus.Ongoing
            infoText.contains("terminé", true) || infoText.contains("termine", true) -> ShowStatus.Completed
            else -> null
        }
        val year = Regex("""\b(19|20)\d{2}\b""").find(infoText)?.value?.toIntOrNull()

        // ----- Season pages -----
        val seasonLinks = doc.select("a.season-card").map { it.attr("href") }.ifEmpty {
            doc.select("a[href]").map { it.attr("href") }.filter { href ->
                val path = pathOf(href)
                val rest = path.removePrefix("/anime/$slug/")
                path.startsWith("/anime/$slug/") && rest.isNotBlank() && rest.count { it == '/' } == 0
            }
        }.map { fixUrl(it) }
            .filter { pathOf(it).removePrefix("/anime/$slug/") != "scan" }
            .distinct()

        val seasonEpisodes = coroutineScope {
            seasonLinks.map { seasonUrl ->
                async(Dispatchers.IO) { parseSeasonEpisodes(seasonUrl, slug) }
            }.awaitAll()
        }.flatten()

        // Fallback: episodes listed directly on the anime page (no season sub-page)
        val episodes = seasonEpisodes.ifEmpty { parseSeasonEpisodes(url, slug) }

        val episodesByDub = episodes
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) ->
                list.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { null }
            this.year = year
            this.showStatus = status
            this.synonyms = altTitle?.let { listOf(it) }
            this.episodes = episodesByDub.toMutableMap()
        }
    }

    /**
     * Parses one season page (or the anime page itself as fallback) and returns
     * (DubStatus, Episode) pairs. "vostfr" episodes go to Subbed, "vf" to Dubbed.
     * Special seasons (film, heroines, kai…) are stored as season 0.
     */
    private suspend fun parseSeasonEpisodes(seasonUrl: String, slug: String): List<Pair<DubStatus, Episode>> {
        val doc = runCatching { app.get(seasonUrl, interceptor = cfKiller).document }.getOrNull()
            ?: return emptyList()

        val seasonSegment = pathOf(seasonUrl).removePrefix("/anime/$slug/").substringBefore('/')
        val seasonNum = Regex("""(?:saison|season)[-_](\d+)""", RegexOption.IGNORE_CASE)
            .find(seasonSegment)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0 // specials (film, heroines, kai…)

        return doc.select("a[href]").mapNotNull { a ->
            val match = episodePathRegex.matchEntire(pathOf(a.attr("href"))) ?: return@mapNotNull null
            val epSlug = match.groupValues[1]
            val epLang = match.groupValues[3]
            val epNum = match.groupValues[4]
            if (epSlug != slug) return@mapNotNull null
            val number = epNum.toIntOrNull() ?: return@mapNotNull null
            val dubStatus = if (epLang.equals("vf", true)) DubStatus.Dubbed else DubStatus.Subbed

            val name = a.attr("title").trim()
                .replace(Regex("""\s*V(OST)?FR\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Épisode $number" }

            dubStatus to newEpisode(fixUrl(a.attr("href"))) {
                this.name = name
                this.season = seasonNum
                this.episode = number
            }
        }.distinctBy { it.second.data }
    }

    // /anime/{slug}/[season/]vf|vostfr/episode-{n}
    private val episodePathRegex = Regex(
        """^/anime/([a-z0-9-]+)(?:/([a-z0-9-]+))?/(vf|vostfr)/episode-(\d+)$""",
        RegexOption.IGNORE_CASE
    )

    // ---------- Video links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, interceptor = cfKiller).document
        val html = doc.html()
        val players = linkedMapOf<String, String>() // embed url -> label
        fun addPlayer(url: String, label: String) {
            val fixed = url.trim()
            if (fixed.startsWith("http") && !fixed.contains(mainUrl.removePrefix("https://")) && fixed !in players) {
                players[fixed] = label
            }
        }

        // 1) Player <select> present on every episode page
        doc.select("#epLecteurSelect option[value]").forEach { option ->
            if (option.attr("data-restricted").equals("true", ignoreCase = true)) return@forEach
            val label = option.text().trim().ifBlank { "Lecteur ${players.size + 1}" }
            addPlayer(option.attr("value"), label)
        }

        // 2) Fallbacks: iframes, og:video, JSON-LD embedUrl, known hosts in the source
        if (players.isEmpty()) {
            doc.select("iframe[src]").forEach { iframe ->
                addPlayer(iframe.attr("src"), "Lecteur ${players.size + 1}")
            }
            doc.selectFirst("meta[property=og:video]")?.attr("content")?.let {
                addPlayer(it, "Lecteur 1")
            }
            Regex(""""(?:embedUrl|contentUrl)"\s*:\s*"(https?://[^"]+)"""").findAll(html).forEach {
                addPlayer(it.groupValues[1], "Lecteur ${players.size + 1}")
            }
            knownHosts.forEach { host ->
                Regex("""https?://[^"'\\\s<>]+""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains(host, ignoreCase = true) }
                    .forEach { addPlayer(it, "Lecteur ${players.size + 1}") }
            }
        }

        var found = false

        players.forEach { (embedUrl, label) ->
            val loaded = runCatching {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)

            if (loaded) {
                found = true
            } else {
                // Generic fallback: fetch the embed page and look for direct streams
                runCatching {
                    val embedHtml = app.get(embedUrl, referer = mainUrl).text
                    directStreamRegex.findAll(embedHtml).map { it.value }.distinct().forEach { link ->
                        callback(
                            newExtractorLink(name, "$name · $label", link) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                                this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            }
                        )
                        found = true
                    }
                }
            }
        }

        // 3) Direct video links straight in the episode page
        directStreamRegex.findAll(html).map { it.value }.distinct().forEach { link ->
            callback(
                newExtractorLink(name, name, link) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
        }

        return found
    }

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private val knownHosts = listOf(
        "sibnet", "sendvid", "vidmoly", "ansembed", "voe.sx", "voe-unblock", "dood",
        "mixdrop", "streamtape", "filemoon", "uqload", "vudeo", "streamwish",
        "ok.ru", "mp4upload", "yourupload", "streamlare", "supervideo", "upstream",
        "vidhide", "fastplay", "hqq.tv", "netu", "waaw", "megafz"
    )
}
