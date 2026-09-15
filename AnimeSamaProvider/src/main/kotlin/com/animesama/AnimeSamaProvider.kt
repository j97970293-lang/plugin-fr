package com.animesama

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class AnimeSamaPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        AnimeSamaProvider.appContext = context.applicationContext
        registerMainAPI(AnimeSamaProvider())
        // ansembed.net — lecteur JWPlayer utilisé par Anime-Sama
        registerExtractorAPI(AnsEmbed())
        openSettings = { ctx -> AnimeSamaProvider.showSettings(ctx) }
    }
}

/**
 * ansembed.net — clone VidMoly (JWPlayer) : sources: [{ file: 'https://…/master.m3u8' }]
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

// ===========================================================================
// Anime-Sama (anime-sama.to) — catalogue d'animes VF/VOSTFR
//   · Catalogue : /catalogue/ (une grande page). Recherche : POST
//     /template-php/defaut/fetch.php {query} (ouverte).
//   · Fiche anime : poster og:image (GitHub), saisons déclarées par
//     panneauAnime("Saison 1", "saison1/vostfr") — plusieurs appels, l'URL est
//     relative à /catalogue/{slug}/ et le suffixe donne la langue (vostfr|vf).
//   · Page saison : /catalogue/{slug}/{saison}/{lang}/ contient
//     <script src='episodes.js?filever=N'> (RELATIF) définissant
//     var eps1 = ['URL ép.1', 'URL ép.2', …] — un tableau par lecteur
//     (eps2, eps3… = miroirs du même épisode).
//   · Lecteurs : ansembed.net (JW), sibnet, vidmoly, sendvid, uqload…
// ===========================================================================
class AnimeSamaProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "AnimeSama"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // -------------------------------------------------------------------------
    // Réglages
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://anime-sama.to"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "animesama_settings"
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
                setText(currentUrl())
                hint = "https://…"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse d'Anime-Sama")
                .setMessage("Le site change de domaine (anime-sama.to, .fr, .com…). Indiquez l'adresse actuelle.")
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setSiteUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setSiteUrl(context, DEFAULT_URL) }
                .show()
        }
    }

    private fun syncUrl() {
        mainUrl = currentUrl()
    }

    private val baseHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    // -------------------------------------------------------------------------
    // Accueil & recherche
    // -------------------------------------------------------------------------
    // Sections de la home : carrousel « Nouveautés », cartes « Derniers
    // épisodes ajoutés » (avec badge langue), planning et catalogue complet.
    // ⚠ Router sur request.data (la clé), jamais request.name (le libellé).
    override val mainPage = mainPageOf(
        "nouveautes" to "Nouveautés",
        "vostfr" to "Derniers épisodes VOSTFR",
        "vf" to "Derniers épisodes VF",
        "planning" to "Animes du planning (en cours)",
        "catalogue" to "Catalogue complet"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        if (page > 1) return newHomePageResponse(request, emptyList(), false)
        when (request.data) {
            "catalogue" -> {
                val html = runCatching {
                    app.get(currentUrl() + "/catalogue/", headers = baseHeaders).text
                }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
                return newHomePageResponse(request, parseCatalogue(html), hasNext = false)
            }
            "nouveautes" -> {
                val home = fetchHome() ?: return newHomePageResponse(request, emptyList(), false)
                return newHomePageResponse(request, parseCarousel(home), hasNext = false)
            }
            "planning" -> {
                val html = runCatching {
                    app.get(currentUrl() + "/planning/", headers = baseHeaders).text
                }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
                val cards = parseRecentCards(html)
                    .distinctBy { it.slug }
                    .map {
                        newAnimeSearchResponse(it.title, "$mainUrl/catalogue/${it.slug}/", TvType.Anime) {
                            this.posterUrl = it.poster
                        }
                    }
                return newHomePageResponse(request, cards, hasNext = false)
            }
            else -> { // vostfr / vf
                val wantLang = if (request.data == "vf") "vf" else "vostfr"
                val home = fetchHome() ?: return newHomePageResponse(request, emptyList(), false)
                val cards = parseRecentCards(home)
                    .filter { it.lang == wantLang }
                    .distinctBy { it.slug }
                    .map {
                        newAnimeSearchResponse(it.title, "$mainUrl/catalogue/${it.slug}/", TvType.Anime) {
                            this.posterUrl = it.poster
                        }
                    }
                return newHomePageResponse(request, cards, hasNext = false)
            }
        }
    }

    /** HTML de la page d'accueil (mise en cache par requête). */
    private var homeCache: Pair<Long, String>? = null
    private suspend fun fetchHome(): String? {
        val cached = homeCache
        if (cached != null && System.currentTimeMillis() - cached.first < 60_000L) return cached.second
        val html = runCatching {
            app.get(currentUrl() + "/", headers = baseHeaders).text
        }.getOrNull() ?: return null
        homeCache = System.currentTimeMillis() to html
        return html
    }

    /** Slides du carrousel d'accueil : titre + poster + langues CTA. */
    private fun parseCarousel(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<div class="ak-slide[^"]*"[^>]*>(.*?)</div>\s*</div>\s*</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val seg = m.groupValues[1]
            val title = Regex("""<h2 class="ak-slide-title">([^<]+)</h2>""").find(seg)?.groupValues?.get(1)?.trim() ?: return@forEach
            val poster = Regex("""<div class="ak-slide-bg"><img[^>]+src="([^"]+)"""").find(seg)?.groupValues?.get(1)
            val slug = Regex("""href="(?:https?://[^"]*)?/catalogue/([a-z0-9.-]+)/[^>]*>""")
                .find(seg)?.groupValues?.get(1) ?: return@forEach
            val url = "$mainUrl/catalogue/$slug/"
            if (out.none { it.url == url }) {
                out += newAnimeSearchResponse(htmlUnescape(title), url, TvType.Anime) { posterUrl = poster }
            }
        }
        return out
    }

    /** Cartes « Derniers épisodes ajoutés » de la home. */
    private data class RecentCard(val slug: String, val lang: String, val title: String, val poster: String?)

    private fun parseRecentCards(html: String): List<RecentCard> {
        val out = mutableListOf<RecentCard>()
        Regex(
            """<a href="(?:https?://[^"]*)?/catalogue/([a-z0-9.-]+)/([a-z0-9]+)/([a-z]+)/"[^>]*>\s*<div class="card-image-container">\s*<img[^>]+class="card-image"[^>]+src="([^"]+)"[^>]*alt="([^"]*)""""
        ).findAll(html).forEach { m ->
            val (slug, _, lang, poster, alt) = m.destructured
            val title = htmlUnescape(alt).trim().ifBlank { slug.replace('-', ' ') }
            out += RecentCard(slug, lang, title, poster)
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val html = runCatching {
            app.post(
                currentUrl() + "/template-php/defaut/fetch.php",
                referer = currentUrl() + "/",
                headers = baseHeaders,
                data = mapOf("query" to query)
            ).text
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a\s+href="([^"]+/catalogue/([a-z0-9.-]+)/?)"[^>]*class="asn-search-result"[^>]*>.*?<img[^>]+src="([^"]+)".*?<h3[^>]*>([^<]+)</h3>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val poster = m.groupValues[3]
            val title = htmlUnescape(m.groupValues[4]).trim()
            if (title.isNotBlank()) {
                out += newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
            }
        }
        return out.distinctBy { it.url }.ifEmpty { parseCatalogue(html) }
    }

    /** Décode les entités HTML courantes des titres. */
    private fun htmlUnescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    /** Cartes du catalogue : <a href="…/catalogue/{slug}"><img class="card-image" src=… alt="{titre}"> */
    private fun parseCatalogue(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a\s+href="((?:https?://[^"]+)?/catalogue/([a-z0-9.-]+))/?"[^>]*>\s*<div[^>]*>\s*<img[^>]+src="([^"]+)"[^>]+alt="([^"]*)""""
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val url = if (href.startsWith("http")) href else currentUrl() + href
            val poster = m.groupValues[3]
            val title = htmlUnescape(m.groupValues[4]).trim().ifBlank { m.groupValues[2].replace('-', ' ') }
            out += newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
        }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche : saisons déclarées par panneauAnime + comptage via episodes.js
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val slug = url.trimEnd('/').substringAfterLast('/')
        val html = runCatching {
            app.get("$mainUrl/catalogue/$slug/", headers = baseHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche inaccessible")

        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: slug.replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // saisons : panneauAnime("Nom", "chemin")
        val entries = Regex("""panneauAnime\("([^"]+)",\s*"([^"]+)"\)""")
            .findAll(html).map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first != "nom" && it.second != "url" } // en-tête du template
            .toList()
        if (entries.isEmpty()) throw ErrorLoadingException("Aucune saison trouvée sur cette fiche.")

        // Regroupe par nom (Saison 1 vostfr + Saison 1 vf = une entrée).
        // ⚠ CloudStream fusionne les épisodes qui partagent le même couple
        // (season, episode) : il faut donc un numéro de saison UNIQUE par
        // panneau. « Saga N » (One Piece…), « Kai - Saga N », « saisonNhs »,
        // « Films », « OAV » doivent être distingués.
        data class SeasonInfo(
            val name: String,
            val paths: List<String>,
            val uniqueSeason: Int     // clé unique pour CloudStream (évite les fusions)
        )

        val grouped = entries.groupBy({ it.first }, { it.second })
            .map { (name, paths) -> name to paths.distinct() }

        // numéros déjà utilisés, pour garantir l'unicité
        val used = mutableSetOf<Int>()
        fun uniqueOrNext(base: Int): Int {
            var n = base
            while (n in used) n++
            used += n
            return n
        }

        val byName = grouped.mapIndexed { idx, (name, paths) ->
            val isKai = paths.any { it.startsWith("kai") } ||
                Regex("""\bkai\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
            val isHs = paths.any { Regex("""saison\d+hs""").containsMatchIn(it) }
            val isFilm = name.contains("film", true) || paths.any { it.startsWith("film") }
            val isOav = name.contains("oav", true) || paths.any { it.startsWith("oav") }
            // numéro « naturel » : Saga 3 / Saison 3 / saison3hs / kai3
            val natural = Regex("""(?:saison|season|saga|kai)\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: paths.mapNotNull { p -> Regex("""(?:saison|kai)(\d+)""").find(p)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
            val unique = when {
                isKai -> uniqueOrNext(100 + (natural ?: idx + 1))
                isHs -> uniqueOrNext(40 + (natural ?: idx + 1))
                isFilm -> uniqueOrNext(90)
                isOav -> uniqueOrNext(91)
                natural != null -> uniqueOrNext(natural)
                else -> uniqueOrNext(300 + idx)
            }
            SeasonInfo(name, paths, unique)
        }.sortedWith(compareBy({ it.uniqueSeason >= 300 }, { it.uniqueSeason }))

        // Compte les épisodes de chaque saison (episodes.js). On limite la
        // concurrence (Semaphore) et on réessaie : en 4G, 27 requêtes d'un coup
        // font échouer la moitié des comptages → saisons réduites à 1 épisode.
        val sem = kotlinx.coroutines.sync.Semaphore(3)
        val counts = coroutineScope {
            byName.map { s ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val c = s.paths.mapNotNull { p -> countEpisodesRobust("$mainUrl/catalogue/$slug/$p/") }.maxOrNull()
                        s to c
                    }
                }
            }.awaitAll()
        }

        // Versions VF : la fiche ne liste souvent que les panneaux VOSTFR alors
        // que les pages /vf/ existent (découvertes via les CTA de la home). On
        // sonde la 1re saison en VF ; si elle existe, on sonde toutes les autres
        // → remplit la piste DUB (sélecteur SUB/DUB dans CloudStream).
        val vfCounts: Map<SeasonInfo, Int> = run {
            val firstVostfr = byName.firstOrNull()?.paths?.firstOrNull { it.endsWith("/vostfr") }
                ?: return@run emptyMap()
            val firstVf = firstVostfr.removeSuffix("vostfr") + "vf"
            if (runCatching { countEpisodesRobust("$mainUrl/catalogue/$slug/$firstVf/") }.getOrNull() == null) {
                return@run emptyMap()
            }
            coroutineScope {
                byName.map { s ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val vfPaths = s.paths.filter { it.endsWith("/vostfr") }
                                .map { it.removeSuffix("vostfr") + "vf" }
                            val c = vfPaths.mapNotNull { p -> countEpisodesRobust("$mainUrl/catalogue/$slug/$p/") }.maxOrNull()
                            s to c
                        }
                    }
                }.awaitAll()
            }.mapNotNull { (s, c) -> c?.let { s to it } }.toMap()
        }

        val subbed = mutableListOf<Episode>()
        val dubbed = mutableListOf<Episode>()
        counts.forEach { (season, count) ->
            val n = count ?: 1
            season.paths.forEach { path ->
                val isVf = path.trimEnd('/').endsWith("/vf")
                val list = if (isVf) dubbed else subbed
                // nom lisible : tout sauf « Saison N » / « Season N » toutes seules
                val plain = Regex("""^\s*(?:saison|season)\s*\d+\s*$""", RegexOption.IGNORE_CASE).matches(season.name)
                for (ep in 1..n) {
                    list += newEpisode(episodeDataUrl(slug, path, ep)) {
                        this.season = season.uniqueSeason
                        this.episode = ep
                        if (!plain) this.name = "${season.name} · Épisode $ep"
                        // pas de vignette d'épisode côté site → poster de la fiche
                        this.posterUrl = poster
                    }
                }
            }
            // piste VF (dub) de la même saison, si les pages /vf/ existent
            vfCounts[season]?.let { nvf ->
                season.paths.firstOrNull { it.endsWith("/vostfr") }?.let { vostfrPath ->
                    val vfPath = vostfrPath.removeSuffix("vostfr") + "vf"
                    val plain = Regex("""^\s*(?:saison|season)\s*\d+\s*$""", RegexOption.IGNORE_CASE).matches(season.name)
                    for (ep in 1..nvf) {
                        dubbed += newEpisode(episodeDataUrl(slug, vfPath, ep)) {
                            this.season = season.uniqueSeason
                            this.episode = ep
                            if (!plain) this.name = "${season.name} · Épisode $ep"
                            this.posterUrl = poster
                        }
                    }
                }
            }
        }

        val isMovie = byName.size == 1 &&
            (byName[0].name.contains("film", true) || byName[0].name.contains("oav", true) || byName[0].name.contains("movie", true))

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = ShowStatus.Ongoing
            val byDub = mutableMapOf<DubStatus, List<Episode>>()
            if (subbed.isNotEmpty()) byDub[DubStatus.Subbed] = subbed
            if (dubbed.isNotEmpty()) byDub[DubStatus.Dubbed] = dubbed
            this.episodes = byDub.toMutableMap()
        }
    }

    private fun episodeDataUrl(slug: String, seasonPath: String, ep: Int): String =
        mainUrl + "/e?slug=" + java.net.URLEncoder.encode(slug, "UTF-8") +
            "&p=" + java.net.URLEncoder.encode(seasonPath, "UTF-8") + "&n=$ep"

    /** Nombre d'épisodes d'une page saison (via episodes.js), avec 2 tentatives. */
    private suspend fun countEpisodesRobust(seasonUrl: String): Int? {
        repeat(3) { attempt ->
            val n = runCatching { countEpisodes(seasonUrl) }.getOrNull()
            if (n != null) return n
            if (attempt < 2) kotlinx.coroutines.delay(400L * (attempt + 1))
        }
        return null
    }

    /** Nombre d'épisodes d'une page saison (via episodes.js).
     *  null = erreur réseau (à retenter) ; 0 = page sans episodes.js (ex. VF absent). */
    private suspend fun countEpisodes(seasonUrl: String): Int? {
        val html = runCatching {
            app.get(seasonUrl, headers = baseHeaders).text
        }.getOrNull() ?: return null
        val hasJs = Regex("""src=['"][^'"]*episodes\.js""").containsMatchIn(html)
        val arrays = fetchEpsArrays(seasonUrl, html)
        if (arrays.isEmpty()) return if (hasJs) null else 0
        return arrays.maxOfOrNull { it.value.size } ?: 0
    }

    /** episodes.js de la page saison → {epsN → [urls]} */
    private suspend fun fetchEpsArrays(seasonUrl: String, html: String): Map<String, List<String>> {
        val m = Regex("""src=['"]([^'"]*episodes\.js[^'"]*)['"]""").find(html) ?: return emptyMap()
        val src = m.groupValues[1]
        val jsUrl = when {
            src.startsWith("http") -> src
            src.startsWith("/") -> currentUrl() + src
            else -> seasonUrl.trimEnd('/') + "/" + src
        }
        val js = runCatching {
            app.get(jsUrl, headers = baseHeaders, referer = seasonUrl).text
        }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        Regex("""var\s+(eps\d+)\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).findAll(js).forEach { em ->
            val urls = Regex("""['"]([^'"]+)['"]""").findAll(em.groupValues[2]).map { it.groupValues[1] }.toList()
            if (urls.isNotEmpty()) out[em.groupValues[1]] = urls
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
        syncUrl()
        val params = parseParams(data.substringAfter("?", ""))
        val slug = params["slug"] ?: return false
        val path = params["p"] ?: return false
        val ep = params["n"]?.toIntOrNull() ?: 1

        val seasonUrl = "$mainUrl/catalogue/$slug/${path.trim('/')}/"
        val html = runCatching {
            app.get(seasonUrl, headers = baseHeaders).text
        }.getOrNull() ?: return false
        val arrays = fetchEpsArrays(seasonUrl, html)
        if (arrays.isEmpty()) return false

        // miroirs de l'épisode : eps1[i], eps2[i]… (index 0-based)
        val mirrors = arrays.entries
            .sortedBy { it.key.substringAfter("eps").toIntOrNull() ?: 0 }
            .mapNotNull { (_, urls) -> urls.getOrNull(ep - 1) }
            .filter { it.startsWith("http") }
            .distinct()
        if (mirrors.isEmpty()) return false

        var found = false
        val lock = Any()
        val doctorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val doctorJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun emit(link: ExtractorLink) {
            synchronized(seenUrls) { if (seenUrls.contains(link.url)) return }
            seenUrls.add(link.url)
            doctorJobs += doctorScope.launch {
                if (isPlayableBlocking(link)) {
                    synchronized(lock) {
                        found = true
                        callback(link)
                    }
                }
            }
        }

        val semaphore = Semaphore(6)
        coroutineScope {
            mirrors.map { u ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val label = "AnimeSama · " + labelFromUrl(u)
                        if (directStreamRegex.matches(u)) {
                            emit(
                                newExtractorLink(label, label, u) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                    this.type = if (".m3u8" in u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                            return@async
                        }
                        var produced = 0
                        runCatching {
                            loadExtractor(u, mainUrl, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link ->
                                produced++
                                emit(relabel(link, label))
                            }
                        }
                        if (produced == 0) {
                            runCatching {
                                genericExtract(u, label, { sub -> synchronized(lock) { subtitleCallback(sub) } }) { link -> emit(link) }
                            }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        doctorJobs.forEach { it.join() }
        doctorScope.cancel()
        return found
    }

    private fun labelFromUrl(url: String): String {
        val u = url.lowercase()
        return when {
            "ansembed" in u -> "AnsEmbed"
            "sibnet" in u -> "Sibnet"
            "vidmoly" in u -> "VidMoly"
            "sendvid" in u -> "SendVid"
            "uqload" in u -> "Uqload"
            "filemoon" in u -> "FileMoon"
            "dood" in u -> "Dood"
            "streamtape" in u -> "Streamtape"
            "voe" in u -> "Voe"
            else -> Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)?.removePrefix("www.") ?: "Lecteur"
        }
    }

    /** Paramètres de l'URL de données (?slug=…&p=…&n=…). */
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
    // Docteur de liens (identique aux autres extensions du dépôt)
    // -------------------------------------------------------------------------
    private val doctorClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun isPlayableBlocking(link: ExtractorLink): Boolean = try {
        val builder = okhttp3.Request.Builder().url(link.url).header("Range", "bytes=0-1023")
        val headers = (link.headers ?: emptyMap()).toMutableMap()
        if (!headers.containsKey("User-Agent")) headers["User-Agent"] = USER_AGENT
        if (link.referer.isNotBlank() && !headers.containsKey("Referer")) headers["Referer"] = link.referer
        headers.forEach { (k, v) -> builder.header(k, v) }
        doctorClient.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                val code = res.code
                return@use code == 403 || code == 416 || code == 429
            }
            val body = res.body ?: return@use false
            val head = ByteArray(512)
            var n = 0
            while (n < 512) {
                val read = body.byteStream().read(head, n, 512 - n)
                if (read <= 0) break
                n += read
            }
            if (n <= 0) return@use false
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            val prefix = String(head, 0, n, Charsets.ISO_8859_1)
            val isFtyp = n >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            val isEbml = n >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
            val isTs = (head[0].toInt() and 0xFF) == 0x47
            val isFlv = prefix.startsWith("FLV") || prefix.startsWith("OggS") || prefix.startsWith("ID3")
            val isMp3Sync = n >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
            when {
                link.type == ExtractorLinkType.DASH || ".mpd" in link.url ->
                    prefix.contains("<MPD") || prefix.contains("<?xml")
                link.type == ExtractorLinkType.M3U8 || ".m3u8" in link.url || contentType.contains("mpegurl") ->
                    prefix.contains("#EXTM3U") || contentType.contains("mpegurl")
                contentType.startsWith("video/") || contentType.startsWith("audio/") -> true
                prefix.contains("#EXTM3U") -> true
                isFtyp || isEbml || isTs || isFlv || isMp3Sync || prefix.startsWith("RIFF") -> true
                else -> false
            }
        }
    } catch (e: Exception) {
        true
    }

    private fun relabel(link: ExtractorLink, label: String): ExtractorLink {
        return ExtractorLink(
            link.source, label, link.url, link.referer, link.quality,
            link.headers, link.extractorData, link.type, link.audioTracks
        )
    }

    // -------------------------------------------------------------------------
    // Fallback générique
    // -------------------------------------------------------------------------
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(embedUrl, referer = mainUrl, headers = mapOf("user-agent" to USER_AGENT)).text

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
        Regex("""\}\("([A-Za-z0-9+/=]{40,})"\)""").findAll(page).forEach { m ->
            val host = Regex("""^https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return@forEach
            decodeXorSource(m.groupValues[1], host)?.let { links.add(it) }
        }

        links.asSequence()
            .filter { it.startsWith("http") }
            .map { it.replace("&amp;", "&") }
            .filter { link -> junkFilterRegex.containsMatchIn(link).not() }
            .filter { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".webm") || directStreamRegex.matches(it) }
            .distinct()
            .forEach { link ->
                callback(
                    newExtractorLink(label, label, link) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

        links.isNotEmpty()
    }.getOrDefault(false)

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

    private val directStreamRegex = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4|webm)[^"'\\\s<>]*""")

    private val junkFilterRegex = Regex(
        """(?i)(youtube|youtu\.be|dailymotion|\.jpg|\.jpeg|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|""" +
            """/ads?/|adserve|adservice|adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
    )
}
