package com.frembed

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ===========================================================================
// Frembed — films & séries VF (Voe, Dood, Uqload… liens réels par contenu)
// ===========================================================================
// Site : https://frembed.surf (le domaine change souvent → résolution auto :
//   1. adresse personnalisée (⚙) ; 2. config publique GitHub du site ;
//   3. liste de secours intégrée. Chaque candidat est validé sur /movies).
//   Catalogue : /movies?page=N · /tv-show?page=N (vraies disponibilités,
//   600+ pages) — cartes /{movies|tv-show}/{slug}/{tmdbId}.
//   Fiches    : TMDB (clé publique du réseau, language=fr-FR).
//   Serveurs  : /api/films?id={tmdb}&idType=tmdb ·
//               /api/series?id={tmdb}&sa={s}&epi={e}&idType=tmdb
//               → links[{url, host:{name}, lang}] = Voe/Dood/Uqload réels,
//               extraits par les extracteurs officiels en parallèle.
//   Supplément : vidsrc.buzz (agrégateur TMDB, HLS proxysés).
// ===========================================================================
@CloudstreamPlugin
class FrembedPlugin : Plugin() {
    override fun load(context: Context) {
        FrembedProvider.appContext = context.applicationContext
        registerMainAPI(FrembedProvider())
        openSettings = { ctx -> FrembedProvider.showSettings(ctx) }
    }
}

class FrembedProvider : MainAPI() {
    companion object {
        @Volatile
        var appContext: Context? = null

        private const val PREFS_NAME = "frembed_settings"
        private const val PREF_URL = "site_url"

        // config publique du site (infrastructure officielle de migration)
        private const val CONFIG_URL =
            "https://raw.githubusercontent.com/kingofthrone73-dotcom/frembed-config/refs/heads/main/config.json"
        private val FALLBACK_DOMAINS = listOf(
            "https://frembed.surf",
            "https://frembed.skin"
        )

        // clé TMDB publique du réseau (identique au lecteur du site)
        const val TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"

        fun customUrl(): String? = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        fun setSiteUrl(context: Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: Context) {
            val input = android.widget.EditText(context).apply {
                setText(customUrl() ?: ""); hint = "https://frembed.surf"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Frembed")
                .setMessage("Laissez vide pour la résolution automatique du domaine officiel.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Auto") { _, _ -> setSiteUrl(context, null) }
                .show()
        }
    }

    override var mainUrl = FALLBACK_DOMAINS.first()
    override var name = "Frembed"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    @Volatile
    private var resolvedDomain: String? = null

    // Headers exacts observés quand un navigateur ouvre /api/stream en iframe.
    // /api/stream exige : cookies de session + Referer de la fiche + Sec-Fetch
    // iframe → répond 302 Location = URL réelle de l'hôte (voe/dood/uqload).
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

    private fun headers(referer: String? = null) = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept-Language", "fr-FR,fr;q=0.9")
        if (referer != null) put("Referer", referer)
    }

