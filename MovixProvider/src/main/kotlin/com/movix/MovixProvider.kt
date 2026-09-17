package com.movix

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
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
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ===========================================================================
// Movix — films & séries VF (13+ serveurs réels par contenu)
// ===========================================================================
// Vrai site 2026 : https://movix.men (movix.online → movix.men ; movix.zip
// était un clone périmé aux serveurs morts).
// Architecture (SPA React) :
//   · Catalogue : TMDB public avec la clé du site (dans son bundle JS)
//     → listes /trending, /movie/popular, /movie/top_rated, /movie/upcoming,
//       /tv/popular… + recherche /search/multi (language=fr-FR)
//   · Lecteurs  : https://api.movix.men/api/tmdb/{movie|tv}/{tmdb}[?season=&episode=]
//     → player_links[{decoded_url, language, quality}] = 7 à 13 hébergeurs
//     réels (uqload.cx, voe.sx, filemoon.sx, vidmoly, vidoza, veev.to,
//     lulustream, wishonly, darkibox, emmmmbed, mivalyo, listeamed…).
//     Réponse « Contenu non disponible » = pas encore uploadé.
//   · lecteurvideo.com (lecteur interne du site) = protégé Turnstile → 403,
//     inutilisable : on passe par player_links directement.
//   · Supplément : vidsrc.buzz (agrégateur TMDB, HLS proxysés) quand le site
//     ne propose pas encore le contenu (animes, séries peu connues…).
//   Tout est interrogé EN PARALLÈLE avec des délais bornés par hôte.
// ===========================================================================
@CloudstreamPlugin
class MovixPlugin : Plugin() {
    override fun load(context: Context) {
        MovixProvider.appContext = context.applicationContext
        registerMainAPI(MovixProvider())
        openSettings = { ctx -> MovixProvider.showSettings(ctx) }
    }
}

class MovixProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://movix.men"
        const val DEFAULT_API = "https://api.movix.men"
        // clé TMDB publique du site (lisible dans son bundle JS côté client)
        const val TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"

        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "movix_settings"
        private const val PREF_URL = "site_url"

        fun currentUrl(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_URL

        fun currentApi(): String = runCatching {
            val site = currentUrl().substringAfter("://", DEFAULT_URL).substringBefore('/')
            "https://api.$site"
        }.getOrDefault(DEFAULT_API)

        fun setSiteUrl(context: Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://movix.men"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Movix")
                .setMessage("Adresse du site (l'API est déduite : api.{domaine}).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Movix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private fun tmdbHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json"
    )

    // -------------------------------------------------------------------------
    // DTOs TMDB
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbResult(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val media_type: String? = null,
        val poster_path: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val vote_average: Double? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbPage(val results: List<TmdbResult> = emptyList(), val total_pages: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(val season_number: Int? = null, val episode_count: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisodePage(val episodes: List<TmdbEpisode> = emptyList())

    // -------------------------------------------------------------------------
    // DTOs api.movix.men
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MxPlayer(val decoded_url: String? = null, val language: String? = null, val quality: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MxResponse(
        val player_links: List<MxPlayer> = emptyList(),
        val current_episode: MxEpisode? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class MxEpisode(val player_links: List<MxPlayer> = emptyList())
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsQ(val type: String? = null, val id: String? = null, val s: Int? = null, val e: Int? = null, val t: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsServer(val ref: String? = null, val name: String? = null)

    // ⚠ l'API renvoie désormais un OBJET {"status":..,"servers":[..]} et non
    // plus une liste brute — l'ancien parsing List<VsServer> échouait en silence
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsSources(val status: String? = null, val servers: List<VsServer> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MxImdb(val imdb_id: String? = null)

    // liens de l'API du réseau Frembed (url RELATIVE /api/stream?…)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FbLinks(val links: List<FbLink> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FbLink(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val host: FbHost? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class FbHost(val name: String? = null, val slug: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsPlay(val url: String? = null)

    // -------------------------------------------------------------------------
    // Sections (TMDB FR)
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "trending/movie/week" to "🔥 Films tendance",
        "movie/popular" to "🎬 Films populaires",
        "movie/top_rated" to "⭐ Films les mieux notés",
        "movie/upcoming" to "🗓️ Prochainement",
        "trending/tv/week" to "📺 Séries tendance",
        "tv/popular" to "📼 Séries populaires"
    )

    private fun tmdbCard(r: TmdbResult, fallbackType: String? = null): SearchResponse? {
        val id = r.id ?: return null
        val type = (r.media_type ?: fallbackType)?.takeIf { it == "movie" || it == "tv" } ?: return null
        val title = (r.title ?: r.name)?.takeIf { it.isNotBlank() } ?: return null
        val date = r.release_date ?: r.first_air_date
        val data = "movix:$type:$id"
        val poster = r.poster_path?.let { "$TMDB_IMG$it" }
        val year = date?.take(4)?.toIntOrNull()
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, data) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, data, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val json = runCatching {
            app.get(
                "$TMDB_BASE/${request.data}?api_key=$TMDB_KEY&language=fr-FR&page=$page",
                headers = tmdbHeaders()
            ).text
        }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val root = runCatching { AppUtils.parseJson<TmdbPage>(json) }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        // ⚠ movie/* et tv/* ne renvoient PAS media_type : sans le type de la
        // section, TOUTES les cartes sont filtrées (catalogue quasi vide)
        val fallbackType = when {
            request.data.startsWith("movie") -> "movie"
            request.data.startsWith("tv") -> "tv"
            else -> null
        }
        val items = root.results.mapNotNull { tmdbCard(it, fallbackType) }
        val hasNext = page < (root.total_pages ?: 1).coerceAtMost(500)
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    // -------------------------------------------------------------------------
    // Recherche (TMDB multi)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return emptyList()
        val json = runCatching {
            app.get(
                "$TMDB_BASE/search/multi?api_key=$TMDB_KEY&language=fr-FR&query=$q&include_adult=false",
                headers = tmdbHeaders()
            ).text
        }.getOrNull() ?: return emptyList()
        val root = runCatching { AppUtils.parseJson<TmdbPage>(json) }.getOrNull() ?: return emptyList()
        return root.results.mapNotNull { tmdbCard(it) }
    }

    // -------------------------------------------------------------------------
    // Fiches — data « movix:movie:{tmdb} » / « movix:tv:{tmdb} »
    // -------------------------------------------------------------------------
    private suspend fun tmdbJson(path: String): com.fasterxml.jackson.databind.JsonNode? = runCatching {
        AppUtils.parseJson<com.fasterxml.jackson.databind.JsonNode>(
            app.get("$TMDB_BASE$path?api_key=$TMDB_KEY&language=fr-FR", headers = tmdbHeaders()).text
        )
    }.getOrNull()

    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        // data = movix:{movie|tv}:{tmdb}[:{s}:{e}] (ou une URL se terminant par ce format)
        val marker = url.substringAfter("movix:", "")
        val parts = marker.split(":").filter { it.isNotBlank() }
        val type = parts.getOrNull(0)
        val tmdb = parts.getOrNull(1)?.toIntOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche invalide")
        val isTv = type == "tv"

        val d = tmdbJson("/${if (isTv) "tv" else "movie"}/$tmdb")
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val title = (d.path("title").asText(null) ?: d.path("name").asText(null) ?: "Fiche $tmdb").trim()
        val poster = d.path("poster_path").asText(null)?.let { "$TMDB_IMG$it" }
        val backdrop = d.path("backdrop_path").asText(null)?.let { "$TMDB_IMG$it" }
        val plot = d.path("overview").asText(null)
        val year = (d.path("release_date").asText(null) ?: d.path("first_air_date").asText(null))?.take(4)?.toIntOrNull()
        val score = d.path("vote_average").asDouble(0.0).takeIf { it > 0 }
            ?.let { com.lagradost.cloudstream3.Score.from10(it) }
        val duration = d.path("runtime").asInt(0).takeIf { it > 0 }?.times(60)
        val tags = runCatching {
            d.path("genres").map { it.path("name").asText() }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, "movix:movie:$tmdb") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = score
                this.duration = duration
                this.tags = tags
            }
        }

        // Série : toutes les saisons (en parallèle), épisodes TMDB
        val seasons = runCatching {
            d.path("seasons").mapNotNull { s ->
                s.path("season_number").asInt(-1).takeIf { it >= 1 }?.let { sn ->
                    sn to (s.path("episode_count").asInt(0))
                }
            }
        }.getOrDefault(emptyList())
        if (seasons.isEmpty()) {
            throw com.lagradost.cloudstream3.ErrorLoadingException("Aucune saison trouvée")
        }
        val episodes = mutableListOf<Episode>()
        coroutineScope {
            seasons.forEach { (sn, _) ->
                async(Dispatchers.IO) {
                    val sd = tmdbJson("/tv/$tmdb/season/$sn") ?: return@async
                    val eps = runCatching { AppUtils.parseJson<TmdbEpisodePage>(sd.toString()) }.getOrNull()
                        ?: return@async
                    synchronized(episodes) {
                        eps.episodes.forEach { e ->
                            val n = e.episode_number ?: return@forEach
                            episodes += newEpisode("movix:tv:$tmdb:$sn:$n") {
                                this.name = e.name?.takeIf { it.isNotBlank() } ?: "Épisode $n"
                                this.season = sn
                                this.episode = n
                                this.posterUrl = e.still_path?.let { "https://image.tmdb.org/t/p/w300$it" }
                                this.description = e.overview
                            }
                        }
                    }
                }
            }
        }
        val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        if (sorted.isEmpty()) {
            throw com.lagradost.cloudstream3.ErrorLoadingException("Aucun épisode trouvé")
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
        }
    }

    // -------------------------------------------------------------------------
    // Lecture — api.movix.men player_links (7-13 hébergeurs) + vidsrc.buzz
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val marker = data.substringAfter("movix:", data)
        val parts = marker.split(":").filter { it.isNotBlank() }
        val type = parts.getOrNull(0)
        val tmdb = parts.getOrNull(1) ?: return false
        val isTv = type == "tv"
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        // ---- 1) Serveurs réels de movix.men ----
        val api = currentApi()
        val mxUrl = if (isTv && season != null && episode != null) {
            "$api/api/tmdb/tv/$tmdb?season=$season&episode=$episode"
        } else if (isTv) {
            "$api/api/tmdb/tv/$tmdb?season=1&episode=1"
        } else {
            "$api/api/tmdb/movie/$tmdb"
        }
        val mxJson = runCatching {
            app.get(mxUrl, headers = tmdbHeaders() + mapOf("Referer" to "$mainUrl/")).text
        }.getOrNull()
        val players = mxJson?.let {
            runCatching { AppUtils.parseJson<MxResponse>(it) }.getOrNull()
        }?.let { it.player_links.ifEmpty { it.current_episode?.player_links ?: emptyList() } }
            ?: emptyList()

        // ---- 2) serveurs du site — en parallèle, chacun borné à 15 s ----
        if (players.isNotEmpty()) {
            coroutineScope {
                players.forEach { p ->
                    launch(Dispatchers.IO) {
                        val link = p.decoded_url?.takeIf { it.startsWith("http") } ?: return@launch
                        val hostLabel = p.quality?.trim()?.takeIf { it.isNotBlank() } ?: "Serveur Movix"
                        val lang = when (p.language?.lowercase()) {
                            "french", "fr" -> " · VF"
                            null, "" -> ""
                            else -> " · ${p.language?.uppercase()?.take(8)}"
                        }
                        val label = "$hostLabel$lang"
                        runCatching {
                            withTimeoutOrNull(15_000) {
                                val ok = loadExtractor(link, "$mainUrl/", subtitleCallback) { l ->
                                    foundFlag.set(true)
                                    callback(l)
                                }
                                if (!ok && genericExtract(link, label, subtitleCallback, callback)) foundFlag.set(true)
                            }
                        }
                    }
                }
            }
        }

        // ---- 3) réseau Frembed en cascade (Voe/Dood/Uqload réels par API) ----
        //      Les « embeds publics » (Videasy, VidFast, 2Embed, VidSrc.cc…)
        //      chargent leur flux par XHR côté client : AUCUNE URL extractible
        //      côté extension (testé : 0 m3u8 sur 8 lecteurs). Le réseau Frembed
        //      publie ses serveurs par API : links[] → /api/stream (relative)
        //      → cookies + Sec-Fetch iframe → 302 vers l'hôte réel.
        runCatching {
            withTimeoutOrNull(20_000) {
                if (frembedNetworkLinks(tmdb, isTv, season, episode, subtitleCallback) { l ->
                        foundFlag.set(true)
                        callback(l)
                    }) foundFlag.set(true)
            }
        }

        // ---- 4) agrégateur vidsrc.buzz (TMDB, puis id IMDb en secours) ----
        val aggFound = runCatching { vidsrcBuzzLinks(tmdb, isTv, season, episode, callback) }.getOrDefault(false)
        return foundFlag.get() || aggFound
    }

    // -------------------------------------------------------------------------
    // vidsrc.buzz — agrégateur TMDB, HLS proxysés directs (chaîne validée :
    // /embed/{type}/{tmdb}[/s/e] → var Q → a=sources → a=play en parallèle)
    // -------------------------------------------------------------------------
    // --- headers exacts observés quand un navigateur ouvre /api/stream en iframe
    private val streamNavHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7"
            ),
        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    // --- id IMDb via TMDB (clé publique du réseau) : certains contenus n'ont
    //     de serveurs sur vidsrc.buzz QUE par leur id IMDb (ex : Resident Evil)
    private suspend fun fetchImdbId(tmdbId: String, isTv: Boolean): String? {
        val path = if (isTv) "tv" else "movie"
        return runCatching {
            val j = app.get(
                "$TMDB_BASE/$path/$tmdbId/external_ids?api_key=$TMDB_KEY&language=fr-FR",
                headers = tmdbHeaders()
            ).text
            AppUtils.parseJson<MxImdb>(j).imdb_id?.takeIf { it.startsWith("tt") }
        }.getOrNull()
    }

    // --- agrégateur vidsrc.buzz : HLS proxysés directs ; si l'id TMDB ne donne
    //     rien, on retente avec l'id IMDb (catalogue plus large pour les vieux
    //     films : Resident Evil 2002, Green Lantern…)
    private suspend fun vidsrcBuzzLinks(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        fun buildEmbed(id: String): String = when {
            isTv && season != null && episode != null ->
                "https://vidsrc.buzz/embed/tv/$id/$season/$episode"
            isTv -> "https://vidsrc.buzz/embed/tv/$id/1/1"
            else -> "https://vidsrc.buzz/embed/movie/$id"
        }

        tryBuzzEmbed(buildEmbed(tmdbId), callback, foundFlag)
        if (!foundFlag.get()) {
            val imdb = fetchImdbId(tmdbId, isTv)
            if (imdb != null && imdb != tmdbId) {
                tryBuzzEmbed(buildEmbed(imdb), callback, foundFlag)
            }
        }
        return foundFlag.get()
    }

    private suspend fun tryBuzzEmbed(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
        foundFlag: java.util.concurrent.atomic.AtomicBoolean
    ): Boolean {
        val vsHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Referer" to embedUrl
        )
        val html = runCatching { app.get(embedUrl, headers = vsHeaders).text }.getOrNull() ?: return false
        val qm = Regex("""var Q = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return false
        val q = runCatching { AppUtils.parseJson<VsQ>(qm.groupValues[1]) }.getOrNull() ?: return false
        val id = q.id?.takeIf { it.isNotBlank() } ?: return false
        val token = q.t?.takeIf { it.isNotBlank() } ?: return false
        val qs = "type=${q.type ?: "movie"}&id=${java.net.URLEncoder.encode(id, "UTF-8")}" +
            "&s=${q.s ?: 0}&e=${q.e ?: 0}&t=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val srcJson = runCatching {
            app.get("https://vidsrc.buzz/pl/api.php?a=sources&$qs", headers = vsHeaders).text
        }.getOrNull() ?: return false
        // ⚠ réponse = {"status":"ok","servers":[…]} (objet, plus une liste brute)
        val servers = runCatching { AppUtils.parseJson<VsSources>(srcJson).servers }.getOrNull()
            ?: return false
        coroutineScope {
            servers.take(6).forEach { sv ->
                launch(Dispatchers.IO) {
                    val ref = sv.ref?.takeIf { it.isNotBlank() } ?: return@launch
                    val svName = sv.name?.replace(Regex("""^Server\s+"""), "")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: "Agrégateur"
                    var u: String? = null
                    for (attempt in 1..3) {
                        val play = runCatching {
                            app.get(
                                "https://vidsrc.buzz/pl/api.php?a=play&ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" +
                                    "&t=${java.net.URLEncoder.encode(token, "UTF-8")}",
                                headers = vsHeaders
                            ).text
                        }.getOrNull() ?: break
                        u = runCatching { AppUtils.parseJson<VsPlay>(play).url }.getOrNull()
                        if (!u.isNullOrBlank()) break
                        if (attempt < 3) delay(1300)
                    }
                    val raw = u?.takeIf { it.isNotBlank() } ?: return@launch
                    val link = if (raw.startsWith("/")) "https://vidsrc.buzz$raw" else raw
                    if (!link.startsWith("http")) return@launch
                    foundFlag.set(true)
                    callback(
                        newExtractorLink("VidSrc $svName", "VidSrc $svName", link) {
                            this.referer = "https://vidsrc.buzz/"
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                }
            }
        }
        return foundFlag.get()
    }

    // --- réseau Frembed : serveurs réels du réseau (Voe/Dood/Uqload) par API.
    //     links[].url est RELATIF (/api/stream?…) : il faut la page de contenu
    //     en Referer + les cookies de session, avec les headers d'iframe → 302
    //     vers l'hôte réel, que loadExtractor sait ensuite décoder.
    private suspend fun frembedNetworkLinks(
        tmdb: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = "https://frembed.surf"
        val contentPage = if (isTv) "$origin/series?id=$tmdb" else "$origin/films?id=$tmdb"
        val apiUrl = when {
            isTv && season != null && episode != null ->
                "$origin/api/series?id=$tmdb&sa=$season&epi=$episode&idType=tmdb"
            isTv -> "$origin/api/series?id=$tmdb&sa=1&epi=1&idType=tmdb"
            else -> "$origin/api/films?id=$tmdb&idType=tmdb"
        }
        // cookies de session (la page doit être « primée » comme dans un navigateur)
        runCatching { app.get(contentPage, headers = mapOf("User-Agent" to USER_AGENT)) }
        val apiJson = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Accept" to "application/json"
                )
            ).text
        }.getOrNull() ?: return false
        val links = runCatching { AppUtils.parseJson<FbLinks>(apiJson).links }.getOrNull() ?: return false
        var found = false
        links.forEach { l ->
            val raw = l.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val streamUrl = if (raw.startsWith("http")) raw else "$origin$raw"
            val hostName = l.host?.name?.takeIf { it.isNotBlank() }
                ?: l.label?.takeIf { it.isNotBlank() } ?: "Serveur"
            val lang = l.lang?.uppercase()?.take(5)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            val label = "Movix+ · Frembed $hostName$lang"
            // résout /api/stream → URL réelle de l'hôte (302 Location)
            val target = runCatching {
                val resp = app.get(
                    streamUrl,
                    headers = streamNavHeaders + mapOf("Referer" to contentPage),
                    allowRedirects = false
                )
                when (resp.okhttpResponse.code) {
                    in 300..399 -> resp.okhttpResponse.headers["Location"]?.takeIf { it.startsWith("http") }
                    200 -> Regex("""https?://[a-zA-Z0-9.-]+/[^'	
 <>]+""")
                        .findAll(resp.text)
                        .mapNotNull { it.value }
                        .firstOrNull {
                            "frembed" !in it && "cloudflare" !in it && "static." !in it && "fonts" !in it
                        }
                    else -> null
                }
            }.getOrNull()
            if (target != null) {
                runCatching {
                    withTimeoutOrNull(15_000) {
                        val ok = loadExtractor(target, "$origin/", subtitleCallback) { e ->
                            found = true
                            callback(e)
                        }
                        if (!ok && genericExtract(target, label, subtitleCallback) { e ->
                                found = true
                                callback(e)
                            }) found = true
                    }
                }
            }
        }
        return found
    }

    private val directStreamRegex = Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4|webm)[^"'\s<>]*""")

    private val junkFilterRegex = Regex(
        """(?:google|facebook|twitter|recaptcha|cloudflare|adservice|doubleclick|""" +
            """googletagmanager|analytics|adsystem|ads\.|-ad_|_ad\.|/ad/|sponsor)"""
    )

    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(
            embedUrl,
            referer = "$mainUrl/",
            headers = mapOf("user-agent" to USER_AGENT)
        ).text
        if ("Just a moment" in page) return@runCatching false
        val links = LinkedHashSet<String>()
        Regex("""(?:og:video(?::secure_url)?|contentUrl|embedUrl)"?\s*(?:content|=|:)\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        Regex("""(?:file|src|url|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.forEach { links.add(it) }
        directStreamRegex.findAll(page).map { it.value }.forEach { links.add(it) }
        if (links.isEmpty()) {
            runCatching { JsUnpacker(page).takeIf { it.detect() }?.unpack() }.getOrNull()?.let { unpacked ->
                Regex("""(?:file|src)\s*[=:]\s*["'](https?://[^"']+)["']""")
                    .findAll(unpacked).map { it.groupValues[1] }.forEach { links.add(it) }
                directStreamRegex.findAll(unpacked).map { it.value }.forEach { links.add(it) }
            }
        }
        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { junkFilterRegex.containsMatchIn(it).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                callback(
                    newExtractorLink(label, label, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (".m3u8" in link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }
        links.isNotEmpty()
    }.getOrDefault(false)
}
