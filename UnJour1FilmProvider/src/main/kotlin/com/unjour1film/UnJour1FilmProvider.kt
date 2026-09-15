package com.unjour1film

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// ===========================================================================
// 1JOUR1FILM (1jour1film0826b.website) — WordPress + admin-ajax
// ===========================================================================
// Analyse du site (15/09/2026) :
//  · Cartes : <a class="j1f-card" href="/films/{slug}/"> avec img poster.
//  · Catalogue : POST admin-ajax action=j1f_catalogue {type, page, search…}
//    → {data:{pages, html}} (8 423 films + séries).
//  · Fallback REST : GET /wp-json/wp/v2/{movies|tvshows}?per_page=60&page=N
//    (X-WP-Total: 8423) — sans poster mais toujours vivant si l'ajax change.
//  · Fiche film : scripts inline base64 → J1F_POST_ID + ID TMDB dans
//    « vp4-{tmdbId}- » (lecteur vp4 du site).
//  · Page saison : /saisons/{slug}/ → J1F_SEASON_ID + j1fEpsData[] (id, num,
//    label, backdrop TMDB) + script vp4tv « var tmdb = N; var season = M; »
//    (4 lecteurs publics concaténés côté client).
//  · Sources : POST j1f_get_source / j1f_get_ep_source (nonce via
//    j1f_get_nonce) → URL Vidara/Lulustream/direct.
//  · L'ID TMDB permet d'ajouter des lecteurs publics (Videasy, VidFast,
//    VidSrc…) et l'agrégateur apiwiflix en serveurs supplémentaires.
// ===========================================================================

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge.
 */
@CloudstreamPlugin
class UnJour1FilmPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        UnJour1FilmProvider.appContext = context.applicationContext
        registerMainAPI(UnJour1FilmProvider())
        // bouton ⚙ dans CloudStream → Paramètres → Extensions → 1JOUR1FILM
        openSettings = { ctx -> UnJour1FilmProvider.showSettings(ctx) }
    }
}

class UnJour1FilmProvider : MainAPI() {

    companion object {
        const val DEFAULT_URL = "https://1jour1film0826b.website"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "unjour1film_settings"
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
                .setTitle("Adresse de 1JOUR1FILM")
                .setMessage("Le site change de domaine régulièrement (1jour1film…). Indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "1JOUR1FILM"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { com.fasterxml.jackson.databind.ObjectMapper() }

    // Site derrière Cloudflare : selon le réseau, la 1re requête peut être
    // défiée. L'intercepteur résout le défi via WebView (automatique pour les
    // challenges JS, un clic pour Turnstile) puis rejoue la requête avec le
    // cookie cf_clearance — les requêtes suivantes passent seules.
    private val cfKiller by lazy { CloudflareKiller() }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    // En-têtes « vrais navigateur » : le WAF du site bloque les requêtes
    // au user-agent non-navigateur (okhttp/curl → 403) et note les requêtes
    // minimalistes — on imite donc un navigateur complet.
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    private fun ajaxHeaders(referer: String) = baseHeaders + mapOf(
        // pas de Content-Type explicite : la bibliothèque le pose correctement
        // pour les données de formulaire (double en-tête = requête rejetée)
        "Origin" to mainUrl,
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // -------------------------------------------------------------------------
    // Accueil
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "sorties" to "Dernières sorties",
        "movies" to "Films",
        "tvshows" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        if (request.data == "sorties") {
            if (page > 1) return newHomePageResponse(request, emptyList(), false)
            val html = runCatching {
                app.get("$mainUrl/dernieres-sorties/", headers = baseHeaders, interceptor = cfKiller).text
            }.getOrNull()
            val cards = html?.let { parseCards(it) } ?: emptyList()
            if (cards.isNotEmpty()) return newHomePageResponse(request, cards, hasNext = false)
            // fallback REST : les films/séries les plus récents
            val rest = restCards("movies", page).first + restCards("tvshows", page).first
            if (rest.isNotEmpty()) return newHomePageResponse(request, rest, hasNext = false)
            throw ErrorLoadingException(
                "Site 1JOUR1FILM inaccessible. Vérifiez votre connexion, puis changez l'adresse dans les réglages de l'extension (⚙) si le domaine a changé."
            )
        }
        // ⚠ request.data = la clé ("movies"/"tvshows"), request.name = le libellé
        val type = request.data // movies | tvshows
        val root = runCatching { catalogue(type, page, "") }.getOrNull()
        val items = root?.second?.let { parseCards(it) } ?: emptyList()
        // ---- Fallback REST si l'ajax est indisponible/vide ----
        if (items.isEmpty()) {
            val rest = restCards(type, page)
            if (rest.first.isNotEmpty()) return newHomePageResponse(request, rest.first, rest.second)
            throw ErrorLoadingException(
                "Catalogue inaccessible. Vérifiez votre connexion, puis changez l'adresse dans les réglages de l'extension (⚙) si le domaine a changé."
            )
        }
        val hasNext = root!!.first < 10_000 && items.size >= 20
        return newHomePageResponse(request, items, hasNext)
    }

    /** POST j1f_catalogue → (pages, html des cartes). */
    private suspend fun catalogue(type: String, page: Int, search: String): Pair<Int, String> {
        val text = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "j1f_catalogue",
                "type" to type,
                "genre" to "", "annee" to "", "qualite" to "", "reseau" to "", "decennie" to "",
                "tri" to "date",
                "search" to search,
                "page" to page.toString()
            ),
            headers = ajaxHeaders("$mainUrl/catalogue-films/"),
            interceptor = cfKiller
        ).text
        val root = runCatching { mapper.readTree(text) }.getOrNull() ?: return 1 to ""
        if (!root.path("success").asBoolean(false)) return 1 to ""
        val d = root.path("data")
        return d.path("pages").asInt(1) to d.path("html").asText("")
    }

