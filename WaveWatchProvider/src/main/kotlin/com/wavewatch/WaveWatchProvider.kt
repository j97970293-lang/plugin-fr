package com.wavewatch

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
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
import kotlinx.coroutines.sync.Semaphore
import java.net.URLEncoder

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class WaveWatchPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        WaveWatchProvider.appContext = context.applicationContext
        registerMainAPI(WaveWatchProvider())
        // Custom extractor for 1embed.cc (direct HLS playlists in the page)
        registerExtractorAPI(OneEmbed())
        // ansembed.net (JWPlayer) — hébergeur du secours anime AnimoFlix
        registerExtractorAPI(AnsEmbed())
        // Bouton « Réglages » sur la fiche de l'extension dans CloudStream
        openSettings = { ctx -> WaveWatchProvider.showSettings(ctx) }
    }
}

/**
 * ansembed.net — hébergeur JWPlayer (clone VidMoly) utilisé par AnimoFlix.
 * Extrait `sources: [{ file: 'https://…/master.m3u8' }]` de la page d'embed.
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
        com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
            .extractStreamLinks(script, name, mainUrl, callback, subtitleCallback)
    }
}

/**
 * 1embed.cc — embed player whose page embeds a Next.js payload listing its
 * servers as JSON: `{"name":"Solari","url":"/v/solari_<id>.m3u8","type":"hls"}`.
 * The playlists are served directly (no referer needed, verified).
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
 * Extension CloudStream pour https://wavewatch.top
 *
 * Structure du site (vérifiée par requêtes réelles) :
 *   React SPA + API JSON publique FastAPI, catalogue TMDB :
 *   /api/tmdb/trending/{movies|tv|anime}     listes d'accueil (page 1)
 *   /api/tmdb/discover/movie?genre={id}      par genre (paginé)
 *   /api/tmdb/upcoming/movies                sorties à venir
 *   /api/tmdb/search?q=…                     recherche multi (media_type)
 *   /api/tmdb/movie/{id} · /api/tmdb/tv/{id} fiches (FR)
 *   /api/tmdb/tv/{id}/season/{n}             épisodes
 *   /api/tv-channels                         79 chaînes TV en direct
 *
 * Lecture — lecteur maison wwembed.wavewatch.top :
 *   /api/v1/streaming/ww-movie-{tmdb}
 *   /api/v1/streaming/ww-tv-{tmdb}-s{S}-e{E}
 *   /api/v1/live/ww-live-{uuid}              chaînes live
 * Ces pages contiennent `var _src=[{name,url,quality,language,…}]` : la liste
 * complète des sources du site (37 par contenu) avec la langue de chacune.
 * L'extension interroge en parallèle les meilleurs agrégateurs de cette liste
 * (apiwiflix, playerix, mouve, zeus/SSE, movix, french-stream, 1embed) avec des
 * URL reconstruites (certaines URL du site sont boguées : `saison=` ignoré,
 * `id=108978/1`, `&=1`…), puis essaye les lecteurs externes restants
 * (vidfast, vidsrc, frembed, 2embed…). Tous les liens sont étiquetés
 * VF / VOSTFR / VFQ / MULTI.
 */
class WaveWatchProvider : MainAPI() {

    override var mainUrl = "https://wavewatch.top"
    override var name = "WaveWatch"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
    override val hasMainPage = true

    /** Préfixe du data de section : « tmdb: » (page 1 seule), « paged: » (pagination), « live: ». */
    override val mainPage = mainPageOf(
        "tmdb:/api/tmdb/trending/movies" to "🔥 Films Tendance",
        "tmdb:/api/tmdb/trending/tv" to "📺 Séries Tendance",
        "tmdb:/api/tmdb/trending/anime" to "🌸 Animes Tendance",
        "tmdb:/api/tmdb/upcoming/movies" to "📅 Prochaines Sorties",
        "paged:/api/tmdb/discover/movie?genre=28" to "💥 Films Action",
        "paged:/api/tmdb/discover/movie?genre=35" to "😂 Films Comédie",
        "paged:/api/tmdb/discover/movie?genre=878" to "🚀 Films Science-Fiction",
        "paged:/api/tmdb/discover/movie?genre=27" to "👻 Films Horreur",
        "paged:/api/tmdb/discover/movie?genre=53" to "🔪 Films Thriller",
        "paged:/api/tmdb/discover/movie?genre=16" to "✨ Films Animation",
        "paged:/api/tmdb/discover/tv?genre=10759" to "🗡️ Séries Action & Aventure",
        "paged:/api/tmdb/discover/tv?genre=18" to "🎭 Séries Drame",
        "paged:/api/tmdb/discover/tv?genre=16" to "🎴 Séries Animation",
        "live:" to "📡 Chaînes TV en Direct",
    )

