package com.xalaflix

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ===========================================================================
// Xalaflix — films & séries VF/VOSTFR multi-serveurs (Laravel + Livewire)
// ===========================================================================
// Site : https://xalaflix.tax — la page d'annonce https://xalaflix.online
// publie le domaine courant (résolution automatique au démarrage, avec
// validation ; ⚙ pour forcer une adresse).
//   Listes    : /trending · /movies?page=N · /tv-shows?page=N · /top-imdb
//   Recherche : /search/{q} (espaces = %20)
//   Fiches    : /movie/{slug} · /tv-show/{slug} — saisons via Livewire
//               (updateSeason), épisodes /episode/{slug}/{s}-{e}
//   Serveurs  : const videos = [{server_name, version, link}] (vidzy XOR,
//               kakaflix dood/voe, vidsrc.xyz, flixeo…) + fallback iframes —
//               en parallèle, bornés à 15 s/hôte.
//   Supplément : id TMDB (présent dans les liens du site) → lecteurs publics
//               (Videasy, VidFast, VidSrc.cc, 2Embed…) + vidsrc.buzz.
// ===========================================================================
@CloudstreamPlugin
class XalaflixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        XalaflixProvider.appContext = context.applicationContext
        registerMainAPI(XalaflixProvider())
        openSettings = { ctx -> XalaflixProvider.showSettings(ctx) }
    }
}