    /**
     * Fallback REST : GET /wp-json/wp/v2/{movies|tvshows} — sans posters mais
     * insensible aux changements du protocole admin-ajax.
     */
    private suspend fun restCards(type: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val root = runCatching {
            mapper.readTree(app.get("$mainUrl/wp-json/wp/v2/$type?per_page=60&page=$page", headers = baseHeaders, interceptor = cfKiller).text)
        }.getOrNull() ?: return emptyList<SearchResponse>() to false
        if (!root.isArray) return emptyList<SearchResponse>() to false
        val out = mutableListOf<SearchResponse>()
        root.forEach { p ->
            val link = p.path("link").asText(null)?.takeIf { it.startsWith("http") } ?: return@forEach
            val title = p.path("title").path("rendered").asText(null)
                ?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotBlank() }
                ?: link.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            val year = Regex("""\b(19|20)\d{2}\b""").find(p.path("dtyear").asText(""))?.value
            val name = if (year != null) "$title ($year)" else title
            out += if (type == "tvshows") {
                newTvSeriesSearchResponse(name, link, TvType.TvSeries) {}
            } else {
                newMovieSearchResponse(name, link, TvType.Movie) {}
            }
        }
        return out to (out.size >= 60)
    }

    /** Cartes j1f-card : <a href="…" class="j1f-card">…<img (data-|)src=poster alt=titre>… */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a href="((?:https?://[^"]*)?/(?:films|tvshows)/([a-z0-9-]+)/)"[^>]*class="j1f-card"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val href = if (m.groupValues[1].startsWith("http")) m.groupValues[1] else mainUrl + m.groupValues[1]
            val slug = m.groupValues[2]
            val seg = m.groupValues[3]
            val poster = Regex("""<img[^>]+(?:data-src|src)="(https?://[^"]+)"""").find(seg)?.groupValues?.get(1)
            val title = Regex("""<div class="card-title">([^<]+)</div>""").find(seg)?.groupValues?.get(1)?.trim()
                ?: Regex("""alt="([^"]*)"""").find(seg)?.groupValues?.get(1)?.trim()
                ?: slug.replace('-', ' ')
            if (out.none { it.url == href }) {
                val isSeries = href.contains("/tvshows/")
                out += if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val movies = runCatching { catalogue("movies", 1, query) }.getOrNull()?.second
        val series = runCatching { catalogue("tvshows", 1, query) }.getOrNull()?.second
        val out = mutableListOf<SearchResponse>()
        movies?.let { out += parseCards(it) }
        series?.let { out += parseCards(it) }
        if (out.isEmpty()) {
            // fallback REST sur le terme de recherche (slug Wordpress)
            val slug = query.lowercase().trim().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
            if (slug.isNotBlank()) {
                out += restCards("movies", 1).first.filter {
                    it.name.contains(query, ignoreCase = true)
                }
            }
        }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = runCatching {
            app.get(url, headers = baseHeaders, interceptor = cfKiller).text
        }.getOrNull() ?: throw ErrorLoadingException(
            "Fiche inaccessible — si le problème persiste, changez l'adresse du site dans les réglages de l'extension (⚙)."
        )

        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore('|')?.trim() ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
        val year = Regex("""\((19|20)\d{2}\)""").find(title)?.value?.trim('(', ')')?.toIntOrNull()
        val scripts = decodeInlineScripts(html)

        // ---- Série : saisons → épisodes ----
        if (url.contains("/tvshows/")) {
            val seasonLinks = Regex(
                """href="((?:https?://[^"]*)?/saisons/([a-z0-9-]+)/)"[^>]*class="season-card""""
            ).findAll(html).map { if (it.groupValues[1].startsWith("http")) it.groupValues[1] else mainUrl + it.groupValues[1] }
                .distinct().toList()
            if (seasonLinks.isEmpty()) throw ErrorLoadingException("Aucune saison trouvée")

            val episodes = mutableListOf<Episode>()
            seasonLinks.forEachIndexed { seasonIdx, seasonUrl ->
                val seasonHtml = runCatching { app.get(seasonUrl, headers = baseHeaders, interceptor = cfKiller).text }.getOrNull() ?: return@forEachIndexed
                val sScripts = decodeInlineScripts(seasonHtml)
                val seasonId = sScripts.firstNotNullOfOrNull { s ->
                    Regex("""J1F_SEASON_ID\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)
                } ?: return@forEachIndexed
                val seasonNum = Regex("""[Ss]aison\s*(\d+)""").find(
                    Regex("""<title>([^<]+)</title>""").find(seasonHtml)?.groupValues?.get(1) ?: ""
                )?.groupValues?.get(1)?.toIntOrNull() ?: (seasonIdx + 1)
                // ID TMDB + numéro de saison réels (lecteur vp4tv du site)
                val tmdbId = sScripts.firstNotNullOfOrNull { s ->
                    Regex("""var\s+tmdb\s*=\s*(\d+)\s*;""").find(s)?.groupValues?.get(1)?.toIntOrNull()
                } ?: 0
                val realSeason = sScripts.firstNotNullOfOrNull { s ->
                    Regex("""var\s+season\s*=\s*(\d+)\s*;""").find(s)?.groupValues?.get(1)?.toIntOrNull()
                } ?: seasonNum
                val epsData = sScripts.firstNotNullOfOrNull { s ->
                    Regex("""j1fEpsData\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(s)
                        ?.groupValues?.get(1)?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                } ?: return@forEachIndexed
                epsData.forEach { ep ->
                    val epId = ep.path("id").asInt(0)
                    val num = ep.path("num").asText("").toIntOrNull() ?: return@forEach
                    if (epId > 0) {
                        episodes += newEpisode("j1fe:$seasonId:$epId:$tmdbId:$realSeason:$num") {
                            this.season = seasonNum
                            this.episode = num
                            this.name = ep.path("label").asText(null)?.takeIf { it.isNotBlank() }
                            // backdrop TMDB de l'épisode, sinon poster de la fiche
                            this.posterUrl = ep.path("backdrop").asText(null)?.takeIf { it.isNotBlank() } ?: poster
                        }
                    }
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode disponible")
            val sorted = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        // ---- Film ----
        val postId = scripts.firstNotNullOfOrNull { s ->
            Regex("""J1F_POST_ID\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Identifiant du film introuvable")
        // ID TMDB du lecteur vp4 (« vp4-{tmdbId}- » dans le script)
        val tmdbId = scripts.firstNotNullOfOrNull { s ->
            Regex("""vp4-(\d+)-""").find(s)?.groupValues?.get(1)
        }?.toIntOrNull() ?: 0
        return newMovieLoadResponse(title, url, TvType.Movie, "j1fm:$postId:$tmdbId") {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /** Décode tous les scripts inline embarqués en base64 (data:text/javascript;base64,…). */
    private fun decodeInlineScripts(html: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""base64,([A-Za-z0-9+/=]{50,})""").findAll(html).forEach { m ->
            runCatching {
                android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
            }.getOrNull()?.let { out += it }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------
    // data = j1fm:{postId}[:{tmdbId}]
    //      | j1fe:{seasonId}:{epId}:{tmdbId}:{seasonNum}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val nonce = getNonce() ?: return false
        var tmdbId = 0
        var seasonNum: Int? = null
        var epNum = 1
        val urls: List<String> = when {
            data.startsWith("j1fm:") -> {
                val parts = data.removePrefix("j1fm:").split(":")
                val postId = parts[0]
                tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: 0
                (0 until 6).mapNotNull { idx ->
                    fetchSource(
                        mapOf(
                            "action" to "j1f_get_source", "nonce" to nonce,
                            "post_id" to postId, "idx" to idx.toString()
                        )
                    )
                }
            }
            data.startsWith("j1fe:") -> {
                val parts = data.removePrefix("j1fe:").split(":")
                if (parts.size < 2) return false
                tmdbId = parts.getOrNull(2)?.toIntOrNull() ?: 0
                seasonNum = parts.getOrNull(3)?.toIntOrNull()
                epNum = parts.getOrNull(4)?.toIntOrNull() ?: 1
                (0 until 4).mapNotNull { idx ->
                    fetchSource(
                        mapOf(
                            "action" to "j1f_get_ep_source", "nonce" to nonce,
                            "season_id" to parts[0], "ep_id" to parts[1], "idx" to idx.toString()
                        )
                    )
                }
            }
            else -> return false
        }
        var found = false
        urls.forEach { u -> if (extract(u, callback)) found = true }

        // ---- Serveurs supplémentaires (lecteurs publics TMDB + apiwiflix) ----
        if (tmdbId > 0) {
            val produced = java.util.concurrent.atomic.AtomicInteger(0)
            runCatching {
                val tmdb = tmdbId.toString()
                val embeds = buildList {
                    add(
                        if (seasonNum != null)
                            "https://player.videasy.net/tv/$tmdb/$seasonNum/$epNum?overlay=true&color=8B5CF6&nextEpisode=true&episodeSelector=true" to "Videasy"
                        else "https://player.videasy.net/movie/$tmdb?overlay=true&color=8B5CF6" to "Videasy"
                    )
                    add(
                        if (seasonNum != null)
                            "https://frembed.skin/embed/serie/$tmdb?sa=$seasonNum&epi=$epNum" to "Frembed"
                        else "https://frembed.skin/embed/movie/$tmdb" to "Frembed"
                    )
                    add(
                        if (seasonNum != null)
                            "https://peachify.top/embed/tv/$tmdb/$seasonNum/$epNum?dub=French&sub=French&autoNext=30" to "Peachify"
                        else "https://peachify.top/embed/movie/$tmdb?dub=French&sub=French" to "Peachify"
                    )
                    addAll(
                        if (seasonNum != null) listOf(
                            "https://vidfast.pro/tv/$tmdb/$seasonNum/$epNum?autoPlay=true&sub=fr" to "VidFast",
                            "https://vidsrc.cc/v2/embed/tv/$tmdb/$seasonNum/$epNum" to "VidSrc.cc",
                            "https://www.vidsrc.wtf/api/2/tv/?id=$tmdb&s=$seasonNum&e=$epNum" to "VidSrc.wtf",
                            "https://www.2embed.cc/embedtv/$tmdb&s=$seasonNum&e=$epNum" to "2Embed",
                            "https://111movies.com/tv/$tmdb/$seasonNum/$epNum" to "111Movies",
                            "https://www.braflix.win/watch/$tmdb?s=$seasonNum&e=$epNum" to "Braflix",
                            "https://www.vidking.net/embed/tv/$tmdb/$seasonNum/$epNum?autoPlay=true" to "VidKing",
                            "https://vidnest.fun/tv/$tmdb/$seasonNum/$epNum" to "VidNest"
                        ) else listOf(
                            "https://vidfast.pro/movie/$tmdb?autoPlay=true&sub=fr" to "VidFast",
                            "https://vidsrc.cc/v2/embed/movie/$tmdb" to "VidSrc.cc",
                            "https://www.vidsrc.wtf/api/3/movie/?id=$tmdb" to "VidSrc.wtf",
                            "https://www.2embed.cc/embed/$tmdb" to "2Embed",
                            "https://111movies.com/movie/$tmdb" to "111Movies",
                            "https://www.braflix.win/watch/$tmdb" to "Braflix",
                            "https://www.vidking.net/embed/movie/$tmdb?autoPlay=true" to "VidKing",
                            "https://vidnest.fun/movie/$tmdb" to "VidNest"
                        )
                    )
                }
                // agrégateur apiwiflix : liens hébergeurs avec langue
                val agg: List<com.fasterxml.jackson.databind.JsonNode> = runCatching {
                    val url = if (seasonNum != null) "https://apis.wavewatch.top/apiwiflix.php?id=$tmdbId&season=$seasonNum&episode=$epNum" else "https://apis.wavewatch.top/apiwiflix.php?id=$tmdbId"
                    val html = app.get(url, headers = baseHeaders, interceptor = cfKiller).text
                    val m = Regex("""allSources\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
                        ?: return@runCatching emptyList()
                    val node = runCatching { mapper.readTree(m.groupValues[1]) }.getOrNull()
                    if (node != null && node.isArray) node.map { it } else emptyList()
                }.getOrDefault(emptyList())
                val toExtract = embeds.toMutableList<Pair<String, String>>()
                agg.forEach { s ->
                    val u = s.path("url").asText(null)?.takeIf { it.startsWith("http") } ?: return@forEach
                    val lang = s.path("language").asText(null)?.trim()?.uppercase()
                        ?.replace("FRENCH", "VF")?.takeIf { it.isNotBlank() }
                    val nm = s.path("name").asText("Lecteur")
                    toExtract += u to (nm + (lang?.let { " · $it" } ?: ""))
                }
                val semaphore = Semaphore(6)
                coroutineScope {
                    toExtract.map { (u, label) ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                runCatching {
                                    loadExtractor(u, mainUrl, { sub -> subtitleCallback(sub) }) { link ->
                                        val nm = "1J1F+ · $label"
                                        produced.incrementAndGet()
                                        callback(
                                            ExtractorLink(
                                                nm, nm,
                                                link.url, link.referer, link.quality,
                                                link.headers, link.extractorData, link.type
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    // vidsrc.buzz : multi-serveurs TMDB (films, séries et animes) —
                    // HLS proxysés directs, extraits maison (chaîne embed → jeton →
                    // api.php?a=sources → a=play).
                    runCatching { vidsrcBuzzLinks(tmdb, seasonNum, epNum) { link ->
                        produced.incrementAndGet()
                        callback(link)
                    } }
                }
            }
            if (produced.get() > 0) found = true
        }
        return found
    }

    /**
     * vidsrc.buzz — agrégateur TMDB multi-serveurs. Émet des liens HLS directs.
     * /embed/{type}/{tmdb}[/s/e] → `var Q = {…}` (jeton) → /pl/api.php?a=sources →
     * serveurs → /pl/api.php?a=play → {url:"/_stream?id=…"} (proxy HLS du site).
     */
    private suspend fun vidsrcBuzzLinks(
        tmdbId: String,
        season: Int?,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (season != null) {
            "https://vidsrc.buzz/embed/tv/$tmdbId/$season/$episode"
        } else {
            "https://vidsrc.buzz/embed/movie/$tmdbId"
        }
        val html = runCatching { app.get(embedUrl, headers = baseHeaders).text }.getOrNull() ?: return
        val qm = Regex("""var Q = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return
        val q = runCatching { mapper.readTree(qm.groupValues[1]) }.getOrNull() ?: return
        val type = q.path("type").asText("movie").takeIf { it.isNotBlank() } ?: "movie"
        val id = q.path("id").asText()
        val s = q.path("s").asInt()
        val e = q.path("e").asInt()
        val token = q.path("t").asText()
        if (id.isBlank() || token.isBlank()) return
        val qs = "type=$type&id=${java.net.URLEncoder.encode(id, "UTF-8")}&s=$s&e=$e" +
            "&t=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val srcJson = runCatching {
            app.get(
                "https://vidsrc.buzz/pl/api.php?a=sources&$qs",
                headers = baseHeaders + mapOf("Referer" to embedUrl, "Accept" to "application/json")
            ).text
        }.getOrNull() ?: return
        val servers = runCatching { mapper.readTree(srcJson).path("servers") }.getOrNull() ?: return
        if (!servers.isArray) return
        servers.take(4).forEach { sv ->
            val ref = sv.path("ref").asText(null) ?: return@forEach
            val name = sv.path("name").asText("Serveur").replace(Regex("""^Server\s+"""), "").trim()
            val play = runCatching {
                app.get(
                    "https://vidsrc.buzz/pl/api.php?a=play&ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" +
                        "&t=${java.net.URLEncoder.encode(token, "UTF-8")}",
                    headers = baseHeaders + mapOf("Referer" to embedUrl, "Accept" to "application/json")
                ).text
            }.getOrNull() ?: return@forEach
            val u = Regex(""""url"\s*:\s*"([^"]+)"""").find(play)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: return@forEach
            val streamUrl = when {
                u.startsWith("/") -> "https://vidsrc.buzz$u"
                u.startsWith("http") -> u
                else -> return@forEach
            }
            val nm = "1J1F+ · VidSrc $name"
            callback(
                ExtractorLink(
                    nm, nm, streamUrl, "https://vidsrc.buzz/",
                    quality = Qualities.Unknown.value,
                    type = if (".m3u8" in streamUrl) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
    }

    /** POST admin-ajax action=j1f_get_nonce → nonce frais. */
    private suspend fun getNonce(): String? = runCatching {
        val text = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "j1f_get_nonce"),
            headers = ajaxHeaders("$mainUrl/"),
            interceptor = cfKiller
        ).text
        mapper.readTree(text).path("data").path("nonce").asText(null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Un appel j1f_get_source / j1f_get_ep_source → url de la source. */
    private suspend fun fetchSource(params: Map<String, String>): String? = runCatching {
        val text = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = params,
            headers = ajaxHeaders("$mainUrl/"),
            interceptor = cfKiller
        ).text
        val root = mapper.readTree(text)
        if (!root.path("success").asBoolean(false)) return@runCatching null
        root.path("data").path("url").asText(null)?.takeIf { it.startsWith("http") }
    }.getOrNull()

    /** Extrait un flux lisible d'une URL de source (Vidara, Lulustream, direct). */
    private suspend fun extract(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        // fichier direct
        if (url.endsWith(".mp4") || url.endsWith(".m3u8")) {
            callback(newExtractorLink("Direct", "Direct", url) {
                this.type = if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
            return true
        }
        // Vidara (et miroirs) : POST {base}/api/stream {filecode, device} → m3u8
        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: ""
        if (host.contains("vidara", true)) {
            val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return false
            val fileCode = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
            val res = runCatching {
                app.post(
                    "$base/api/stream",
                    json = mapOf("filecode" to fileCode, "device" to "web"),
                    referer = base
                ).text
            }.getOrNull() ?: return false
            val stream = Regex(""""streaming_url"\s*:\s*"([^"]+)"""")
                .find(res)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
            if (!stream.startsWith("http")) return false
            callback(newExtractorLink("Vidara", "Vidara", stream) {
                this.type = if (".m3u8" in stream) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.referer = base
            })
            return true
        }
        // Lulustream (luluvdo.com…) : JS packé → sources:[{file:"…m3u8"}]
        if (host.contains("lulu", true)) {
            val page = runCatching {
                app.get(url, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
            }.getOrNull() ?: return false
            val unpacked = runCatching { JsUnpacker(page).takeIf { it.detect() }?.unpack() }.getOrNull()
            val m3u8 = unpacked?.let {
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(it)?.groupValues?.get(1)
            } ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(page)?.groupValues?.get(1)
            if (m3u8 != null) {
                callback(newExtractorLink("Lulustream", "Lulustream", m3u8) {
                    this.type = ExtractorLinkType.M3U8
                })
                return true
            }
            return false
        }
        // Byse (bysezoxexe.com…) : /api/videos/{code}/ → playback chiffré AES-256-GCM.
        // Clé = base64url(key_parts[v-1]) + base64url(key_parts[31-v-1]) pour la version v
        // (déduit du bundle JS : Ea(v)=[v, 31-v], ws sélectionne 2 parts, ks les concatène).
        if (host.contains("byse", true)) {
            val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return false
            val code = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
            val res = runCatching {
                app.get(
                    "$base/api/videos/$code/",
                    headers = baseHeaders + mapOf(
                        "Referer" to url, "Accept" to "application/json"
                    )
                ).text
            }.getOrNull() ?: return false
            val node = runCatching { mapper.readTree(res) }.getOrNull() ?: return false
            val pb = node.path("playback")
            if (!pb.isObject || pb.size() == 0) return false
            val parts = pb.path("key_parts")
            val version = pb.path("version").asText("").trim().toIntOrNull()
            var keyBytes: ByteArray? = null
            if (parts.isArray && parts.size() > 0 && version != null && version in 1..20) {
                val a = version
                val b = 31 - version
                if (a in 1..parts.size() && b in 1..parts.size()) {
                    val ka = b64u(parts.get(a - 1).asText())
                    val kb = b64u(parts.get(b - 1).asText())
                    if (ka != null && kb != null) keyBytes = ka + kb
                }
            }
            if (keyBytes == null && parts.isArray) {
                // fallback (version inconnue) : concatène toutes les parts, comme le client
                val all = parts.mapNotNull { b64u(it.asText()) }
                if (all.isNotEmpty()) keyBytes = all.reduce { acc, b -> acc + b }
            }
            val iv = b64u(pb.path("iv").asText())
            val payload = b64u(pb.path("payload").asText())
            if (keyBytes == null || iv == null || payload == null) return false
            val plain = runCatching {
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, iv)
                    )
                }.doFinal(payload)
            }.getOrNull() ?: return false
            val info = runCatching { mapper.readTree(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return false
            var found = false
            info.path("sources").forEach { src ->
                val u = src.path("url").asText(null)?.takeIf { it.startsWith("http") } ?: return@forEach
                val label = src.path("label").asText("")?.takeIf { it.isNotBlank() }
                found = true
                callback(
                    newExtractorLink("Byse", "Byse" + (label?.let { " · $it" } ?: ""), u) {
                        this.type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        this.referer = base
                    }
                )
            }
            return found
        }
        // C'était mieux avant (cetaitmieuxavant.website) : page épisode avec
        // const videoData={"servers":[{name,url},…]} → 4 serveurs (firestream,
        // byse, lulustream, p2p). On relaie chaque serveur vers son extracteur.
        if (host.contains("cetaitmieuxavant", true)) {
            val page = runCatching {
                app.get(url.trimEnd('/') + "/", headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
            }.getOrNull() ?: return false
            val vm = Regex("""const videoData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(page)
                ?: return false
            val data = runCatching { mapper.readTree(vm.groupValues[1]) }.getOrNull() ?: return false
            var found = false
            data.path("servers").forEach { sv ->
                val u = sv.path("url").asText(null)?.takeIf { it.startsWith("http") } ?: return@forEach
                if ("p2pstream" in u) return@forEach // lecteur P2P, non extractible
                if (runCatching { extract(u, callback) }.getOrDefault(false)) found = true
            }
            return found
        }
        // FireStream (firestream.site) : /e/{code} → <script id="video-data"> avec
        // signedVideoUrl (MP4/HLS direct). Le champ est retenu si l'IP est flaggée VPN.
        if (host.contains("firestream", true)) {
            val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return false
            val page = runCatching {
                app.get(url, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
            }.getOrNull() ?: return false
            val vm = Regex(
                """<script id="video-data" type="application/json">(.*?)</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(page) ?: return false
            val data = runCatching { mapper.readTree(vm.groupValues[1]) }.getOrNull() ?: return false
            val signed = data.path("video").path("signedVideoUrl").asText(null)
                ?.takeIf { it.startsWith("http") } ?: return false
            callback(
                newExtractorLink("FireStream", "FireStream", signed) {
                    this.type = if (".m3u8" in signed || ".m3u8" in url) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO
                    this.referer = base
                }
            )
            return true
        }
        return false
    }

    /** base64url (alphabet -_ , sans remplissage) → octets. */
    private fun b64u(s: String): ByteArray? = runCatching {
        val pad = if (s.length % 4 == 0) 0 else 4 - s.length % 4
        android.util.Base64.decode(s.replace("-", "+").replace("_", "/") + "=".repeat(pad), android.util.Base64.DEFAULT)
    }.getOrNull()
}
