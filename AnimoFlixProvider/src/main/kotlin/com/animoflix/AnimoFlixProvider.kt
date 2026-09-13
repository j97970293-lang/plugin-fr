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
import com.lagradost.cloudstream3.plugins.Plugin
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
class AnimoFlixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        AnimoFlixProvider.appContext = context.applicationContext
        registerMainAPI(AnimoFlixProvider())
        // Custom extractors used by AnimoFlix:
        // - ansembed.net (VidMoly/JWPlayer clone)
        // - odysee.com (LBRY)
        registerExtractorAPI(AnsEmbed())
        registerExtractorAPI(Odysee())
        // Bouton « Réglages » sur la fiche de l'extension dans CloudStream
        openSettings = { ctx -> AnimoFlixProvider.showSettings(ctx) }
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

/**
 * Odysee (LBRY) videos, embedded on AnimoFlix as https://odysee.com/$/embed/@channel:id/claim-name
 * Resolves the claim through the public LBRY API and builds the direct stream URL.
 */
class Odysee : ExtractorApi() {
    override val name = "Odysee"
    override val mainUrl = "https://odysee.com"
    override val requiresReferer = false

    private val claimIdRegex = Regex(""""claim_id"\s*:\s*"([0-9a-f]{40})"""")
    private val sdHashRegex = Regex(""""sd_hash"\s*:\s*"([0-9a-f]{96})"""")

    /** Decodes %XX sequences as UTF-8, keeping literal '+' intact. */
    private fun percentDecode(input: String): String =
        java.net.URLDecoder.decode(input.replace("+", "%2B"), "UTF-8")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // https://odysee.com/$/embed/@channel:9/claim-name  ->  lbry://@channel:9/claim-name
        val encodedPath = url.substringBefore("?").substringAfter("/$/embed/").trimEnd('/')
        if (encodedPath.isEmpty()) throw ErrorLoadingException("Invalid Odysee embed url")

        val response = app.post(
            "https://api.na-backend.odysee.com/api/v1/proxy?m=resolve",
            json = com.lagradost.nicehttp.JsonAsString(
                """{"jsonrpc":"2.0","method":"resolve","params":{"urls":["lbry://${percentDecode(encodedPath)}"]},"id":1}"""
            )
        ).text

        val claimId = claimIdRegex.find(response)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Odysee claim not found")
        val sdHash = sdHashRegex.find(response)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Odysee stream not found")

        val streamUrl =
            "https://player.odycdn.com/api/v4/streams/free/$encodedPath/$claimId/${sdHash.take(6)}"

        callback(
            newExtractorLink(name, name, streamUrl) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO
            }
        )
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

    override var mainUrl = DEFAULT_URL
    override var name = "AnimoFlix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)

    // The site sits behind Cloudflare: this interceptor solves the challenge via WebView (on device).
    private val cfKiller by lazy { CloudflareKiller() }

    // -------------------------------------------------------------------------
    // Réglages : adresse du site modifiable (miroirs / changement de domaine)
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://animoflix.to"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "animoflix_settings"
        private const val PREF_URL = "site_url"

        /** Adresse actuelle : réglage utilisateur si défini, sinon celle par défaut. */
        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun setSiteUrl(context: android.content.Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        /** Bouton « Réglages » de l'extension : boîte de dialogue pour changer l'adresse. */
        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl())
                hint = "https://…"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(
                    android.widget.TextView(context).apply {
                        text = "Adresse du site AnimoFlix (à changer s'il déménage) :"
                    }
                )
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("AnimoFlix")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val value = input.text.toString().trim()
                    if (value.startsWith("http")) {
                        setSiteUrl(context, value)
                        toast(context, "Adresse enregistrée : $value")
                    } else {
                        toast(context, "Adresse invalide : elle doit commencer par https://")
                    }
                }
                .setNeutralButton("Par défaut") { _, _ ->
                    setSiteUrl(context, null)
                    toast(context, "Adresse par défaut restaurée : $DEFAULT_URL")
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        private fun toast(context: android.content.Context, message: String) =
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Applique l'adresse personnalisée à chaque requête. */
    private fun syncUrl() {
        mainUrl = currentUrl()
    }

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

    /** Poster of a card <a>: its own image, or the anime cover ("fiche") as fallback. */
    private fun posterOf(a: Element, slug: String?): String? {
        val img = a.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return when {
            img != null && img.contains("default") && slug != null -> fixUrlNull("/covers/$slug.webp")
            img != null -> fixUrlNull(img)
            slug != null -> fixUrlNull("/covers/$slug.webp")
            else -> null
        }
    }

    /** Converts a card <a> into a SearchResponse pointing to the anime page. */
    private fun toCard(a: Element): SearchResponse? {
        val href = a.attr("href")
        val url = animeUrlOf(href) ?: return null
        val title = titleOf(a) ?: return null
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterOf(a, slugOf(href))
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
        syncUrl()
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
        syncUrl()
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
        syncUrl()
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
     * No per-episode thumbnail: CloudStream shows the episode number, so each
     * episode stays identifiable (the fiche poster is on the show page).
     */
    private suspend fun parseSeasonEpisodes(
        seasonUrl: String,
        slug: String
    ): List<Pair<DubStatus, Episode>> {
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
        syncUrl()
        val doc = app.get(data, interceptor = cfKiller).document
        val html = doc.html()

        // embed url -> (label, restricted). The <select> is server-rendered with every player.
        val players = LinkedHashMap<String, Pair<String, Boolean>>()
        doc.select("#epLecteurSelect option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (!value.startsWith("http")) return@forEach
            if (value in players) return@forEach
            val restricted = option.attr("data-restricted").equals("true", ignoreCase = true)
            val label = option.text().trim().ifBlank { "Lecteur ${players.size + 1}" }
            players[value] = label to restricted
        }

        // Fallbacks if the select is missing: iframes, og:video, JSON-LD embedUrl, known hosts
        if (players.isEmpty()) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http") && !src.contains(mainUrl.removePrefix("https://")) && src !in players) {
                    players[src] = "Lecteur ${players.size + 1}" to false
                }
            }
            doc.selectFirst("meta[property=og:video]")?.attr("content")?.trim()?.let {
                if (it !in players) players[it] = "Lecteur 1" to false
            }
            Regex(""""(?:embedUrl|contentUrl)"\s*:\s*"(https?://[^"]+)"""").findAll(html).forEach {
                if (it.groupValues[1] !in players) players[it.groupValues[1]] = "Lecteur ${players.size + 1}" to false
            }
            knownHosts.forEach { host ->
                Regex("""https?://[^"'\\\s<>]+""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains(host, ignoreCase = true) }
                    .forEach {
                        if (it !in players) players[it] = "Lecteur ${players.size + 1}" to false
                    }
            }
        }

        var found = false

        // Non-restricted players first, then the rest (matches the site's order)
        players.entries.sortedBy { it.value.second }.forEach { (embedUrl, labelRestricted) ->
            val label = labelRestricted.first

            // Run the matching extractor (built-in or plugin) and count the links it produces:
            // some built-in extractors "succeed" without emitting anything (e.g. Sendvid only
            // handles m3u8 but serves mp4), which used to hide the extra servers.
            var produced = 0
            val countingCallback: (ExtractorLink) -> Unit = { link ->
                produced++
                callback(link)
            }
            runCatching {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, countingCallback)
            }
            if (produced > 0) {
                found = true
                return@forEach
            }

            // Generic fallback: fetch the embed page and look for direct streams
            if (genericExtract(embedUrl, label, subtitleCallback, callback)) {
                found = true
            }
        }

        // Direct video links straight in the episode page
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

    /**
     * Last-resort extraction for hosts without a working built-in extractor:
     * loads the embed page and collects every direct stream found in it
     * (og:video meta, <source> tags, jwplayer `file:` values, raw .m3u8/.mp4/.webm URLs).
     */
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(
            embedUrl,
            referer = mainUrl,
            headers = mapOf("user-agent" to USER_AGENT)
        ).text

        val links = LinkedHashSet<String>()

        // <meta property="og:video(:secure_url)?" content="…"> and JSON-LD contentUrl/embedUrl
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }

        // <source src="…"> / <video src="…">
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }

        // jwplayer-ish: file: '…' / "file": "…" / src: "…"
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }

        // Any direct stream URL anywhere in the page
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }

        links.filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                callback(
                    newExtractorLink(name, "$name · $label", link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

        links.isNotEmpty()
    }.getOrDefault(false)

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private val knownHosts = listOf(
        "sibnet", "sendvid", "odysee", "vidmoly", "ansembed", "voe.sx", "voe-unblock", "dood",
        "mixdrop", "streamtape", "filemoon", "uqload", "vudeo", "streamwish",
        "ok.ru", "mp4upload", "yourupload", "streamlare", "supervideo", "upstream",
        "vidhide", "fastplay", "hqq.tv", "netu", "waaw", "megafz"
    )
}
