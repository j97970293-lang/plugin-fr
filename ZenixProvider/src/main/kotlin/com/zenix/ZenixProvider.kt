package com.zenix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
import kotlinx.coroutines.sync.Semaphore
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class ZenixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ZenixProvider())
        // Custom extractor for 1embed.cc (direct HLS playlists in the page)
        registerExtractorAPI(OneEmbed())
    }
}

/**
 * 1embed.cc — embed player whose page embeds a Next.js payload listing its
 * servers as JSON: `{"name":"Solari","url":"/v/solari_<id>.m3u8","type":"hls"}`.
 * The playlists are served directly (Referer required).
 */
class OneEmbed : ExtractorApi() {
    override val name = "1Embed"
    override val mainUrl = "https://1embed.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer, headers = baseHeaders).text
        // Un-escape the Next.js flight payload (\" -> ")
        val norm = html.replace("\\\"", "\"")
        val servers = LinkedHashMap<String, String>()
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+\.m3u8)"""")
            .findAll(norm).forEach { servers[it.groupValues[2]] = it.groupValues[1] }
        // Fallback: any /v/*.m3u8 playlist path on the page
        if (servers.isEmpty()) {
            Regex(""""(/v/[a-zA-Z0-9_.-]+\.m3u8)"""").findAll(norm).forEach {
                if (it.groupValues[1] !in servers) servers[it.groupValues[1]] = "Serveur"
            }
        }
        servers.forEach { (path, serverName) ->
            val absolute = if (path.startsWith("http")) path else "$mainUrl$path"
            callback(
                newExtractorLink(name, "$name · $serverName", absolute) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
        }
    }

    companion object {
        private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    }
}

/**
 * Extension CloudStream pour https://zenix.best (films & séries FR, rendu serveur PHP).
 *
 * Structure réelle du site (vérifiée) :
 *   /                            accueil (carrousels JS)
 *   /trending /top-imdb          Tendances, Top IMDb
 *   /movies /tv-shows            Films, Séries
 *   /genre/{slug}                action, adventure, animation, comedy, science-fiction,
 *                                horror, thriller, romance, crime, drama
 *   /movie/{slug}                fiche film (serveurs de lecture dans le HTML)
 *   /tv-show/{slug}              fiche série (toutes les saisons + épisodes)
 *   /episode/{slug}/{saison}-{épisode}   page épisode (serveurs de lecture)
 *   /ajax/search/suggest?q=…     recherche JSON
 *   /search/{requête}            recherche HTML (secours)
 *
 * Lecteurs : chaque page film/épisode contient des boutons
 * `selectStream(n, 'url', 'Libellé | LANGUE', 'type')` listant TOUS les serveurs
 * (BlinkFlux et « Lecteur Gratuit 4K » internes + ~22 embeds externes : Frembed,
 * Peachify, VidFast, Mostream, HNEmbed, WaveWatch, Videasy, PrimeSrc, VidLove,
 * VidUp, Braflix, StreamIMDb, VidSrc PM/IO, AnyEmbed, Viduki, 1Embed, VidKing,
 * 2Embed, SuperFlix…). L'extension tente chaque URL via les extracteurs intégrés
 * puis via une extraction générique, en parallèle.
 */
class ZenixProvider : MainAPI() {

    override var mainUrl = "https://zenix.best" // domaine de secours officiel : zenix.lol
    override var name = "Zenix"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "🔥 Tendances",
        "$mainUrl/top-imdb" to "⭐ Top IMDb",
        "$mainUrl/movies" to "🎬 Films",
        "$mainUrl/tv-shows" to "📺 Séries",
        "$mainUrl/genre/action" to "💥 Action",
        "$mainUrl/genre/adventure" to "🧭 Aventure",
        "$mainUrl/genre/animation" to "✨ Animation",
        "$mainUrl/genre/comedy" to "😂 Comédie",
        "$mainUrl/genre/science-fiction" to "🚀 Science-Fiction",
        "$mainUrl/genre/horror" to "👻 Horreur",
        "$mainUrl/genre/thriller" to "🔪 Thriller",
        "$mainUrl/genre/romance" to "❤️ Romance",
        "$mainUrl/genre/crime" to "🕵️ Policier",
        "$mainUrl/genre/drama" to "🎭 Drame",
    )

    private val baseHeaders = mapOf("user-agent" to USER_AGENT)

    // -------------------------------------------------------------------------
    // Page d'accueil : une page par section (la pagination des listes est en JS)
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data, headers = baseHeaders).document
        val cards = doc.select("a.zenix-card__inner")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, cards)
    }

    // -------------------------------------------------------------------------
    // Recherche : API JSON /ajax/search/suggest (secours : HTML /search/{q})
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val apiResults = runCatching {
            val json = app.get(
                "$mainUrl/ajax/search/suggest",
                params = mapOf("q" to trimmed),
                headers = baseHeaders
            ).text
            AppUtils.tryParseJson<ZenixSuggestResponse>(json)?.posts
                ?.filter { !it.url.isNullOrBlank() && !it.title.isNullOrBlank() }
                ?.mapNotNull { p ->
                    val url = fixUrl(p.url!!)
                    val isTv = p.type == "tv"
                    val poster = p.image?.takeIf { it.startsWith("http") }
                    val year = p.year?.trim()?.toIntOrNull()
                    if (isTv) {
                        newTvSeriesSearchResponse(p.title!!, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(p.title!!, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                } ?: emptyList()
        }.getOrDefault(emptyList())
        if (apiResults.isNotEmpty()) return apiResults

        // Fallback HTML
        val doc = app.get(
            "$mainUrl/search/" + trimmed.replace(Regex("\\s+"), "-"),
            headers = baseHeaders
        ).document
        return doc.select("a.zenix-card__inner")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Détail : /movie/{slug}, /tv-show/{slug} (une page /episode/ remonte à la série)
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        var fetchUrl = url
        var doc = app.get(fetchUrl, headers = baseHeaders).document

        // Une page épisode renvoie toujours vers sa série
        if ("/episode/" in fetchUrl) {
            doc.selectFirst("a[href*='/tv-show/']")?.attr("href")?.let {
                fetchUrl = fixUrl(it)
                doc = app.get(fetchUrl, headers = baseHeaders).document
            }
        }
        val isTv = "/tv-show/" in fetchUrl

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("–")?.trim()
            ?: "Inconnu"

        val poster = fixUrlNull(
            doc.selectFirst(".zx-poster-wrap img")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        )

        val plot = doc.selectFirst("[class*=overview]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Badges de la fiche : note (or), année / durée / genres (gris) ;
        // on ignore les badges « vues » et le lien IMDb.
        var rating: Double? = null
        var year: Int? = null
        val genres = mutableListOf<String>()
        doc.select(".zx-meta .zx-badge").forEach { badge ->
            val text = badge.text().replace('\u00A0', ' ').trim()
            if (text.isBlank()) return@forEach
            val cls = badge.className()
            when {
                "zx-badge-gold" in cls ->
                    rating = text.replace(",", ".").toDoubleOrNull()
                "zx-badge-views" in cls || "zx-badge-imdb" in cls -> { /* ignoré */ }
                text.endsWith("min") -> { /* durée : ignorée */ }
                text.toIntOrNull() != null ->
                    if (year == null) year = text.toInt()
                else -> genres.add(text)
            }
        }

        return if (isTv) {
            newTvSeriesLoadResponse(title, fetchUrl, TvType.TvSeries, parseEpisodes(doc)) {
                this.posterUrl = poster
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.year = year
                this.tags = genres.distinct()
            }
        } else {
            newMovieLoadResponse(title, fetchUrl, TvType.Movie, fetchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.score = rating?.let { Score.from10(it) }
                this.year = year
                this.tags = genres.distinct()
            }
        }
    }

    /** Épisodes d'une série : liens /episode/{slug}/{saison}-{épisode} (toutes saisons). */
    private fun parseEpisodes(doc: Document): List<Episode> {
        return doc.select("a[href*='/episode/']")
            .mapNotNull { a ->
                val href = a.attr("href")
                val m = episodeRegex.find(href) ?: return@mapNotNull null
                val epNumber = m.groupValues[2].toIntOrNull()
                // Certains titres sont des noms de fichiers (« Reacher-S04-E05.mp4 »)
                val name = a.selectFirst("h3")?.text()?.trim()?.takeIf {
                    it.isNotBlank() && !Regex("""\.(mp4|mkv|avi)$""", RegexOption.IGNORE_CASE).containsMatchIn(it)
                } ?: "Épisode ${epNumber ?: ""}".trim()
                val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                newEpisode(fixUrl(href)) {
                    this.name = name
                    this.season = m.groupValues[1].toIntOrNull()
                    this.episode = epNumber
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // -------------------------------------------------------------------------
    // Lecture : tous les serveurs de la page, en parallèle
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = app.get(data, headers = baseHeaders).text

        // 1) Boutons selectStream(n, 'url', 'Libellé | LANGUE', 'type') — tout est
        //    server-rendered dans le HTML, y compris les « Autres sources ».
        val players = LinkedHashMap<String, Pair<String, String>>() // url -> (label, type)
        selectStreamRegex.findAll(raw).forEach { m ->
            val url = jsUnescape(m.groupValues[1])
            if (url.startsWith("http") && url !in players) {
                players[url] = m.groupValues[2] to m.groupValues[3]
            }
        }

        // 2) Secours si les boutons manquent : iframes + hôtes connus
        if (players.isEmpty()) {
            Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(raw).forEach {
                val src = it.groupValues[1]
                if (src.startsWith("http") && !src.contains("zenix.best")) players[src] = "Lecteur ${players.size + 1}" to "other"
            }
            knownHosts.forEach { host ->
                Regex("""https?://[^"'\\\s<>]+""")
                    .findAll(raw).map { x -> x.value }
                    .filter { x -> x.contains(host, ignoreCase = true) && !x.contains("zenix.best") }
                    .forEach { if (it !in players) players[it] = host.replaceFirstChar { it.uppercase() } to "other" }
            }
        }

        // Les VF d'abord, puis VOSTFR, puis le reste, et les lecteurs internes Zenix
        // (protégés par pubs/captcha) en dernier.
        fun priority(url: String, label: String): Int = when {
            url.contains("zenix.best/embed") -> 4
            "| VF" in label -> 0
            "VOSTFR" in label -> 1
            else -> 2
        }
        val ordered = players.entries
            .sortedWith(compareBy({ priority(it.key, it.value.first) }, { it.value.first }))
            .toList()

        var found = false
        if (ordered.isNotEmpty()) {
            val lock = Any() // les callbacks de loadExtractor ne sont pas suspend
            val semaphore = Semaphore(6) // 6 serveurs à la fois max
            coroutineScope {
                ordered.map { (url, labelType) ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val label = labelType.first
                            var produced = false
                            // Extracteurs intégrés + ceux du plugin (1embed…)
                            runCatching {
                                loadExtractor(url, mainUrl, { sub ->
                                    synchronized(lock) { subtitleCallback(sub) }
                                }, { link ->
                                    produced = true
                                    synchronized(lock) { callback(link) }
                                })
                            }
                            // Secours générique : page de l'embed -> flux directs
                            if (!produced) {
                                runCatching {
                                    genericExtract(
                                        url,
                                        label,
                                        { sub -> synchronized(lock) { subtitleCallback(sub) } },
                                        { link -> synchronized(lock) { callback(link) } }
                                    )
                                }.getOrNull()?.let { produced = it }
                            }
                            produced
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll().let { results -> found = results.any { it } }
            }
        }

        // 3) Dernier recours : l'ID TMDB de la fiche -> 1embed (m3u8 directs)
        if (!found) {
            val tmdb = Regex("""[?&]tmdb=(\d+)""").find(raw)?.groupValues?.get(1)
            if (tmdb != null) {
                val epMatch = episodeRegex.find(data)
                val embedUrl = if (epMatch != null) {
                    "https://1embed.cc/embed/tv/$tmdb/${epMatch.groupValues[1]}/${epMatch.groupValues[2]}"
                } else if ("/tv-show/" in data) {
                    "https://1embed.cc/embed/tv/$tmdb/1/1"
                } else {
                    "https://1embed.cc/embed/movie/$tmdb"
                }
                runCatching {
                    OneEmbed().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                }.onFailure { }.onSuccess { found = true }
            }
        }

        return found
    }

    /**
     * Extraction générique de secours pour les hôtes sans extracteur intégré :
     * charge la page de l'embed et collecte tous les flux directs trouvés
     * (og:video, <source>, jwplayer file:, .m3u8/.mp4/.webm bruts).
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
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
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

    // -------------------------------------------------------------------------
    // Carte du catalogue : a.zenix-card__inner (aria-label = titre)
    // -------------------------------------------------------------------------
    private fun parseCard(el: Element): SearchResponse? {
        val href = el.attr("href").trim()
        if (href.isBlank() || href.startsWith("javascript")) return null
        val url = fixUrl(href)
        val isTv = "/tv-show/" in url

        var title = el.attr("aria-label").trim()
        if (title.isBlank()) {
            title = el.selectFirst(".zenix-card__title-wrapper img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        // data-src (lazyload) avant src (pixel gif de placeholder)
        val poster = el.selectFirst(".zenix-card__img")?.let { img ->
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                img.attr(attr).takeIf { it.startsWith("http") }
            }
        }
        val score = el.selectFirst(".zenix-card__badge--rating")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()
        val year = el.selectFirst(".zenix-card__meta > span")
            ?.text()?.trim()?.toIntOrNull()

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.score = score?.let { Score.from10(it) }
                this.year = year
            }
        }
    }

    private fun jsUnescape(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private val selectStreamRegex =
        Regex("""selectStream\(\d+,\s*'(.*?)',\s*'(.*?)',\s*'([a-zA-Z_]+)'\)""")

    private val episodeRegex = Regex("""/episode/[^/]+/(\d+)-(\d+)""")

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private val knownHosts = listOf(
        "1embed", "frembed", "vidfast", "peachify", "mostream", "hnembed", "catflix",
        "wavewatch", "videasy", "primesrc", "vidlove", "vidup", "braflix", "streamimdb",
        "vidsrc", "anyembed", "viduki", "vidking", "2embed", "superflix", "vidjoy",
        "nontongo", "vsembed", "dood", "mixdrop", "filemoon", "streamtape", "uqload",
        "streamwish", "voe", "upstream", "vidhide"
    )
}

// Réponse de /ajax/search/suggest
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZenixSuggestResponse(
    @JsonProperty("posts") val posts: List<ZenixSuggestPost> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZenixSuggestPost(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("quality") val quality: String? = null
)
