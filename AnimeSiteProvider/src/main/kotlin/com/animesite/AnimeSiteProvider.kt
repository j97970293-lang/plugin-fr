package com.animesite

// ===========================================================================
// AnimeSite (animesite.fr) — Next.js + lecteurs SibNet (MP4 directs)
// ===========================================================================
// Analyse du site (14/09/2026, via r.jina.ai + ingénierie des bundles JS) :
//  · listes  : GET /api/medias?name={trending|added-episode|top-rated}&page=N
//              → [{id, title, type, image:{poster,backdrop}, genres,
//                  overview, releasedAt, lang:"vf | vostfr"}]
//  · l'id interne est converti en id TVDB : tvdbId = id × 336
//    (fonction convertIdForUrl du bundle : "12*t*28").
//  · l'URL publique exige le slug EXACT /{tvdbId}-{slug} — résolu via le
//    sitemap (/sitemap.xml, ~2 670 animés, mis en cache par session), avec
//    repli sur une slugification du titre.
//  · recherche : GET /search/{q} (SSR) → les résultats sont dans le payload
//    RSC (« medias »:[{…}]) — même forme que /api/medias.
//  · fiche : GET /{tvdbId}-{slug} → JSON-LD dans le payload RSC :
//    name, description, image, genre[], startDate, numberOfSeasons,
//    containsSeason:[{seasonNumber:"N", numberOfEpisodes:N}].
//    (les films y sont aussi des « TVSeries » : 1 saison de 1 épisode)
//  · lecteurs : POST /api/stream/token
//    {idAndSlugTitle, seasonNumber, episodeNumber, playerIndex}
//    → {status:"ok", kind:"embed"|"direct"|"external", src:"/v/{token}"}
//    · kind=direct → src = fichier vidéo direct.
//    · kind=embed → GET /v/{token} = page SibNet proxifiée contenant
//      player.src([{src:'/v/{hash}/{videoid}.mp4'}]) → le MP4 réel est sur
//      https://video.sibnet.ru/v/{hash}/{videoid}.mp4.
//      La langue (VOSTFR/VF) figure dans le og:title de la page /v/.
//    · kind=external → publicité, ignoré.
//  · indices players observés : 0-2 = VOSTFR, 3 = VF (variable selon
//    l'épisode) → on sonde 0..5 et on étiquette selon le og:title.
// ===========================================================================

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import java.util.concurrent.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge
 * (le provider seul ne s'enregistre pas : erreur « installe mais invisible »).
 */
@CloudstreamPlugin
class AnimeSitePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        AnimeSiteProvider.appContext = context.applicationContext
        registerMainAPI(AnimeSiteProvider())
        openSettings = { ctx -> AnimeSiteProvider.showSettings(ctx) }
    }
}

class AnimeSiteProvider : MainAPI() {

    companion object {
        const val DEFAULT_URL = "https://animesite.fr"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "animesite_settings"
        private const val PREF_URL = "site_url"

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

        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://…"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse d'AnimeSite")
                .setMessage("Si le site change de domaine, indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "AnimeSite"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "fr"
    override val hasMainPage = true

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    // Site derrière Cloudflare : selon le réseau, la 1re requête peut être
    // défiée. L'intercepteur résout le défi via WebView (automatique pour les
    // challenges JS, un clic pour Turnstile) puis rejoue la requête avec le
    // cookie cf_clearance — les suivantes passent seules.
    private val cfKiller by lazy { CloudflareKiller() }

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
        "Origin" to mainUrl
    )

    // -------------------------------------------------------------------------
    // Accueil
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "trending" to "Tendances",
        "added-episode" to "Nouveaux épisodes",
        "top-rated" to "Les mieux notés"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val url = "$mainUrl/api/medias?name=${request.data}&page=$page"
        val items = runCatching {
            AppUtils.parseJson<List<AsMedia>>(app.get(url, headers = headers(), interceptor = cfKiller).text)
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return newHomePageResponse(request, emptyList(), false)
        val results = items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request, results, hasNext = items.size >= 20)
    }