    /** Résolution du domaine courant : ⚙ → config GitHub → secours. */
    private suspend fun ensureDomain(): String {
        resolvedDomain?.let { return it }
        val candidates = buildList {
            customUrl()?.let { add(it) }
            runCatching {
                val cfg = app.get(CONFIG_URL, headers = headers()).text
                Regex(""""main"\s*:\s*"([^"]+)"""").find(cfg)?.groupValues?.get(1)?.let { add(it) }
                Regex(""""backup"\s*:\s*"([^"]+)"""").find(cfg)?.groupValues?.get(1)?.let { add(it) }
            }
            addAll(FALLBACK_DOMAINS)
        }
        for (c in candidates.distinct()) {
            val origin = c.trimEnd('/')
            if (!origin.startsWith("http")) continue
            val page = runCatching {
                withTimeoutOrNull(8_000) { app.get("$origin/movies", headers = headers("$origin/")).text }
            }.getOrNull() ?: continue
            // page de catalogue valide = ancres /movies/{slug}/{id}
            if (Regex("""/(?:movies|tv-show)/[a-z0-9-]+/\d+""").containsMatchIn(page)) {
                resolvedDomain = origin
                mainUrl = origin
                return origin
            }
        }
        throw ErrorLoadingException("Domaine Frembed introuvable — réessayez plus tard")
    }

    override val mainPage = mainPageOf(
        "movies" to "🎬 Derniers films",
        "tv-show" to "📺 Dernières séries"
    )

    // -------------------------------------------------------------------------
    // Cartes : <a href="/{movies|tv-show}/{slug}/{tmdbId}"> + img alt= (titre)
    // -------------------------------------------------------------------------
    private fun parseCards(html: String, origin: String, route: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        // ⚠ les attributs de <img> sont en ordre ALPHABÉTIQUE (React) :
        // alt AVANT src → on capture tout le tag puis on lit chaque attribut.
        Regex(
            """href="(?:https?://[^"]*?)?/($route)/([a-z0-9-]+)/(\d+)""" +
                """["'][\s\S]{0,900}?<img([^>]+)>"""
        ).findAll(html).forEach { m ->
            val (r, slug, tmdb, imgTag) = m.destructured
            val id = tmdb.toIntOrNull() ?: return@forEach
            val title = Regex("""alt="([^"]*)"""").find(imgTag)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
            val img = Regex("""\ssrc="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                ?: Regex("""data-src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                ?: Regex("""srcset="([^" ]+)"""").find(imgTag)?.groupValues?.get(1)
            val isMovie = r == "movies"
            val data = "frembed:${if (isMovie) "movie" else "tv"}:$id"
            out[id.toString()] = if (isMovie) {
                newMovieSearchResponse(title, data, TvType.Movie) { this.posterUrl = img }
            } else {
                newTvSeriesSearchResponse(title, data) { this.posterUrl = img }
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val origin = ensureDomain()
        val url = "$origin/${request.data}" + if (page > 1) "?page=$page" else ""
        val html = runCatching {
            app.get(url, headers = headers("$origin/")).text
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html, origin, request.data)
        val totalPages = Regex("""page\s+\d+\s*/\s*(\d+)""").find(
            Regex("""<[^>]+>""").replace(html, " ")
        )?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return newHomePageResponse(request, items, hasNext = page < totalPages && items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Recherche : TMDB multi puis sonde de disponibilité sur l'API Frembed
    // (le site n'a pas de recherche — on ne montre que ce qu'il peut lire)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val origin = ensureDomain()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return emptyList()
        val json = runCatching {
            app.get(
                "$TMDB_BASE/search/multi?api_key=$TMDB_KEY&language=fr-FR&query=$q&include_adult=false",
                headers = headers()
            ).text
        }.getOrNull() ?: return emptyList()
        val root = runCatching { AppUtils.parseJson<TmdbPage>(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        root.results.filter { it.media_type == "movie" || it.media_type == "tv" }.take(10).forEach { r ->
            val id = r.id ?: return@forEach
            val title = (r.title ?: r.name)?.takeIf { it.isNotBlank() } ?: return@forEach
            val isMovie = r.media_type == "movie"
            val available = runCatching {
                val probe = if (isMovie) {
                    "$origin/api/films?id=$id&idType=tmdb"
                } else {
                    "$origin/api/series?id=$id&sa=1&epi=1&idType=tmdb"
                }
                val body = app.get(probe, headers = headers("$origin/") + mapOf("Accept" to "application/json")).text
                Regex(""""url"\s*:""").containsMatchIn(body)
            }.getOrDefault(false)
            if (available) {
                val data = "frembed:${if (isMovie) "movie" else "tv"}:$id"
                out += if (isMovie) {
                    newMovieSearchResponse(title, data, TvType.Movie) {
                        this.posterUrl = r.poster_path?.let { "$TMDB_IMG$it" }
                        this.year = (r.release_date ?: r.first_air_date)?.take(4)?.toIntOrNull()
                    }
                } else {
                    newTvSeriesSearchResponse(title, data) {
                        this.posterUrl = r.poster_path?.let { "$TMDB_IMG$it" }
                        this.year = (r.release_date ?: r.first_air_date)?.take(4)?.toIntOrNull()
                    }
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbResult(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val media_type: String? = null,
        val poster_path: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbPage(val results: List<TmdbResult> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(val episode_number: Int? = null, val name: String? = null, val still_path: String? = null, val overview: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisodePage(val episodes: List<TmdbEpisode> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FbLink(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val host: Host? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Host(val name: String? = null, val slug: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FbResponse(val links: List<FbLink> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsQ(val type: String? = null, val id: String? = null, val s: Int? = null, val e: Int? = null, val t: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsServer(val ref: String? = null, val name: String? = null)

    // ⚠ l'API renvoie un OBJET {"status":..,"servers":[..]}, pas une liste brute
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsSources(val status: String? = null, val servers: List<VsServer> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FbImdb(val imdb_id: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsPlay(val url: String? = null)

    // -------------------------------------------------------------------------
    // Fiche (TMDB FR) — data « frembed:{movie|tv}:{tmdb}[:{s}:{e}] »
    // -------------------------------------------------------------------------
    private suspend fun tmdbJson(path: String): com.fasterxml.jackson.databind.JsonNode? = runCatching {
        AppUtils.parseJson<com.fasterxml.jackson.databind.JsonNode>(
            app.get("$TMDB_BASE$path?api_key=$TMDB_KEY&language=fr-FR", headers = headers()).text
        )
    }.getOrNull()

    override suspend fun load(url: String): LoadResponse {
        val origin = ensureDomain()
        val parts = url.substringAfter("frembed:", "").split(":").filter { it.isNotBlank() }
        val type = parts.getOrNull(0)
        val tmdb = parts.getOrNull(1)?.toIntOrNull() ?: throw ErrorLoadingException("Fiche invalide")
        val isTv = type == "tv"

        val d = tmdbJson("/${if (isTv) "tv" else "movie"}/$tmdb")
            ?: throw ErrorLoadingException("Fiche introuvable")
        val title = (d.path("title").asText(null) ?: d.path("name").asText(null) ?: "Fiche $tmdb").trim()
        val poster = d.path("poster_path").asText(null)?.let { "$TMDB_IMG$it" }
        val plot = d.path("overview").asText(null)
        val year = (d.path("release_date").asText(null) ?: d.path("first_air_date").asText(null))?.take(4)?.toIntOrNull()
        val score = d.path("vote_average").asDouble(0.0).takeIf { it > 0 }
            ?.let { com.lagradost.cloudstream3.Score.from10(it) }
        val duration = d.path("runtime").asInt(0).takeIf { it > 0 }?.times(60)
        val tags = runCatching { d.path("genres").map { it.path("name").asText() }.filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, "frembed:movie:$tmdb") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.duration = duration
                this.tags = tags
            }
        }

        val seasons = runCatching {
            d.path("seasons").mapNotNull { s ->
                s.path("season_number").asInt(-1).takeIf { it >= 1 }
            }
        }.getOrDefault(emptyList())
        if (seasons.isEmpty()) throw ErrorLoadingException("Aucune saison trouvée")
        val episodes = mutableListOf<Episode>()
        coroutineScope {
            seasons.forEach { sn ->
                launch(Dispatchers.IO) {
                    val sd = tmdbJson("/tv/$tmdb/season/$sn") ?: return@launch
                    val eps = runCatching { AppUtils.parseJson<TmdbEpisodePage>(sd.toString()) }.getOrNull()
                        ?: return@launch
                    synchronized(episodes) {
                        eps.episodes.forEach { e ->
                            val n = e.episode_number ?: return@forEach
                            episodes += newEpisode("frembed:tv:$tmdb:$sn:$n") {
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
        if (sorted.isEmpty()) throw ErrorLoadingException("Aucun épisode trouvé")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
        }
    }

    // -------------------------------------------------------------------------
    // Lecture — links[] de l'API Frembed + supplément vidsrc.buzz
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = ensureDomain()
        val parts = data.substringAfter("frembed:", data).split(":").filter { it.isNotBlank() }
        val type = parts.getOrNull(0)
        val tmdb = parts.getOrNull(1) ?: return false
        val isTv = type == "tv"
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val foundFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        // ---- 1) serveurs Frembed (Voe, Dood, Uqload…) ----
        val apiUrl = if (isTv && season != null && episode != null) {
            "$origin/api/series?id=$tmdb&sa=$season&epi=$episode&idType=tmdb"
        } else if (isTv) {
            "$origin/api/series?id=$tmdb&sa=1&epi=1&idType=tmdb"
        } else {
            "$origin/api/films?id=$tmdb&idType=tmdb"
        }
        val apiJson = runCatching {
            app.get(apiUrl, headers = headers("$origin/") + mapOf("Accept" to "application/json")).text
        }.getOrNull()
        val links = apiJson?.let { runCatching { AppUtils.parseJson<FbResponse>(it).links }.getOrNull() }
            ?: emptyList()

        // ⚠ links[].url est RELATIF (/api/stream?type=…&server=id:…) : il faut
        // le résoudre en 302 vers l'hôte réel (cookies + Referer fiche + iframe)
        val contentPage = if (isTv) "$origin/series?id=$tmdb" else "$origin/films?id=$tmdb"
        // amorce la session (cookies) sur la même page que le navigateur
        runCatching { app.get(contentPage, headers = headers()) }
        coroutineScope {
            links.forEach { l ->
                launch(Dispatchers.IO) {
                    val raw = l.url?.takeIf { it.isNotBlank() } ?: return@launch
                    val streamUrl = if (raw.startsWith("http")) raw else "$origin$raw"
                    val hostName = l.host?.name?.takeIf { it.isNotBlank() }
                        ?: l.label?.takeIf { it.isNotBlank() } ?: "Serveur"
                    val lang = l.lang?.uppercase()?.take(5)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                    val label = "Frembed · $hostName$lang"
                    runCatching {
                        withTimeoutOrNull(15_000) {
                            // /api/stream → 302 Location = page voe/dood/uqload
                            val target = resolveStream(streamUrl, contentPage)
                            if (target != null) {
                                val ok = loadExtractor(target, "$origin/", subtitleCallback) { e ->
                                    foundFlag.set(true)
                                    callback(e)
                                }
                                if (!ok && genericExtract(target, label, callback)) foundFlag.set(true)
                            }
                        }
                    }
                }
            }
        }

        // ---- 2) supplément vidsrc.buzz (HLS proxysés) ----
        val aggFound = runCatching { vidsrcBuzzLinks(tmdb, isTv, season, episode, callback) }.getOrDefault(false)
        return foundFlag.get() || aggFound
    }

    // résout /api/stream?… → URL réelle de l'hébergeur :
    // 302 Location, sinon (page 200) première URL externe du corps
    private suspend fun resolveStream(streamUrl: String, contentPage: String): String? = runCatching {
        val resp = app.get(
            streamUrl,
            headers = streamNavHeaders + mapOf("Referer" to contentPage),
            allowRedirects = false
        )
        when (resp.okhttpResponse.code) {
            in 300..399 -> resp.okhttpResponse.headers["Location"]?.takeIf { it.startsWith("http") }
            200 -> Regex("""https?://[a-zA-Z0-9.-]+/[^"\t\n <>]+""")
                .findAll(resp.text)
                .mapNotNull { it.value }
                .firstOrNull {
                    "frembed" !in it && "cloudflare" !in it && "static." !in it && "fonts" !in it
                }
            else -> null
        }
    }.getOrNull()

    // -------------------------------------------------------------------------
    // vidsrc.buzz — HLS proxysés (embed → var Q → a=sources → a=play parallèle)
    // -------------------------------------------------------------------------
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
            // certains contenus n'ont de serveurs QUE par leur id IMDb
            val imdb = fetchImdbId(tmdbId, isTv)
            if (imdb != null && imdb != tmdbId) tryBuzzEmbed(buildEmbed(imdb), callback, foundFlag)
        }
        return foundFlag.get()
    }

    private suspend fun fetchImdbId(tmdbId: String, isTv: Boolean): String? {
        val path = if (isTv) "tv" else "movie"
        return runCatching {
            val j = app.get(
                "$TMDB_BASE/$path/$tmdbId/external_ids?api_key=$TMDB_KEY&language=fr-FR",
                headers = headers()
            ).text
            AppUtils.parseJson<FbImdb>(j).imdb_id?.takeIf { it.startsWith("tt") }
        }.getOrNull()
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
        val servers = runCatching { AppUtils.parseJson<VsSources>(srcJson).servers }.getOrNull() ?: return false
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
                        if (attempt < 3) kotlinx.coroutines.delay(1300)
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

    // -------------------------------------------------------------------------
    // Extraction générique (regex sources + p.a.c.k.e.r + mp4/m3u8 directs)
    // -------------------------------------------------------------------------
    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(embedUrl, referer = "$mainUrl/", headers = mapOf("user-agent" to USER_AGENT)).text
        if ("Just a moment" in page) return@runCatching false
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