    private val baseHeaders = mapOf("user-agent" to USER_AGENT)
    private val embedHeaders get() = mapOf("user-agent" to USER_AGENT, "referer" to "$mainUrl/")

    private val tmdbImage = "https://image.tmdb.org/t/p"

    // -------------------------------------------------------------------------
    // Réglages : adresse du site modifiable (miroirs / changement de domaine)
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://wavewatch.top"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "wavewatch_settings"
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
                        text = "Adresse du site WaveWatch (à changer s'il déménage) :"
                    }
                )
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("WaveWatch")
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

    /** Lecteur maison wwembed : même domaine que le site (wwedit.{domaine}). */
    private fun wwembedBase(): String {
        val host = currentUrl().removePrefix("https://").removePrefix("http://").substringBefore('/')
        return "https://wwembed.$host"
    }

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
    // Page d'accueil
    // -------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val data = request.data

        // ---- Chaînes TV en direct (API JSON publique) ----
        if (data == "live:") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val channels = runCatching {
                AppUtils.parseJson<WwChannels>(app.get("$mainUrl/api/tv-channels", headers = baseHeaders).text)
            }.getOrNull()
            val cards = channels?.channels
                ?.filter { it.isActive && it.streamUrl.orNull()?.startsWith("http") == true && it.category?.trim()?.uppercase() != "ADULTE" }
                ?.map { ch ->
                    newLiveSearchResponse(ch.name ?: "Chaîne", "$mainUrl/live/${ch.id}") {
                        this.posterUrl = ch.logoUrl.orNull()
                        this.lang = ch.country?.takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
            return newHomePageResponse(request.name, cards)
        }

        // ---- Sections catalogue TMDB ----
        val paged = data.startsWith("paged:")
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList())
        val path = data.removePrefix("tmdb:").removePrefix("paged:")
        val separator = if ("?" in path) "&" else "?"
        val url = "$mainUrl$path${separator}page=$page"
        val list = runCatching {
            AppUtils.parseJson<WwTmdbPage>(app.get(url, headers = baseHeaders).text)
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val isTv = "/tv" in path
        val isAnime = "/anime" in path
        val cards = list.results.mapNotNull { tmdbCard(it, isTv, isAnime) }
        return newHomePageResponse(request.name, cards.distinctBy { it.url })
    }

    private fun tmdbCard(item: WwTmdbItem, isTv: Boolean, isAnime: Boolean): SearchResponse? {
        val title = (item.title ?: item.name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = item.posterPath?.let { "$tmdbImage/w500$it" }
        val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
        return when {
            isAnime -> newTvSeriesSearchResponse(title, "$mainUrl/tv/${item.id}", TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
            isTv -> newTvSeriesSearchResponse(title, "$mainUrl/tv/${item.id}", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
            else -> newMovieSearchResponse(title, "$mainUrl/movie/${item.id}", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = item.voteAverage?.let { Score.from10(it) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche : API multi /api/tmdb/search?q= (media_type dans chaque résultat)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return runCatching {
            val json = app.get(
                "$mainUrl/api/tmdb/search",
                params = mapOf("q" to trimmed),
                headers = baseHeaders
            ).text
            AppUtils.parseJson<WwTmdbPage>(json).results
                .mapNotNull { tmdbCard(it, isTv = it.mediaType == "tv", isAnime = false) }
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------------
    // Détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        // ---- Chaîne TV en direct ----
        if ("/live/" in url) {
            val channelId = url.substringAfterLast("/")
            val channels = runCatching {
                AppUtils.parseJson<WwChannels>(app.get("$mainUrl/api/tv-channels", headers = baseHeaders).text)
            }.getOrNull()
            val ch = channels?.channels?.firstOrNull { it.id == channelId }
            val name = ch?.name ?: "Chaîne TV"
            return newLiveStreamLoadResponse(name, url, url) {
                this.posterUrl = ch?.logoUrl.orNull()
                this.plot = ch?.description.orNull()
                this.tags = listOfNotNull(ch?.category?.trim()?.replaceFirstChar { it.uppercase() })
            }
        }

        val tmdb = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)
            ?: throw ErrorLoadingException("URL non reconnue : $url")

        if ("/movie/" in url) {
            val detail = AppUtils.parseJson<WwMovieDetail>(
                app.get("$mainUrl/api/tmdb/movie/$tmdb", headers = baseHeaders).text
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

        // ---- Série : fiche + toutes les saisons (en parallèle) ----
        val detail = AppUtils.parseJson<WwTvDetail>(
            app.get("$mainUrl/api/tmdb/tv/$tmdb", headers = baseHeaders).text
        )
        val title = detail.name?.trim()?.takeIf { it.isNotBlank() } ?: "Série"
        val isAnime = detail.genres?.any { it.name.equals("Animation", ignoreCase = true) } == true
        val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }.orEmpty()

        // (saison, épisode, sIndex = place dans la saison, absIndex corrigé après tri)
        data class RawEp(
            val season: Int, val number: Int, val sIndex: Int,
            val ep: WwEpisode
        )

        val rawEpisodes = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    runCatching {
                        val n = season.seasonNumber!!
                        val json = app.get("$mainUrl/api/tmdb/tv/$tmdb/season/$n", headers = baseHeaders).text
                        val eps = AppUtils.parseJson<WwSeasonDetail>(json).episodes.orEmpty()
                            .filter { (it.episodeNumber ?: 0) > 0 }
                            .sortedBy { it.episodeNumber }
                        val first = eps.firstOrNull()?.episodeNumber ?: 0
                        eps.map { ep ->
                            RawEp(n, ep.episodeNumber!!, ep.episodeNumber!! - first + 1, ep)
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }.sortedWith(compareBy({ it.season }, { it.number }))

        // Index absolus (position cumulée) + drapeau anime pour le secours AnimoFlix
        var absBase = 0
        val perSeasonCounts = rawEpisodes.groupBy { it.season }
        val animeAllowed = isAnime || rawEpisodes.size >= 60
        val episodes = rawEpisodes.map { raw ->
            val absIndex = absBase + raw.sIndex
            if (perSeasonCounts[raw.season]?.lastOrNull() === raw) {
                absBase += perSeasonCounts[raw.season]!!.size
            }
            val packed = "?t=" + URLEncoder.encode(title, "UTF-8") +
                "&i=${raw.sIndex}&a=$absIndex&af=${if (animeAllowed) 1 else 0}"
            val ep = raw.ep
            newEpisode("$mainUrl/tv/$tmdb/${raw.season}/${raw.number}$packed") {
                this.name = ep.name?.trim()?.takeIf { it.isNotBlank() && it != "Épisode ${raw.number}" }
                    ?: "Épisode ${raw.number}"
                this.season = raw.season
                this.episode = raw.number
                this.posterUrl = ep.stillPath?.let { "$tmdbImage/w500$it" }
                this.description = ep.overview?.trim()?.takeIf { it.isNotBlank() }
                this.score = ep.voteAverage?.let { Score.from10(it) }
                this.runTime = ep.runtime
            }
        }

        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = detail.posterPath?.let { "$tmdbImage/w500$it" }
            this.backgroundPosterUrl = detail.backdropPath?.let { "$tmdbImage/original$it" }
            this.year = detail.firstAirDate?.take(4)?.toIntOrNull()
            this.score = detail.voteAverage?.let { Score.from10(it) }
            this.plot = detail.overview?.trim()?.takeIf { it.isNotBlank() }
            this.tags = detail.genres?.mapNotNull { it.name }
        }
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        // ---- Chaîne TV en direct : la page wwembed contient le flux HLS ----
        if ("/live/" in data) {
            val channelId = data.substringAfterLast("/")
            val channels = runCatching {
                AppUtils.parseJson<WwChannels>(app.get("$mainUrl/api/tv-channels", headers = baseHeaders).text)
            }.getOrNull()
            val streamUrl = channels?.channels?.firstOrNull { it.id == channelId }?.streamUrl.orNull()
                ?: return false
            val html = runCatching {
                app.get(streamUrl, headers = embedHeaders).text
            }.getOrNull() ?: return false
            var found = false
            parseSrcArray(html).forEach { src ->
                val link = src.url ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                found = true
                callback(
                    newExtractorLink("WaveWatch TV", "WaveWatch TV · ${src.name ?: "Direct"}", link) {
                        this.quality = qualityValue(src.quality)
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
            return found
        }

        // ---- Film / épisode ----
        val m = Regex("""/tv/(\d+)/(\d+)/(\d+)(?:\?([^#]*))?|/movie/(\d+)""").find(data)
            ?: return false
        val tmdb: String
        var season: Int? = null
        var episode: Int? = null
        val params: Map<String, String>
        if (m.groupValues[1].isNotEmpty()) {
            tmdb = m.groupValues[1]
            season = m.groupValues[2].toIntOrNull()
            episode = m.groupValues[3].toIntOrNull()
            params = parseParams(m.groupValues[4])
        } else {
            tmdb = m.groupValues[5]
            params = emptyMap()
        }
        val isTv = season != null
        val showTitle = params["t"]
        val seasonIndex = params["i"]?.toIntOrNull()
        val absIndex = params["a"]?.toIntOrNull()
        val animeAllowed = params["af"] == "1"

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

        // La liste des sources du site, avec la langue de chacune
        var siteSources: List<WwSource> = emptyList()

        coroutineScope {
            // ---- La liste du site (wwembed) : langue par lecteur + lecteurs externes ----
            val siteAsync = async(Dispatchers.IO) {
                runCatching {
                    val embedUrl = if (season != null && episode != null) {
                        wwembedBase() + "/api/v1/streaming/ww-tv-$tmdb-s$season-e$episode"
                    } else {
                        wwembedBase() + "/api/v1/streaming/ww-movie-$tmdb"
                    }
                    parseSrcArray(app.get(embedUrl, headers = embedHeaders).text)
                }.getOrDefault(emptyList())
            }

            // ---- Agrégateurs internes (URL reconstruites, params vérifiés) ----
            val aggregators = mutableListOf<kotlinx.coroutines.Deferred<List<HostLink>>>()
            aggregators += async(Dispatchers.IO) { runCatching { zeusLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { apiwiflixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            // playerix : en TV seuls les boutons HLS (data-fmt="m3u8") sont fiables —
            // les boutons iframe renvoient les mêmes liens pour tous les épisodes (vérifié).
            aggregators += async(Dispatchers.IO) { runCatching { playerixLinks(tmdb, season, episode, m3u8Only = isTv) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { movixLinks(tmdb, season, episode) }.getOrDefault(emptyList()) }
            aggregators += async(Dispatchers.IO) { runCatching { movixFstreamLinks(tmdb, season) }.getOrDefault(emptyList()) }
            // mouve : films uniquement — en TV la majorité des liens ne dépendent pas
            // de l'épisode demandé (vérifié : mêmes URLs pour 2x1 et 2x2).
            if (!isTv) {
                aggregators += async(Dispatchers.IO) { runCatching { mouveLinks(tmdb, null, null) }.getOrDefault(emptyList()) }
            }

            // ---- AnimoFlix : épisodes d'animes (One Piece & co), y compris les tout derniers ----
            if (isTv && animeAllowed && showTitle != null && seasonIndex != null && absIndex != null) {
                aggregators += async(Dispatchers.IO) {
                    runCatching { animoflixLinks(showTitle, season!!, seasonIndex, absIndex) }.getOrDefault(emptyList())
                }
            }

            // ---- 1embed : playlists HLS directes, fiables ----
            async(Dispatchers.IO) {
                runCatching {
                    val oneEmbedUrl = if (season != null && episode != null) {
                        "https://1embed.cc/embed/tv/$tmdb/$season/$episode"
                    } else {
                        "https://1embed.cc/embed/movie/$tmdb"
                    }
                    OneEmbed().getUrl(oneEmbedUrl, mainUrl, subtitleCallback) { emit(it) }
                }
            }

            aggregators.awaitAll().forEach { addHostLinks(it) }

            // ---- Lecteurs externes de la liste du site ----
            siteSources = siteAsync.await()
            val external = siteSources.mapNotNull { src ->
                val u = src.url ?: return@mapNotNull null
                if (!u.startsWith("http")) return@mapNotNull null
                // Les agrégateurs déjà interrogés directement (avec les bons paramètres)
                val handled = "apis.wavewatch.top" in u || "1embed.cc" in u ||
                    "api.movix.cash" in u || "blinkflux.lol" in u || "api.tfx05.lol" in u
                if (handled) return@mapNotNull null
                HostLink(u, normalizeLang(src.language), src.name ?: "Lecteur", "site", quality = qualityValue(src.quality))
            }
            addHostLinks(external)
        }

        // ---- Tri : VF d'abord, puis VOSTFR, puis langues inconnues ----
        fun priority(hl: HostLink): Int {
            val langP = when (hl.lang) {
                "VF", "VFQ", "VFF", "TRUEFRENCH", "FR", "VF/VO" -> 0
                "VOSTFR" -> 1
                "MULTI" -> 2
                null -> 3
                else -> 4
            }
            val originP = when (hl.origin) {
                "zeus" -> 0; "animoflix" -> 1; "apiwiflix" -> 2; "movix" -> 3; "fstream" -> 4
                "playerix" -> 5; "mouve" -> 6; "site" -> 7; "animoflix-r" -> 8; else -> 9
            }
            return langP * 10 + originP
        }

        val ordered = synchronized(hostLinks) { hostLinks.values.toList() }
            .sortedWith(compareBy(::priority))

        // ---- Playlists HLS directes : émises immédiatement ----
        ordered.filter { it.direct }.forEach { hl ->
            val name = "WaveWatch · ${hl.label}" + (hl.lang?.let { " · $it" } ?: "")
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

    /** Paramètres de l'URL de données (?t=…&i=…&a=…&af=…). */
    private fun parseParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return Regex("""(?:^|&)([a-z]+)=([^&]*)""")
            .findAll(query)
            .associate {
                it.groupValues[1] to runCatching {
                    java.net.URLDecoder.decode(it.groupValues[2], "UTF-8")
                }.getOrDefault(it.groupValues[2])
            }
    }

    // -------------------------------------------------------------------------
    // Secours anime — épisodes d'animoflix.to (One Piece, Naruto, démons & co).
    // animoflix a les DERNIERS épisodes VOSTFR là où les agrégateurs culbutent,
    // avec un hébergeur maison (ansembed, HLS) + sibnet.
    // -------------------------------------------------------------------------
    private val animoflixCf by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AfSuggestion(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    /** Titre normalisé (minuscules, sans accents ni ponctuation) pour comparer. */
    private fun normalizeTitle(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    /** Trouve l'anime sur animoflix.to par son titre (autocomplétion du site). */
    private suspend fun animoflixSlug(title: String): String? {
        val json = runCatching {
            app.get(
                "https://animoflix.to/search-autocomplete.php",
                params = mapOf("q" to title),
                interceptor = animoflixCf
            ).text
        }.getOrNull() ?: return null
        val suggestions = AppUtils.tryParseJson<Array<AfSuggestion>>(json) ?: return null
        val wanted = normalizeTitle(title)
        return suggestions.firstOrNull { normalizeTitle(it.title ?: "") == wanted }?.slug
            ?: suggestions.firstOrNull {
                it.title != null && suggestions.size == 1 &&
                    (wanted.contains(normalizeTitle(it.title!!)) || normalizeTitle(it.title!!).contains(wanted))
            }?.slug
    }

    /**
     * Épisode d'un anime sur animoflix.to. Le site numérote PAR SAISON (saison-N),
     * essentiellement le même découpage que TMDB — on essaie aussi les variantes
     * « sans saison / numéro absolu » et « saison 1 / numéro absolu » utilisées
     * par d'autres animes. Chaque page est VÉRIFIÉE (le titre doit contenir
     * « Épisode {n} ») car une page inexistante redirige en douce vers l'épisode 1.
     */
    private suspend fun animoflixLinks(
        title: String,
        season: Int,
        seasonIndex: Int,
        absIndex: Int
    ): List<HostLink> {
        val slug = animoflixSlug(title) ?: return emptyList()
        val out = mutableListOf<HostLink>()

        val variants = mutableListOf<Pair<String, String>>()
        if (!outHasLang(out, "VOSTFR")) variants += "saison-$season/vostfr/episode-$seasonIndex" to "VOSTFR"
        variants += listOf(
            "vostfr/episode-$absIndex" to "VOSTFR",
            "saison-1/vostfr/episode-$absIndex" to "VOSTFR",
            "saison-$season/vf/episode-$seasonIndex" to "VF",
            "vf/episode-$absIndex" to "VF",
            "saison-1/vf/episode-$absIndex" to "VF"
        )

        for ((path, lang) in variants) {
            if (outHasLang(out, lang)) continue
            val url = "https://animoflix.to/anime/$slug/$path"
            val html = runCatching {
                app.get(url, interceptor = animoflixCf).text
            }.getOrNull() ?: continue

            // Vérification : la page doit bien être celle de l'épisode demandé
            val requested = Regex("""episode-(\d+)$""").find(path)?.groupValues?.get(1) ?: continue
            val pageTitle = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1) ?: continue
            if (!Regex("""\b[pé]+isode\s*$requested(\D|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(java.text.Normalizer.normalize(pageTitle, java.text.Normalizer.Form.NFD))
            ) continue

            val select = Regex(
                """<select[^>]*id="epLecteurSelect"[^>]*>(.*?)</select>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)
            val options = select?.let {
                Regex("""<option([^>]*)>([^<]*)</option>""").findAll(it.groupValues[1]).toList()
            } ?: emptyList()
            for (opt in options) {
                val value = Regex("""value="([^"]+)"""").find(opt.groupValues[1])?.groupValues?.get(1) ?: continue
                if (!value.startsWith("http")) continue
                val restricted = """data-restricted="true"""" in opt.groupValues[1]
                val label = opt.groupValues[2].trim().ifBlank { "Lecteur" }
                out += HostLink(
                    value, lang, "AnimoFlix $label",
                    if (restricted) "animoflix-r" else "animoflix"
                )
            }
        }
        return out
    }

    private fun outHasLang(list: List<HostLink>, lang: String) = list.any { it.lang == lang }

    /** Ré-étiquette un lien avec sa langue (« Filemoon · VF ») pour l'utilisateur. */
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
    // Agrégateurs — tous fondés sur l'ID TMDB, testés sur films et épisodes
    // -------------------------------------------------------------------------

    /** `var _src=[…]` des pages wwembed : liste des sources avec langue. */
    private fun parseSrcArray(html: String): List<WwSource> {
        val m = Regex("""var\s+_src\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        return AppUtils.tryParseJson<Array<WwSource>>(m.groupValues[1])?.toList() ?: emptyList()
    }

    /** zeus.php en Server-Sent Events — les meilleures langues (VF/VFQ/VOSTFR/MULTI). */
    private suspend fun zeusLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/zeus.php?sse&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        // Le serveur ferme le flux après l'événement « done » (~10 s) : la réponse
        // complète arrive d'un bloc, on parse chaque « data: {json} ».
        val body = app.get(url, headers = embedHeaders).text
        val out = mutableListOf<HostLink>()
        Regex("""data:\s*(\{.*?\})\s*\n""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            val parsed = AppUtils.tryParseJson<WwSseEvent>(m.groupValues[1]) ?: return@forEach
            parsed.sources?.forEach { s ->
                val u = s.url ?: return@forEach
                if (!u.startsWith("http") || s.premium == true) return@forEach
                val lang = normalizeLang(s.lang)
                val label = hostOf(u)
                if (s.format == "hls" || s.format == "mp4" || s.format == "dash") {
                    if (s.iframe != true) {
                        out += HostLink(u, lang, label, "zeus", direct = true, quality = qualityValue(s.quality))
                        return@forEach
                    }
                }
                out += HostLink(u, lang, label, "zeus", quality = qualityValue(s.quality))
            }
        }
        return out
    }

    /** apis.wavewatch.top/apiwiflix.php — liens hébergeurs AVEC langue (VF/VOSTFR). */
    private suspend fun apiwiflixLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb"
        }
        val html = app.get(url, headers = embedHeaders).text
        val m = Regex("""allSources\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val sources = AppUtils.tryParseJson<Array<ApiWiflixSource>>(m.groupValues[1]) ?: return emptyList()
        return sources.filter { !it.url.isNullOrBlank() }.map {
            HostLink(it.url!!, normalizeLang(it.language), it.name ?: "Lecteur", "apiwiflix")
        }
    }

    /** apis.wavewatch.top/playerix.php — boutons data-url avec langue + HLS directs.
     *  NB : l'API ignore « saison= » — paramètres anglais obligatoires. */
    private suspend fun playerixLinks(
        tmdb: String,
        season: Int?,
        episode: Int?,
        m3u8Only: Boolean = false
    ): List<HostLink> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/playerix.php?type=tv&id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/playerix.php?type=movie&id=$tmdb"
        }
        val html = app.get(url, headers = embedHeaders).text
        val out = mutableListOf<HostLink>()
        val seenHosts = mutableSetOf<String>()
        Regex("""<button([^>]*data-url[^>]*)>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = m.groupValues[1].replace("&amp;", "&")
                val content = m.groupValues[2]
                val dataUrl = Regex("""data-url="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val fmt = Regex("""data-fmt="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: "iframe"
                if (Regex("""data-alive="0"""").containsMatchIn(attrs)) return@forEach
                if (m3u8Only && fmt != "m3u8") return@forEach
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

    /** mouve.php?json=1 — un grand agrégateur (39 flux film / 75 flux épisode). */
    private suspend fun mouveLinks(tmdb: String, season: Int?, episode: Int?): List<HostLink> {
        val url = buildString {
            append("https://apis.wavewatch.top/mouve.php?json=1&type=")
            append(if (season != null) "tv" else "movie")
            append("&id=").append(tmdb)
            append("&s=").append(season ?: 1).append("&e=").append(episode ?: 1)
        }
        val root = AppUtils.parseJson<WwMouveResponse>(app.get(url, headers = embedHeaders).text)
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
                val name = "WaveWatch · $label" + (lang?.let { " · $it" } ?: "")
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
    private fun normalizeLang(lang: String?): String? {
        val l = lang?.trim()?.uppercase() ?: return null
        return when {
            l in setOf("VF", "FR", "FRENCH") -> "VF"
            l in setOf("VFQ", "VFF", "TRUEFRENCH") -> l
            l == "VOSTFR" -> "VOSTFR"
            l == "MULTI" -> "MULTI"
            l == "VO" || l.isEmpty() -> null // « VO » de mouve est un placeholder, pas une info
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

private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }

// -------------------------------------------------------------------------
// Modèles JSON
// -------------------------------------------------------------------------

// /api/tmdb/trending|discover|search|upcoming
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

// /api/tmdb/movie/{id}
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

// /api/tmdb/tv/{id}
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

// /api/tmdb/tv/{id}/season/{n}
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

// /api/tv-channels
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwChannels(
    @JsonProperty("channels") val channels: List<WwChannel> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WwChannel(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("logo_url") val logoUrl: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("is_active") val isActive: Boolean = true
)

// var _src=[…] des pages wwembed + événements SSE de zeus.php
@JsonIgnoreProperties(ignoreUnknown = true)
data class WwSource(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("premium") val premium: Boolean? = null
)

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