class XalaflixProvider : MainAPI() {
    companion object {
        const val DEFAULT_URL = "https://xalaflix.tax"
        // registry public : page d'annonce du domaine courant
        const val REGISTRY_URL = "https://xalaflix.online/"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "movix_settings"
        private const val PREF_URL = "site_url"

        fun customUrl(): String? = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()

        fun currentUrl(): String = customUrl() ?: DEFAULT_URL

        fun setSiteUrl(context: android.content.Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentUrl()); hint = "https://xalaflix.tax"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de Xalaflix")
                .setMessage("Laissez vide pour la résolution automatique via la page d'annonce officielle (xalaflix.online).")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Auto") { _, _ -> setSiteUrl(context, null) }
                .show()
        }
    }

    override var mainUrl = DEFAULT_URL
    override var name = "Xalaflix"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun headers() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    private val cfKiller by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

    @Volatile
    private var resolvedFromRegistry: String? = null

    /**
     * Résolution du domaine courant : adresse ⚙ → page d'annonce officielle
     * (xalaflix.online, liens « xalaflix.* ») → défaut. Le résultat est
     * validé sur la page d'accueil (cartes /movie|tv-show/).
     */
    private suspend fun ensureDomain(): String {
        customUrl()?.let { mainUrl = it; return it }
        resolvedFromRegistry?.let { mainUrl = it; return it }
        val candidates = mutableListOf<String>()
        runCatching {
            val page = app.get(REGISTRY_URL, headers = headers()).text
            Regex("""https?://xalaflix\.[a-z]{2,6}/?""").findAll(page)
                .map { it.value.trimEnd('/') }.toCollection(candidates)
        }
        candidates += DEFAULT_URL
        for (c in candidates.distinct()) {
            val ok = runCatching {
                val home = app.get(c, headers = headers()).text
                Regex("""/(?:movie|tv-show)/[a-z0-9-]+""").containsMatchIn(home)
            }.getOrDefault(false)
            if (ok) {
                resolvedFromRegistry = c
                mainUrl = c
                return c
            }
        }
        mainUrl = DEFAULT_URL
        return DEFAULT_URL
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    override val mainPage = mainPageOf(
        "trending" to "🔥 Tendances",
        "movies" to "🎬 Films",
        "tv-shows" to "📺 Séries",
        "top-imdb" to "⭐ Top IMDb"
    )

    // -------------------------------------------------------------------------
    // Listes — <a href=/movie|tv-show/{slug}> + <img data-src=… alt=TITRE>
    // -------------------------------------------------------------------------
    private fun parseCards(html: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        Regex("""<a href="(?:https?://[^"]*?)/(movie|tv-show)/([a-z0-9-]+)"[\s\S]{0,400}?<img[^>]+data-src="([^"]+)"[^>]*alt="([^"]*)"""")
            .findAll(html).forEach { m ->
                val (kind, slug, img, title) = m.destructured
                if (title.isBlank()) return@forEach
                out[slug] = if (kind == "tv-show") {
                    newTvSeriesSearchResponse(title, "$mainUrl/$kind/$slug") { this.posterUrl = img }
                } else {
                    newMovieSearchResponse(title, "$mainUrl/$kind/$slug", TvType.Movie) { this.posterUrl = img }
                }
            }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val base = when (request.data) {
            "trending", "top-imdb" -> "$mainUrl/${request.data}"
            else -> "$mainUrl/${request.data}?page=$page"
        }
        val html = runCatching { app.get(base, headers = headers(), interceptor = cfKiller).text }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        // trending/top-imdb : une seule page ; listes : ?page=N
        val hasNext = request.data !in setOf("trending", "top-imdb") && items.size >= 20
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    // -------------------------------------------------------------------------
    // Recherche — GET /search/{q} (page dédiée, mêmes cartes que les listes)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        ensureDomain()
        // ⚠ URLEncoder produit « + » pour les espaces → 0 résultat sur movix
        // (le site attend %20, encodage de chemin et non de formulaire)
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return emptyList()
        val html = runCatching {
            app.get("$mainUrl/search/$q", headers = headers(), interceptor = cfKiller).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    /** Extrait (et déséchappe) le snapshot JSON d'un composant Livewire. */
    private fun extractSnapshot(html: String, componentName: String): String? {
        Regex("""wire:snapshot="([^"]*)"""").findAll(html).forEach { m ->
            val snap = m.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
            if (""""name":"$componentName"""" in snap) return snap
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Fiches
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        ensureDomain()
        val html = runCatching { app.get(url, headers = headers(), interceptor = cfKiller).text }.getOrNull()
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Fiche introuvable")
        val slug = url.trimEnd('/').substringAfterLast('/')
        val isSeries = "/tv-show/" in url

        val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore(" streaming")?.trim()?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val year = Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val score = Regex("""([\d.]+)\s*(?:/|sur)\s*10""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        }

        // Série : épisodes de la saison par défaut + les autres via Livewire
        val episodes = mutableListOf<Episode>()
        fun parseEpisodeCards(block: String, season: Int) {
            Regex("""<a href="(?:https?://[^"]*?)/episode/([a-z0-9-]+)/(\d+)-(\d+)"""")
                .findAll(block).forEach { m ->
                    val (s, e) = m.groupValues[2].toInt() to m.groupValues[3].toInt()
                    episodes += newEpisode("$mainUrl/episode/${m.groupValues[1]}/$s-$e") {
                        this.name = "Épisode $e"
                        this.season = s
                        this.episode = e
                        this.posterUrl = poster
                    }
                }
        }
        // épisodes rendus (saison par défaut)
        parseEpisodeCards(html, 1)
        // boutons des autres saisons : wire:click="updateSeason('id')"
        val seasonIds = Regex("""updateSeason\('(\d+)'\)""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        val renderedEpCount = episodes.size
        if (seasonIds.size > 1 || (seasonIds.isNotEmpty() && renderedEpCount == 0)) {
            val snapshot = extractSnapshot(html, "season-component")
            if (snapshot != null) {
                seasonIds.forEach { sid ->
                    // évite de recharger la saison déjà rendue : la réponse
                    // inclut de toute façon la saison demandée
                    val body = mapOf(
                        "components" to listOf(
                            mapOf(
                                "snapshot" to snapshot,
                                "updates" to emptyMap<String, String>(),
                                "calls" to listOf(mapOf("method" to "updateSeason", "params" to listOf(sid)))
                            )
                        )
                    )
                    val resp = runCatching {
                        app.post(
                            "$mainUrl/livewire/update",
                            headers = headers() + mapOf("X-Livewire" to "true"),
                            json = body
                        ).text
                    }.getOrNull() ?: return@forEach
                    val block = runCatching {
                        AppUtils.parseJson<LivewireResponse>(resp).components.firstOrNull()?.effects?.html
                    }.getOrNull() ?: return@forEach
                    parseEpisodeCards(block, 1)
                }
            }
        }
        val unique = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
        if (unique.isEmpty()) {
            throw com.lagradost.cloudstream3.ErrorLoadingException("Aucun épisode trouvé")
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score?.let { com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Lecteurs — versionController() : videos = [{server_name,label,version,link}]
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MvVideo(
        val server_name: String? = null,
        val label: String? = null,
        val version: String? = null,
        val link: String? = null,
        val type: String? = null
    )

    // -------------------------------------------------------------------------
    // vidsrc.buzz — agrégateur TMDB, HLS proxysés directs
    // -------------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsQ(val type: String? = null, val id: String? = null, val s: Int? = null, val e: Int? = null, val t: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsServer(val ref: String? = null, val name: String? = null)

    // ⚠ l'API renvoie un OBJET {"status":..,"servers":[..]}, pas une liste brute
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsSources(val status: String? = null, val servers: List<VsServer> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfImdb(val imdb_id: String? = null)

    // liens de l'API du réseau Frembed (url RELATIVE /api/stream?…)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfLinks(val links: List<XfLink> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XfLink(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val host: XfHost? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class XfHost(val name: String? = null, val slug: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsPlay(val url: String? = null)

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
                "https://api.themoviedb.org/3/$path/$tmdbId/external_ids?api_key=f3d757824f08ea2cff45eb8f47ca3a1e&language=fr-FR",
                headers = headers()
            ).text
            AppUtils.parseJson<XfImdb>(j).imdb_id?.takeIf { it.startsWith("""tt""") }
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
        val qm = Regex("""var Q = (\\{.*?\\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return false
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
                    val svName = sv.name?.replace(Regex("""^Server\\s+"""), "")?.trim()
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

    // --- réseau Frembed : serveurs réels du réseau (Voe/Dood/Uqload) par API.
    //     links[].url est RELATIF (/api/stream?…) : cookies + Referer fiche +
    //     headers iframe → 302 vers l'hôte réel, décodé ensuite par loadExtractor.
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
        runCatching { app.get(contentPage, headers = headers()) }
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
        val links = runCatching { AppUtils.parseJson<XfLinks>(apiJson).links }.getOrNull() ?: return false
        var found = false
        links.forEach { l ->
            val raw = l.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val streamUrl = if (raw.startsWith("http")) raw else "$origin$raw"
            val hostName = l.host?.name?.takeIf { it.isNotBlank() }
                ?: l.label?.takeIf { it.isNotBlank() } ?: "Serveur"
            val lang = l.lang?.uppercase()?.take(5)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            val label = "Xalaflix+ · Frembed $hostName$lang"
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

    // -------------------------------------------------------------------------
    // Extraction générique (vidzy XOR, kakaflix/dood, voe, JW p.a.c.k.e.r…)
    // -------------------------------------------------------------------------
    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")
    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )

    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(embedUrl, referer = mainUrl, headers = mapOf("user-agent" to USER_AGENT), interceptor = cfKiller).text
        // page d'attente anti-bot vidzy.org (compte à rebours 180 s) : aucun flux
        if ("Chargement vidéo" in page && "location.reload" in page) return@runCatching false
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
        // lecteurs videojs obfusqués (vidzy.cc, fsvid.lol…) : src = XOR(base64, hostname)
        Regex("""\}\("([A-Za-z0-9+/=]{40,})"\)""").findAll(page).forEach { m ->
            val host = Regex("""^https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return@forEach
            decodeXorSource(m.groupValues[1], host)?.let { links.add(it) }
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

    /** vidzy/fsvid : sources videojs encodées XOR(base64 inversé, somme des codes du hostname). */
    private fun decodeXorSource(b64: String, hostname: String): String? = runCatching {
        val h = hostname.sumOf { it.code } and 0xFF
        val a = android.util.Base64.decode(b64, android.util.Base64.DEFAULT).reversed()
        val out = StringBuilder()
        for (i in a.indices) {
            val kk = (0x3d + i * 89 + h) and 0xFF
            out.append(((a[i].toInt() and 0xFF) xor kk).toChar())
        }
        out.toString().takeIf { it.startsWith("http") }
    }.getOrNull()
}

// Réponse Livewire standard
@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireComponent(
    val snapshot: String? = null,
    val effects: LivewireEffects? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireEffects(val html: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LivewireResponse(val components: List<LivewireComponent> = emptyList())