    // -------------------------------------------------------------------------
    // Recherche — GET /search/{q} : résultats dans le payload RSC
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/search/" + java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val html = runCatching {
            app.get(url, headers = headers("$mainUrl/search"), interceptor = cfKiller).text
        }.getOrNull() ?: return emptyList()
        val flight = rscFlight(html)
        val medias = Regex(""""medias":\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(flight)?.groupValues?.get(1) ?: return emptyList()
        return splitJsonObjects(medias).mapNotNull { raw ->
            runCatching { AppUtils.parseJson<AsMedia>(raw).toSearchResponse() }.getOrNull()
        }
    }

    /** Concatène + normalise le payload RSC (self.__next_f.push) d'une page. */
    private fun rscFlight(html: String): String =
        Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)+)"\]\)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .joinToString("")
            // le flight échappe ses guillemets (\" → ") et newlines (\\n → \n) ;
            // la description (JSON-LD imbriqué) peut l'être deux fois → 2 passes
            .replace("\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\\\n", "\n")
            .replace("\\n", "\n")

    /** Découpe une suite d'objets JSON « {…},{…} » (accolades équilibrées). */
    private fun splitJsonObjects(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> { if (depth == 0) start = i; depth++ }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) { out += s.substring(start, i + 1); start = -1 }
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Sitemap : tvdbId → slug exact (cache par session)
    // ⚠ 486 Ko — ne doit être téléchargé qu'UNE fois : sans verrou, les 20
    // cartes de l'accueil lançaient 20 téléchargements simultanés → gel de
    // l'application puis fermeture (ANR).
    // -------------------------------------------------------------------------
    private var slugIndex: Pair<String, Map<Int, String>>? = null
    private val slugMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun slugFor(tvdbId: Int, title: String): String {
        slugIndex?.takeIf { it.first == mainUrl }?.let { return it.second[tvdbId] ?: slugify(title) }
        val map = slugMutex.withLock {
            // double vérification : un autre appel a pu finir pendant l'attente
            slugIndex?.takeIf { it.first == mainUrl }?.let { return it.second[tvdbId] ?: slugify(title) }
            runCatching {
                val xml = app.get("$mainUrl/sitemap.xml", headers = headers(), interceptor = cfKiller).text
                Regex("""<loc>https?://[^<]+/(\d{5,9})-([a-z0-9-]+)</loc>""")
                    .findAll(xml)
                    .associate { it.groupValues[1].toInt() to "${it.groupValues[1]}-${it.groupValues[2]}" }
            }.getOrNull() ?: emptyMap()
        }
        slugIndex = mainUrl to map
        return map[tvdbId] ?: slugify(title)
    }

    private fun slugify(title: String): String {
        val low = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\p{Mn}]+"), "")
        return Regex("[^a-z0-9\\s-]").replace(low, " ")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val idAndSlug = url.trimEnd('/').substringAfterLast('/')
        val html = runCatching {
            app.get("$mainUrl/$idAndSlug", headers = headers("$mainUrl/"), interceptor = cfKiller).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche inaccessible")

        val flight = rscFlight(html)
        if (""""@type":"TVSeries"""" !in flight) {
            throw ErrorLoadingException("Anime introuvable sur AnimeSite")
        }

        val title = Regex(""""@type":"TVSeries","name":"([^"]{2,120})"""")
            .find(flight)?.groupValues?.get(1)?.trim()
            ?: idAndSlug.substringAfter('-').replace('-', ' ')
        val poster = Regex(""""image":"(https?://[^"]+)"""")
            .find(flight)?.groupValues?.get(1)
        val plot = Regex(""""description":"(.*?)","image"""", RegexOption.DOT_MATCHES_ALL)
            .find(flight)?.groupValues?.get(1)?.trim()
        val year = Regex(""""startDate":"(\d{4})"""")
            .find(flight)?.groupValues?.get(1)?.toIntOrNull()
        val genres = Regex(""""genre":\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(flight)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]{2,30})"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            .orEmpty()

        val seasons = Regex(
            """\{"@type":"TVSeason","seasonNumber":"(\d+)"(?:,"name":"[^"]*")?,"numberOfEpisodes":(\d+)"""
        ).findAll(flight).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

        fun playUrl(s: Int, e: Int) = "$mainUrl/play/$idAndSlug/$s/$e"

        // Film : une seule « saison » d'un épisode (ou aucune saison listée)
        if (seasons.isEmpty() || (seasons.size == 1 && seasons[0].second == 1)) {
            val (s, e) = if (seasons.isEmpty()) 1 to 1 else seasons[0].first to 1
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, playUrl(s, e)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres.take(8)
            }
        }

        val episodes = seasons.flatMap { (season, count) ->
            (1..count).map { ep ->
                newEpisode(playUrl(season, ep)) {
                    this.season = season
                    this.episode = ep
                    // pas de vignette d'épisode côté site → poster de la fiche
                    this.posterUrl = poster
                }
            }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.take(8)
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
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
        // data = https://animesite.fr/play/{idAndSlug}/{saison}/{episode}
        val m = Regex("""/play/([^/]+)/(\d+)/(\d+)""").find(data) ?: return false
        val (idAndSlug, season, episode) = m.destructured

        val links = mutableListOf<Pair<String, String>>()
        val semaphore = Semaphore(3)
        coroutineScope {
            (0..5).map { idx ->
                async {
                    semaphore.acquire()
                    try {
                        resolvePlayer(idAndSlug, season.toInt(), episode.toInt(), idx)
                            ?.let { links += it }
                    } catch (_: Exception) {
                        // lecteur mort : on ignore
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
        links.distinctBy { it.first }.forEach { (u, label) ->
            callback(
                newExtractorLink(name, label, u) {
                    this.type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = "https://video.sibnet.ru/"
                }
            )
        }
        return links.isNotEmpty()
    }

    /**
     * Un lecteur : POST /api/stream/token → /v/{token} → MP4.
     * Retour (url, label) ou null (lecteur mort / externe).
     */
    private suspend fun resolvePlayer(
        idAndSlug: String, season: Int, episode: Int, playerIndex: Int
    ): Pair<String, String>? {
        val referer = "$mainUrl/play/$idAndSlug/$season/$episode"
        val tokenJson = runCatching {
            app.post(
                "$mainUrl/api/stream/token",
                json = mapOf(
                    "idAndSlugTitle" to idAndSlug,
                    "seasonNumber" to season,
                    "episodeNumber" to episode,
                    "playerIndex" to playerIndex
                ),
                headers = headers(referer),
                interceptor = cfKiller
            ).text
        }.getOrNull() ?: return null
        if (""""status":"ok"""" !in tokenJson) return null
        val src = Regex(""""src":"([^"]+)"""").find(tokenJson)?.groupValues?.get(1)
            ?: return null
        when (Regex(""""kind":"([^"]+)"""").find(tokenJson)?.groupValues?.get(1) ?: "embed") {
            "external" -> return null
            "direct" -> if (src.startsWith("http")) {
                return src to "$name · Lecteur ${playerIndex + 1}"
            }
        }

        // embed : /v/{token} = page SibNet proxifiée
        val vUrl = if (src.startsWith("http")) src else "$mainUrl$src"
        val page = runCatching {
            app.get(vUrl, headers = headers(referer), interceptor = cfKiller).text
        }.getOrNull() ?: return null
        val playerSrc = Regex("""player\.src\(\[\{src:\s*"([^"]+)"""")
            .find(page)?.groupValues?.get(1) ?: return null
        if (".mp4" !in playerSrc && ".m3u8" !in playerSrc) return null
        val videoUrl = when {
            playerSrc.startsWith("http") -> playerSrc
            playerSrc.startsWith("/v/") -> "https://video.sibnet.ru$playerSrc"
            else -> "$mainUrl$playerSrc"
        }
        // langue depuis le og:title (« One_Piece_01_VOSTFR » / « …_VF »)
        val ogTitle = Regex("""property="og:title"\s+content="([^"]*)"""")
            .find(page)?.groupValues?.get(1) ?: ""
        val lang = when {
            ogTitle.contains("VOSTFR", true) -> "VOSTFR"
            ogTitle.contains("VF", true) -> "VF"
            else -> "Lecteur ${playerIndex + 1}"
        }
        return videoUrl to "$name · $lang (${playerIndex + 1})"
    }

    // -------------------------------------------------------------------------
    // Modèles
    // -------------------------------------------------------------------------
    private data class AsMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("releasedAt") val releasedAt: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("isForAdult") val isForAdult: Boolean? = null,
        @JsonProperty("image") val image: AsImage? = null
    )

    private data class AsImage(
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null
    )

    private suspend fun AsMedia.toSearchResponse(): SearchResponse? {
        val mediaId = id ?: return null
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isForAdult == true) return null
        val tvdbId = mediaId * 336
        val slug = slugFor(tvdbId, t)
        val url = "$mainUrl/$slug"
        val isMovie = type == "movie" || t.contains("movie", true)
        val response: AnimeSearchResponse = if (isMovie) {
            newAnimeSearchResponse(t, url, TvType.AnimeMovie) { this.posterUrl = image?.poster }
        } else {
            newAnimeSearchResponse(t, url, TvType.Anime) { this.posterUrl = image?.poster }
        }
        response.year = releasedAt?.take(4)?.toIntOrNull()
        return response
    }
}
