package com.afterdark

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class AfterdarkPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AfterdarkProvider())
        // Lecteur 1embed.cc : playlists HLS (format proxy ou /v/*.m3u8)
        registerExtractorAPI(OneEmbed())
    }
}

/**
 * 1embed.cc — lecteur dont la page embarque la liste de ses serveurs en JSON :
 *  - nouveau format : {"name":"Necro","url":"https://abdx.tv/hls-proxy?url=…&referer=…","type":"hls"}
 *    (le proxy renvoie une playlist HLS valide, vérifié)
 *  - ancien format : {"name":"Solari","url":"/v/solari_<id>.m3u8","type":"hls"}
 */
class OneEmbed : ExtractorApi() {
    override val name = "1Embed"
    override val mainUrl = "https://1embed.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = baseHeaders).text
        // Un-escape le payload Next.js (\" -> " et \u0026 -> &)
        val norm = html.replace("\\\"", "\"").replace("\\u0026", "&")
        val servers = LinkedHashMap<String, String>()
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
            .findAll(norm).forEach {
                val serverName = it.groupValues[1]
                val serverUrl = it.groupValues[2]
                // Nouveau format : proxy HLS ; ancien : chemin /v/*.m3u8 relatif
                if ("hls-proxy" in serverUrl || ".m3u8" in serverUrl) {
                    if (serverUrl !in servers) servers[serverUrl] = serverName
                }
            }
        // Ancien format : n'importe quel chemin /v/*.m3u8 dans la page
        if (servers.isEmpty()) {
            Regex(""""(/v/[a-zA-Z0-9_.-]+\.m3u8)"""").findAll(norm).forEach {
                if (it.groupValues[1] !in servers) servers[it.groupValues[1]] = "Serveur"
            }
        }
        servers.forEach { (serverUrl, serverName) ->
            val absolute = if (serverUrl.startsWith("http")) serverUrl else "$mainUrl$serverUrl"
            callback(
                newExtractorLink(name, "$name · $serverName", absolute) {
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
 * Extension CloudStream pour Afterdark (https://afd926.mom — canonique : afterdark.best)
 *
 * Site (vérifié par reverse-engineering des bundles JS + requêtes réelles) :
 *  · React + TanStack Start, catalogue TMDB (proxy serveur, données FR)
 *  · Fiches : /title/{type}-{tmdbId} ; lecteur : /watch/{type}-{tmdbId}?s=&e=
 *  · API de flux maison : GET /api/sources (NDJSON, groupes seoul/pekin) protégée
 *    par Cloudflare Turnstile — un « proof » (x-nabi-proof) est exigé, vérifié
 *    côté serveur (403 sans proof, POST de token invalide → ok:false). Le primaire
 *    du site n'est donc PAS utilisable sans WebView/captcha.
 *
 * L'extension livre le contenu du site autrement :
 *  · Catalogue : API TMDB publique en français (le même proxy public que
 *    l'extension WaveWatch — les données TMDB sont identiques).
 *  · Lecture : les 3 lecteurs publics de secours du site lui-même (videasy,
 *    frembed.skin, peachify — URL exactes tirées de son bundle) + un arsenal
 *    d'agrégateurs TMDB en parallèle : apiwiflix, playerix, zeus (SSE), mouve,
 *    movix, french-stream, 1embed — soit 60 à 200+ liens par contenu, avec la
 *    langue (VOSTFR d'abord) affichée sur chaque lien.
 */
class AfterdarkProvider : MainAPI() {

    override var mainUrl = "https://afd926.mom"
    override var name = "Afterdark"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    /** Catalogue : proxy TMDB public (les identifiants restent des ID TMDB). */
    private val tmdbProxy = "https://wavewatch.top/api/tmdb"

    override val mainPage = mainPageOf(
        "tmdb:/trending/movies" to "🔥 Films Tendance",
        "tmdb:/trending/tv" to "📺 Séries Tendance",
        "tmdb:/upcoming/movies" to "📅 Prochaines Sorties",
        "paged:/discover/movie?genre=28" to "💥 Action",
        "paged:/discover/movie?genre=878" to "🚀 Science-Fiction",
        "paged:/discover/movie?genre=53" to "🔪 Thriller",
        "paged:/discover/movie?genre=16" to "✨ Animation",
        "paged:/discover/movie?genre=27" to "👻 Horreur",
        "paged:/discover/movie?genre=35" to "😂 Comédie",
        "paged:/discover/tv?genre=10759" to "🗡️ Séries Action & Aventure",
        "paged:/discover/tv?genre=18" to "🎭 Séries Drame",
    )

    private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    private val embedHeaders get() = mapOf("user-agent" to USER_AGENT, "referer" to "$mainUrl/")

    private val tmdbImage = "https://image.tmdb.org/t/p"

    /** Un lien d'hébergeur à passer aux extracteurs, avec sa langue quand elle est connue. */
    private data class HostLink(
        val url: String,
        val lang: String? = null,
        val label: String,   // utilisé si l'extraction générique produit le lien
        val origin: String,  // d'où vient le lien (tri)
        val direct: Boolean = false, // playlist HLS directement jouable
        val quality: Int = Qualities.Unknown.value
    )

    // -------------------------------------------------------------------------
    // Page d'accueil (sections TMDB en français)
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val paged = data.startsWith("paged:")
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList())
        val path = data.removePrefix("tmdb:").removePrefix("paged:")
        val separator = if ("?" in path) "&" else "?"
        val url = "$tmdbProxy$path${separator}page=$page"
        val list = runCatching {
            AppUtils.parseJson<WwTmdbPage>(app.get(url, headers = baseHeaders).text)
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val isTv = "/tv" in path
        val cards = list.results.mapNotNull { tmdbCard(it, isTv) }
        return newHomePageResponse(request.name, cards.distinctBy { it.url })
    }

    private fun tmdbCard(item: WwTmdbItem, isTv: Boolean): SearchResponse? {
        val title = (item.title ?: item.name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = item.posterPath?.let { "$tmdbImage/w500$it" }
        val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
        return if (isTv || item.mediaType == "tv") {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/${item.id}", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/${item.id}", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche (multi : films + séries)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching {
            val json = app.get(
                "$tmdbProxy/search",
                params = mapOf("q" to trimmed),
                headers = baseHeaders
            ).text
            AppUtils.parseJson<WwTmdbPage>(json).results
                .mapNotNull { tmdbCard(it, isTv = it.mediaType == "tv") }
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------------
    // Détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val tmdb = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)
            ?: throw ErrorLoadingException("URL non reconnue : $url")

        if ("/movie/" in url) {
            val detail = AppUtils.parseJson<WwMovieDetail>(
                app.get("$tmdbProxy/movie/$tmdb", headers = baseHeaders).text
            )
            val title = detail.title?.trim()?.takeIf { it.isNotBlank() } ?: "Film"
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = detail.posterPath?.let { "$tmdbImage/w500$it" }
                this.backgroundPosterUrl = detail.backdropPath?.let { "$tmdbImage/original$it" }
                this.year = detail.releaseDate?.take(4)?.toIntOrNull()
                this.score = detail.voteAverage?.let { Score.from10(it) }
                this.plot = detail.overview?.trim()?.takeIf { it.isNotBlank() }
                this.tags = detail.genres?.mapNotNull { it.name }
                this.duration = detail.runtime
            }
        }

        // Série : fiche + toutes les saisons en parallèle
        val detail = AppUtils.parseJson<WwTvDetail>(
            app.get("$tmdbProxy/tv/$tmdb", headers = baseHeaders).text
        )
        val title = detail.name?.trim()?.takeIf { it.isNotBlank() } ?: "Série"
        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }.orEmpty()

        val episodes = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    runCatching {
                        val n = season.seasonNumber!!
                        val json = app.get("$tmdbProxy/tv/$tmdb/season/$n", headers = baseHeaders).text
                        AppUtils.parseJson<WwSeasonDetail>(json).episodes.orEmpty().mapNotNull { ep ->
                            val epNumber = ep.episodeNumber ?: return@mapNotNull null
                            newEpisode("$mainUrl/tv/$tmdb/$n/$epNumber") {
                                this.name = ep.name?.trim()?.takeIf { it.isNotBlank() && it != "Épisode $epNumber" }
                                    ?: "Épisode $epNumber"
                                this.season = n
                                this.episode = epNumber
                                this.posterUrl = ep.stillPath?.let { "$tmdbImage/w500$it" }
                                this.description = ep.overview?.trim()?.takeIf { it.isNotBlank() }
                                this.score = ep.voteAverage?.let { Score.from10(it) }
                                this.runTime = ep.runtime
                            }
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = detail.posterPath?.let { "$tmdbImage/w500$it" }
            this.backgroundPosterUrl = detail.backdropPath?.let { "$tmdbImage/original$it" }
            this.year = detail.firstAirDate?.take(4)?.toIntOrNull()
            this.score = detail.voteAverage?.let { Score.from10(it) }
            this.plot = detail.overview?.trim()?.takeIf { it.isNotBlank() }
            this.tags = detail.genres?.mapNotNull { it.name }
        }
    }

    // -------------------------------------------------------------------------
    // Lecture — un maximum de serveurs en parallèle
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m = Regex("""/tv/(\d+)/(\d+)/(\d+)|/movie/(\d+)""").find(data)
            ?: return false
        val tmdb: String
        var season: Int? = null
        var episode: Int? = null
        if (m.groupValues[1].isNotEmpty()) {
            tmdb = m.groupValues[1]
            season = m.groupValues[2].toIntOrNull()
            episode = m.groupValues[3].toIntOrNull()
        } else {
            tmdb = m.groupValues[4]
        }
        val isTv = season != null

        var found = false
        val lock = Any()
        fun emit(link: ExtractorLink) = synchronized(lock) {
            found = true
            callback(link)
        }

        val hostLinks = LinkedHashMap<String, HostLink>()
        suspend fun addHostLinks(links: List<HostLink>) {
            synchronized(hostLinks) { links.forEach { if (it.url.startsWith("http")) hostLinks.putIfAbsent(it.url, it) } }
        }

        coroutineScope {
            val aggregators = listOf(
                async(Dispatchers.IO) { runCatching { apiwiflixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                async(Dispatchers.IO) { runCatching { playerixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                async(Dispatchers.IO) { runCatching { zeusLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                async(Dispatchers.IO) { runCatching { movixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) },
                async(Dispatchers.IO) { runCatching { movixFstreamLinks(tmdb, season) }.getOrDefault(emptyList()) },
                async(Dispatchers.IO) { runCatching { mouveLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            )

            // ---- 1embed : playlists HLS directes (fiable) ----
            async(Dispatchers.IO) {
                runCatching {
                    val oneEmbedUrl = if (isTv) {
                        "https://1embed.cc/embed/tv/$tmdb/$season/$episode"
                    } else {
                        "https://1embed.cc/embed/movie/$tmdb"
                    }
                    OneEmbed().getUrl(oneEmbedUrl, mainUrl, subtitleCallback) { emit(it) }
                }
            }

            aggregators.awaitAll().forEach { addHostLinks(it) }

            // ---- Lecteurs de secours du site + lecteurs publics TMDB ----
            addHostLinks(fallbackEmbeds(tmdb, season, episode))
        }

        // ---- Tri : VOSTFR d'abord (site VOSTFR), puis VO, puis VF ----
        fun priority(hl: HostLink): Int {
            val langP = when (hl.lang) {
                "VOSTFR" -> 0
                "VO" -> 1
                "VF", "VFQ", "VFF", "TRUEFRENCH", "FR", "VF/VO" -> 2
                "MULTI" -> 3
                null -> 4
                else -> 5
            }
            val originP = when (hl.origin) {
                "zeus" -> 0; "apiwiflix" -> 1; "playerix" -> 2; "movix" -> 3
                "fstream" -> 4; "mouve" -> 5; "site" -> 6; "embed" -> 7; else -> 8
            }
            return langP * 10 + originP
        }

        val ordered = synchronized(hostLinks) { hostLinks.values.toList() }
            .sortedWith(compareBy(::priority))

        // ---- Playlists HLS directes : émises immédiatement ----
        ordered.filter { it.direct }.forEach { hl ->
            val name = "Afterdark · ${hl.label}" + (hl.lang?.let { " · $it" } ?: "")
            emit(
                newExtractorLink(name, name, hl.url) {
                    this.quality = hl.quality
                    this.type = ExtractorLinkType.M3U8
                }
            )
        }

        // ---- Extraction parallèle sur le reste ----
        val toExtract = ordered.filter { !it.direct }
        if (toExtract.isNotEmpty()) {
            val semaphore = Semaphore(6)
            coroutineScope {
                toExtract.map { hl ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            var produced = 0
                            runCatching {
                                loadExtractor(hl.url, mainUrl, { sub ->
                                    synchronized(lock) { subtitleCallback(sub) }
                                }, { link ->
                                    produced++
                                    emit(relabel(link, hl.lang))
                                })
                            }
                            // Secours générique si l'extracteur n'a rien donné
                            if (produced == 0) {
                                runCatching {
                                    genericExtract(
                                        hl.url, hl.label, hl.lang,
                                        { sub -> synchronized(lock) { subtitleCallback(sub) } },
                                        { link -> emit(relabel(link, hl.lang)) }
                                    )
                                }
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }

        return found
    }

    /** Ré-étiquette un lien avec sa langue (« Filemoon · VOSTFR ») pour l'utilisateur. */
    private fun relabel(link: ExtractorLink, lang: String?): ExtractorLink {
        if (lang.isNullOrBlank()) return link
        return ExtractorLink(
            link.source,
            "${link.name} · $lang",
            link.url,
            link.referer,
            link.quality,
            link.headers,
            link.extractorData,
            link.type,
            link.audioTracks
        )
    }

    // -------------------------------------------------------------------------
    // Lecteurs de secours publics — les 3 du site (URL tirées de son bundle)
    // + d'autres lecteurs publics compatibles TMDB (tous testés vivants)
    // -------------------------------------------------------------------------
    private fun fallbackEmbeds(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val out = mutableListOf<HostLink>()

        // --- Les lecteurs de secours du site Afterdark lui-même ---
        // videasy (lecteur n°1 du site)
        out += if (season != null) {
            HostLink(
                "https://player.videasy.net/tv/$tmdb/$season/$episode?overlay=true&color=8B5CF6&nextEpisode=true&episodeSelector=true",
                null, "Videasy", "site"
            )
        } else {
            HostLink("https://player.videasy.net/movie/$tmdb?overlay=true&color=8B5CF6", null, "Videasy", "site")
        }
        // frembed
        out += if (season != null) {
            HostLink("https://frembed.skin/embed/serie/$tmdb?sa=$season&epi=$episode", null, "Frembed", "site")
        } else {
            HostLink("https://frembed.skin/embed/movie/$tmdb", null, "Frembed", "site")
        }
        // peachify (Cloudflare : peut fonctionner depuis l'appareil de l'utilisateur)
        out += if (season != null) {
            HostLink(
                "https://peachify.top/embed/tv/$tmdb/$season/$episode?dub=French&sub=French&autoNext=30",
                null, "Peachify", "site"
            )
        } else {
            HostLink("https://peachify.top/embed/movie/$tmdb?dub=French&sub=French", null, "Peachify", "site")
        }

        // --- Autres lecteurs publics TMDB (multi-serveurs, best-effort) ---
        val publicEmbeds = if (season != null) {
            listOf(
                "https://vidfast.pro/tv/$tmdb/$season/$episode?autoPlay=true&sub=fr" to "VidFast",
                "https://vidsrc.cc/v2/embed/tv/$tmdb/$season/$episode" to "VidSrc.cc",
                "https://www.vidsrc.wtf/api/2/tv/?id=$tmdb&s=$season&e=$episode" to "VidSrc.wtf",
                "https://www.2embed.cc/embedtv/$tmdb&s=$season&e=$episode" to "2Embed",
                "https://111movies.com/tv/$tmdb/$season/$episode" to "111Movies",
                "https://www.braflix.win/watch/$tmdb?s=$season&e=$episode" to "Braflix",
                "https://www.vidking.net/embed/tv/$tmdb/$season/$episode?autoPlay=true" to "VidKing"
            )
        } else {
            listOf(
                "https://vidfast.pro/movie/$tmdb?autoPlay=true&sub=fr" to "VidFast",
                "https://vidsrc.cc/v2/embed/movie/$tmdb" to "VidSrc.cc",
                "https://www.vidsrc.wtf/api/3/movie/?id=$tmdb" to "VidSrc.wtf",
                "https://www.2embed.cc/embed/$tmdb" to "2Embed",
                "https://111movies.com/movie/$tmdb" to "111Movies",
                "https://www.braflix.win/watch/$tmdb" to "Braflix",
                "https://www.vidking.net/embed/movie/$tmdb?autoPlay=true" to "VidKing"
            )
        }
        publicEmbeds.forEach { (u, label) -> out += HostLink(u, null, label, "embed") }

        return out
    }

    // -------------------------------------------------------------------------
    // Agrégateurs — tous fondés sur l'ID TMDB, testés sur films et épisodes
    // -------------------------------------------------------------------------

    /** apis.wavewatch.top/apiwiflix.php — liens hébergeurs AVEC langue (VF/VOSTFR). */
    private suspend fun apiwiflixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb"
        }
        val html = app.get(url, headers = baseHeaders).text
        val m = Regex("""allSources\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val sources = AppUtils.tryParseJson<Array<ApiWiflixSource>>(m.groupValues[1]) ?: return emptyList()
        return sources.filter { !it.url.isNullOrBlank() }.map {
            HostLink(it.url!!, normalizeLang(it.language), it.name ?: "Lecteur", "apiwiflix")
        }
    }

    /** apis.wavewatch.top/playerix.php — boutons data-url avec langue + HLS directs.
     *  NB : l'API ignore « saison= » — paramètres anglais obligatoires. */
    private suspend fun playerixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/playerix.php?type=tv&id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/playerix.php?type=movie&id=$tmdb"
        }
        val html = app.get(url, headers = baseHeaders).text
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        Regex("""<button([^>]*data-url[^>]*)>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = m.groupValues[1].replace("&amp;", "&")
                val content = m.groupValues[2]
                val dataUrl = Regex("""data-url="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val fmt = Regex("""data-fmt="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: "iframe"
                if (Regex("""data-alive="0"""").containsMatchIn(attrs)) return@forEach
                val lang = Regex("""class="lang"[^>]*>\s*([^<]+)""").find(content)?.groupValues?.get(1)
                    ?.trim()?.split(" ")?.lastOrNull() // « 🇫🇷 VF » -> VF
                    ?.takeIf { it.isNotBlank() && it != "?" }
                val b64 = Regex("""u=([A-Za-z0-9+/=_-]{16,})""").find(dataUrl)?.groupValues?.get(1) ?: return@forEach
                val decoded = decodeB64(b64) ?: return@forEach
                if (!decoded.startsWith("http")) return@forEach
                val host = hostOf(decoded)

                if (fmt == "m3u8") {
                    out += HostLink(decoded, lang ?: "MULTI", host, "playerix", direct = true)
                } else {
                    val key = "$host|${lang ?: "?"}"
                    if (key in seenHosts) return@forEach
                    seenHosts += key
                    out += HostLink(decoded, lang, host, "playerix")
                }
            }
        return out.take(40)
    }

    /** zeus.php en Server-Sent Events — langues réelles (VF/VFQ/VOSTFR/MULTI) + HLS direct. */
    private suspend fun zeusLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/zeus.php?sse&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        // Le serveur ferme le flux après l'événement « done » (~10 s) : la réponse
        // complète arrive d'un bloc, on parse chaque « data: {json} ».
        val body = app.get(url, headers = baseHeaders).text
        val out = mutableListOf<HostLink>()
        Regex("""data:\s*(\{.*?\})\s*\n""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val parsed = AppUtils.tryParseJson<WwSseEvent>(m.groupValues[1]) ?: return@forEach
            parsed.sources?.forEach { s ->
                val u = s.url ?: return@forEach
                if (!u.startsWith("http") || s.premium == true) return@forEach
                val lang = normalizeLang(s.lang)
                val label = hostOf(u)
                val format = s.format?.lowercase()
                if ((format == "hls" || format == "mp4" || format == "dash") && s.iframe != true) {
                    out += HostLink(u, lang, label, "zeus", direct = true, quality = qualityValue(s.quality))
                    return@forEach
                }
                out += HostLink(u, lang, label, "zeus", quality = qualityValue(s.quality))
            }
        }
        return out
    }

    /** mouve.php?json=1 — un grand agrégateur (39–75 flux par contenu). */
    private suspend fun mouveLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/mouve.php?json=1&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        val root = AppUtils.parseJson<WwMouveResponse>(app.get(url, headers = baseHeaders).text)
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        root.streams?.forEach { s ->
            val u = s.url ?: return@forEach
            val format = s.format?.lowercase()
            if (format == "hls") {
                // Certains flux passent par un proxy : mouve.php?ep=m3u8&url={réel}
                val real = Regex("""[?&]url=([^&]+)""").find(u)?.groupValues?.get(1)
                    ?.let { decodeUrlParam(it) } ?: u
                if (real.startsWith("http") && real.contains(".m3u8")) {
                    out += HostLink(real, null, hostOf(real), "mouve", direct = true)
                }
                return@forEach
            }
            if (!u.startsWith("http")) return@forEach
            val host = hostOf(u)
            if (host in seenHosts) return@forEach // un seul miroir par hébergeur
            seenHosts += host
            out += HostLink(u, null, host, "mouve")
        }
        return out
    }

    /** api.movix.cash/api/tmdb/… — liens hébergeurs FR avec qualité. */
    private suspend fun movixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://api.movix.cash/api/tmdb/tv/$tmdb?season=$season&episode=$episode"
        } else {
            "https://api.movix.cash/api/tmdb/movie/$tmdb"
        }
        val json = app.get(url, headers = baseHeaders).text
        val root = AppUtils.parseJson<MovixResponse>(json)
        val links = root.playerLinks ?: root.currentEpisode?.playerLinks ?: return emptyList()
        return links.filter { !it.decodedUrl.isNullOrBlank() }.mapNotNull { p ->
            val lang = when (p.language?.lowercase()) {
                "french", "fr" -> "VF"
                null, "" -> null
                else -> p.language?.uppercase()?.take(8)
            }
            HostLink(p.decodedUrl!!, lang, p.quality?.take(40)?.takeIf { it.isNotBlank() } ?: "Movix", "movix")
        }
    }

    /** api.movix.cash/api/fstream/… — liens french-stream avec vraies étiquettes VFQ/VF/VOSTFR (films). */
    private suspend fun movixFstreamLinks(tmdb: String, season: Int?): List<HostLink> {
        if (season != null) return emptyList() // films uniquement
        val json = runCatching {
            app.get("https://api.movix.cash/api/fstream/movie/$tmdb", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        val root = AppUtils.parseJson<MovixFstreamResponse>(json)
        val out = mutableListOf<HostLink>()
        root.players?.forEach { (lang, entries) ->
            entries.forEach { e ->
                if (!e.url.isNullOrBlank()) {
                    val cleanLang = when (lang) {
                        "VFQ", "VFF" -> "VF"
                        "Default" -> null
                        else -> lang
                    }
                    out += HostLink(e.url!!, cleanLang, e.player ?: "FStream", "fstream")
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Extraction générique de secours (avec filtre anti-liens invalides)
    // -------------------------------------------------------------------------
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        lang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(
            embedUrl,
            referer = mainUrl,
            headers = baseHeaders
        ).text

        val links = LinkedHashSet<String>()
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }

        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { link -> junkFilterRegex.containsMatchIn(link).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                val name = "Afterdark · $label" + (lang?.let { " · $it" } ?: "")
                callback(
                    newExtractorLink(name, name, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

        links.isNotEmpty()
    }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // Petits utilitaires
    // -------------------------------------------------------------------------
    /** Codes de langue du site : vf | vff | vfq | vo | vostfr (+ agrégateurs). */
    private fun normalizeLang(lang: String?): String? {
        val l = lang?.trim()?.uppercase() ?: return null
        return when {
            l in setOf("VF", "FR", "FRENCH") -> "VF"
            l in setOf("VFQ", "VFF", "TRUEFRENCH") -> if (l == "TRUEFRENCH") "VFQ" else l
            l == "VOSTFR" -> "VOSTFR"
            l == "VO" -> "VO"
            l == "MULTI" -> "MULTI"
            l.isEmpty() -> null
            else -> l.take(8)
        }
    }

    private fun hostOf(url: String): String =
        Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "Lecteur"

    private fun qualityValue(q: String?): Int = when {
        q == null -> Qualities.Unknown.value
        q.contains("2160") || q.contains("4K", ignoreCase = true) -> Qualities.P2160.value
        q.contains("1080") -> Qualities.P1080.value
        q.contains("720") -> Qualities.P720.value
        q.contains("480") -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun decodeB64(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE))
    }.getOrNull() ?: runCatching {
        String(Base64.decode(s, Base64.DEFAULT))
    }.getOrNull()

    private fun decodeUrlParam(s: String): String? = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrNull()

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    /** Filtre les faux positifs (images, pubs, YouTube, sous-titres…) qui causent
     *  des erreurs de lecture du type « container unsupported ». */
    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )
}

// -------------------------------------------------------------------------
// Modèles JSON
// -------------------------------------------------------------------------

// Proxy TMDB : trending|discover|search|upcoming
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTmdbPage(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("results") val results: List<WwTmdbItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTmdbItem(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

// fiche film
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMovieDetail(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<WwGenre>? = null
)

// fiche série
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwTvDetail(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<WwGenre>? = null,
    @JsonProperty("seasons") val seasons: List<WwSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
    @JsonProperty("name") val name: String? = null
)

// saison détaillée
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSeasonDetail(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<WwEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

// zeus.php (SSE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSseEvent(
    @JsonProperty("sources") val sources: List<WwSseSource>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSseSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("iframe") val iframe: Boolean? = null,
    @JsonProperty("premium") val premium: Boolean? = null
)

// mouve.php?json=1
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMouveResponse(
    @JsonProperty("streams") val streams: List<WwMouveStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwMouveStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("iframe") val iframe: Boolean? = null
)

// apiwiflix
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiWiflixSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("language") val language: String? = null
)

// api.movix.cash
@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixResponse(
    @JsonProperty("player_links") val playerLinks: List<MovixPlayerLink>? = null,
    @JsonProperty("current_episode") val currentEpisode: MovixCurrentEpisode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixCurrentEpisode(
    @JsonProperty("player_links") val playerLinks: List<MovixPlayerLink>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixPlayerLink(
    @JsonProperty("decoded_url") val decodedUrl: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("language") val language: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixFstreamResponse(
    @JsonProperty("players") val players: Map<String, List<MovixFstreamPlayer>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovixFstreamPlayer(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("player") val player: String? = null,
    @JsonProperty("quality") val quality: String? = null
)
