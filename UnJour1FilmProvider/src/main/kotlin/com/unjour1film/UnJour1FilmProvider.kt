package com.unjour1film

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.DubStatus
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

// ===========================================================================
// 1JOUR1FILM (1jour1film0826b.website) — WordPress + thème DooPlay personnalisé
// ===========================================================================
// Analyse du site (14/09/2026) :
//  · Catalogue : POST /wp-admin/admin-ajax.php action=j1f_catalogue
//    {type: movies|tvshows, search, page, tri} → {html (cartes TMDB),
//    total, pages} — 8 418 films / 1 269 séries.
//  · Dernières sorties : page /dernieres-sorties/ (60 cartes).
//  · Page film : les URL réelles des sources ne sont PAS dans le HTML ;
//    elles viennent de POST action=j1f_get_source&nonce&post_id&idx
//    → {url, type:mp4|iframe}. Le post_id et les libellés (J1F_SRV,
//    J1F_POST_ID) sont dans des scripts inline encodés en base64.
//  · Page série : liens class="season-card" vers /saisons/{slug}/.
//    Chaque page saison embarque (base64) J1F_SEASON_ID et j1fEpsData =
//    [{id, num, label, backdrop, servers:[…]}] — les sources d'épisode
//    viennent de action=j1f_get_ep_source&nonce&season_id&ep_id&idx.
//  · Nonce : POST action=j1f_get_nonce → data.nonce (jetet à chaque session).
//  · Hébergeurs observés :
//      · vidara.to/e/{code} → POST https://vidara.to/api/stream
//        {"filecode","device":"web"} → streaming_url (m3u8 HLS).
//      · luluvdo.com/e/{code} (Lulustream) → JS packé (p,a,c,k,e,d) →
//        sources:[{file:"…master.m3u8"}].
//      · autres iframes génériques → ignorés (non extractibles).
// ===========================================================================

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge.
 */
@CloudstreamPlugin
class UnJour1FilmPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(UnJour1FilmProvider())
    }
}

class UnJour1FilmProvider : MainAPI() {

    override var mainUrl = "https://1jour1film0826b.website"
    override var name = "1JOUR1FILM"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { com.fasterxml.jackson.databind.ObjectMapper() }

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    private fun ajaxHeaders(referer: String) = baseHeaders + mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to mainUrl,
        "Referer" to referer
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
        if (request.name == "sorties") {
            if (page > 1) return newHomePageResponse(request, emptyList(), false)
            val html = runCatching {
                app.get("$mainUrl/dernieres-sorties/", headers = baseHeaders).text
            }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
            return newHomePageResponse(request, parseCards(html), hasNext = false)
        }
        val type = request.name // movies | tvshows
        val root = runCatching {
            catalogue(type, page, "")
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(root.second)
        val hasNext = root.first < 10_000 && items.size >= 20
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
            headers = ajaxHeaders("$mainUrl/catalogue-films/")
        ).text
        val root = runCatching { mapper.readTree(text) }.getOrNull() ?: return 1 to ""
        if (!root.path("success").asBoolean(false)) return 1 to ""
        val d = root.path("data")
        return d.path("pages").asInt(1) to d.path("html").asText("")
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
        val movies = runCatching { catalogue("movies", 1, query) }.getOrNull()?.second
        val series = runCatching { catalogue("tvshows", 1, query) }.getOrNull()?.second
        val out = mutableListOf<SearchResponse>()
        movies?.let { out += parseCards(it) }
        series?.let { out += parseCards(it) }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val html = runCatching {
            app.get(url, headers = baseHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche inaccessible")

        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore('|')?.trim() ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
        val year = Regex("""\((19|20)\d{2}\)""").find(title)?.value?.trim('(', ')')?.toIntOrNull()

        // ---- Série : saisons → épisodes ----
        if (url.contains("/tvshows/")) {
            val seasonLinks = Regex(
                """href="((?:https?://[^"]*)?/saisons/([a-z0-9-]+)/)"[^>]*class="season-card""""
            ).findAll(html).map { if (it.groupValues[1].startsWith("http")) it.groupValues[1] else mainUrl + it.groupValues[1] }
                .distinct().toList()
            if (seasonLinks.isEmpty()) throw ErrorLoadingException("Aucune saison trouvée")

            val episodes = mutableListOf<Episode>()
            seasonLinks.forEach { seasonUrl ->
                val seasonHtml = runCatching { app.get(seasonUrl, headers = baseHeaders).text }.getOrNull() ?: return@forEach
                val scripts = decodeInlineScripts(seasonHtml)
                val seasonId = scripts.firstNotNullOfOrNull { s ->
                    Regex("""J1F_SEASON_ID\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)
                } ?: return@forEach
                val seasonNum = Regex("""[Ss]aison\s*(\d+)""").find(
                    Regex("""<title>([^<]+)</title>""").find(seasonHtml)?.groupValues?.get(1) ?: ""
                )?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                val epsData = scripts.firstNotNullOfOrNull { s ->
                    Regex("""j1fEpsData\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(s)
                        ?.groupValues?.get(1)?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                } ?: return@forEach
                epsData.forEach { ep ->
                    val epId = ep.path("id").asInt(0)
                    val num = ep.path("num").asText("").toIntOrNull() ?: return@forEach
                    if (epId > 0) {
                        episodes += newEpisode("j1fe:$seasonId:$epId") {
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
        val postId = decodeInlineScripts(html).firstNotNullOfOrNull { s ->
            Regex("""J1F_POST_ID\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Identifiant du film introuvable")
        return newMovieLoadResponse(title, url, TvType.Movie, "j1fm:$postId") {
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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val nonce = getNonce() ?: return false
        val urls: List<String> = when {
            data.startsWith("j1fm:") -> {
                val postId = data.removePrefix("j1fm:")
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
        return found
    }

    /** POST admin-ajax action=j1f_get_nonce → nonce frais. */
    private suspend fun getNonce(): String? = runCatching {
        val text = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "j1f_get_nonce"),
            headers = ajaxHeaders("$mainUrl/")
        ).text
        mapper.readTree(text).path("data").path("nonce").asText(null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Un appel j1f_get_source / j1f_get_ep_source → url de la source. */
    private suspend fun fetchSource(params: Map<String, String>): String? = runCatching {
        val text = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = params,
            headers = ajaxHeaders("$mainUrl/")
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
        return false
    }
}
