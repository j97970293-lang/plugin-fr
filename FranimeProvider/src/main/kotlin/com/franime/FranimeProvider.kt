package com.franime

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

/**
 * Entry point of the plugin, this is the class that CloudStream loads.
 */
@CloudstreamPlugin
class FranimePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        FranimeProvider.appContext = context.applicationContext
        registerMainAPI(FranimeProvider())
        // Bouton « Réglages » sur la fiche de l'extension dans CloudStream
        openSettings = { ctx -> FranimeProvider.showSettings(ctx) }
    }
}

// ===========================================================================
// FRAnime (franime.fr) — analyse complète (sept. 2026)
//
//   · Site Next.js protégé Cloudflare + API publique https://api.franime.fr/api
//     (Origin/Referer franime.fr requis ; cfKiller résout le challenge).
//   · Le catalogue complet (/api/animes ≈ 11 Mo) n'est JAMAIS téléchargé :
//     les listes et la recherche passent par l'API publique Kitsu — FRAnime
//     indexe les MÊMES IDs Kitsu (https://kitsu.io/api/edge/anime).
//   · Saisons/épisodes : /api/anime-seasons/{id} → { episodeDetails:
//     [{ seasonNumber, episodes: [{ number, title, thumbnail }] }] }
//     (les INDEX 0-based de saison/épisode sont ceux qu'attend l'API lecteur).
//   · Lecteur : /api/anime/{id}/{saisonIndex}/{episodeIndex}/{lang}/{lecteurIndex}
//     (lang = vo|vf, INDEX 0-based) → corps = URL franime.fr/watch2/?a=…&b=…
//     → décodage : paramètre b64 → hex → XOR (clé 1 octet, brute-force 0-255).
//   · Hébergeurs : sibnet, vidmoly, sendvid, filemoon, uqload, streamtape,
//     doodstream, vk, vido… (tous via extracteurs intégrés + fallback générique).
//   · Anti-scraping : aux clients non-navigateurs l'API peut servir un embed
//     LEURRE générique (vidmoly placeholder) → liste noire + détection par
//     doublons (deux lecteurs distincts qui résolvent le MÊME embed).
// ===========================================================================
class FranimeProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "FRAnime"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // L'API est derrière Cloudflare : ce solveur passe par un WebView (sur appareil).
    private val cfKiller by lazy { CloudflareKiller() }
    private val mapper by lazy { ObjectMapper() }

    // -------------------------------------------------------------------------
    // Réglages : adresse du site modifiable (miroirs / changement de domaine)
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://franime.fr"
        private const val KITSU = "https://kitsu.io/api/edge"

        /** Embeds-leurres connus (placeholder servi aux clients non navigateur). */
        private val DECOY_EMBEDS = setOf(
            "https://vidmoly.biz/embed-mzyza0y0iaai.html"
        )

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "franime_settings"
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
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Adresse de FRAnime")
                .setMessage("Si le site déménage, indiquez sa nouvelle adresse (l'API suit : api.<domaine>).")
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

    /** https://api.<domaine-du-site>/api */
    private fun apiBase(): String {
        val host = Regex("^https?://([^/]+)").find(currentUrl())?.groupValues?.get(1) ?: "franime.fr"
        return "https://api.$host/api"
    }

    /** Cloudflare exige un Referer franime.fr (chemin interne) sur les endpoints de lecteurs. */
    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to currentUrl() + "/anime/watch",
        "Origin" to currentUrl()
    )

    // -------------------------------------------------------------------------
    // Accueil — listes via l'API publique Kitsu (mêmes IDs que FRAnime)
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "trending" to "Tendances",
        "airing" to "En cours de diffusion",
        "popular" to "Les plus populaires",
        "rated" to "Les mieux notés",
        "movies" to "Films d'animation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val offset = (page - 1) * 20
        val url = when (request.data) {
            "trending" -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                "$KITSU/trending/anime?limit=20"
            }
            "airing" -> "$KITSU/anime?filter%5Bstatus%5D=current&sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            "popular" -> "$KITSU/anime?sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            "rated" -> "$KITSU/anime?sort=-averageRating&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            else -> "$KITSU/anime?filter%5Bsubtype%5D=movie&sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
        }
        val items = fetchKitsuList(url)
        if (items.isEmpty()) return newHomePageResponse(request, emptyList(), false)
        val results = items.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request, results, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        // 1) Recherche dans le catalogue FRAnime lui-même (≈ 1 500 animes,
        //    titres FR inclus : « attaque des titans », « les chevaliers du
        //    zodiaque »… trouvent leur anime). L'index est construit une fois
        //    par session (parsing en streaming : pas de pic mémoire).
        //    Kitsu filter[text] se plante en effet sur les titres français.
        val fromCatalog = runCatching { searchCatalog(query) }.getOrDefault(emptyList())
        if (fromCatalog.isNotEmpty()) return fromCatalog
        // 2) Repli : recherche Kitsu (titres anglais/romaji)
        val url = "$KITSU/anime?filter%5Btext%5D=${java.net.URLEncoder.encode(query, "UTF-8")}&page%5Blimit%5D=20"
        return fetchKitsuList(url).mapNotNull { it.toSearchResponse() }
    }

    /** Index du catalogue FRAnime gardé en mémoire (une session d'app). */
    private class CatalogEntry(val id: String, val fr: String?, val en: String?, val poster: String?)

    private var catalogIndex: List<CatalogEntry>? = null
    private var catalogIndexAt = 0L

    private fun normalizeTitle(s: String): String {
        val low = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        return Regex("\\s+").replace(low, " ").trim()
    }

    /** Télécharge (1×/session) /api/animes et ne garde que id + titres + affiche. */
    private suspend fun buildCatalogIndex(): List<CatalogEntry> {
        catalogIndex?.let { return it }
        // périmé après 12 h → on re-télécharge
        val now = System.currentTimeMillis()
        val cached = catalogIndex
        if (cached != null && now - catalogIndexAt < 12 * 3600_000L) {
            return cached
        }
        val body = app.get(apiBase() + "/animes", headers = apiHeaders(), interceptor = cfKiller).text
        val entries = mutableListOf<CatalogEntry>()
        // Parsing en flux : un nœud « anime » à la fois (le JSON fait ~11 Mo,
        // readTree complet ferait un pic mémoire de ~100 Mo sur mobile).
        val parser = mapper.factory.createParser(body)
        var inArray = false
        while (true) {
            val token = parser.nextToken() ?: break
            if (token.isScalarValue) continue
            when {
                token == com.fasterxml.jackson.core.JsonToken.START_ARRAY -> inArray = true
                token == com.fasterxml.jackson.core.JsonToken.START_OBJECT && inArray -> {
                    val node: com.fasterxml.jackson.databind.JsonNode = mapper.readTree(parser)
                    val id: String = node.path("id").asText(null) ?: continue
                    if (id.isEmpty()) continue
                    val titles: com.fasterxml.jackson.databind.JsonNode? = node.get("titles")
                    fun t(field: String): String? {
                        val n = titles?.get(field) ?: return null
                        if (!n.isTextual) return null
                        return n.asText().trim().takeIf { it.isNotEmpty() }
                    }
                    val fr = t("fr_fr")
                    val en = t("en") ?: t("en_us")
                    val original = node.path("titleO").asText(null)?.trim()?.takeIf { it.isNotEmpty() }
                    if (fr == null && en == null && original == null) continue
                    val poster = node.path("affiche").asText(null)?.takeIf { it.isNotEmpty() }
                    entries += CatalogEntry(id, fr, en ?: original, poster)
                }
                else -> {}
            }
        }
        catalogIndex = entries
        catalogIndexAt = now
        return entries
    }

    private suspend fun searchCatalog(query: String): List<SearchResponse> {
        val q = normalizeTitle(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(" ").filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        // (entrée, score, titre affiché)
        val hits = mutableListOf<Triple<CatalogEntry, Int, String>>()
        for (e in buildCatalogIndex()) {
            val candidates = sequenceOf(e.fr, e.en).filterNotNull().map { normalizeTitle(it) }
            var best = 0
            var bestTitle: String? = null
            for (c in candidates) {
                if (c.isEmpty()) continue
                val allWords = words.all { w -> c.contains(w) }
                if (!allWords) continue
                val score = when {
                    c == q -> 100
                    c.startsWith(q) -> 80
                    else -> 40
                } + (if (e.fr != null && c == normalizeTitle(e.fr)) 20 else 0)
                if (score > best) { best = score; bestTitle = c }
            }
            if (best > 0) {
                val display = e.fr ?: e.en ?: continue
                hits += Triple(e, best, display)
            }
        }
        return hits.sortedByDescending { it.second }.take(25).map { (entry, _, display) ->
            newAnimeSearchResponse(display, "$mainUrl/anime/${entry.id}", TvType.Anime) {
                this.posterUrl = entry.poster
                this.otherName = entry.en?.takeIf { it != display }
            }
        }
    }

    private suspend fun fetchKitsuList(url: String): List<KitsuItem> = runCatching {
        AppUtils.parseJson<KitsuEnvelope>(
            app.get(url, headers = mapOf("Accept" to "application/vnd.api+json", "User-Agent" to USER_AGENT)).text
        ).data.orEmpty()
    }.getOrDefault(emptyList())

    private fun KitsuItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val a = attributes ?: return null
        val title = a.titles?.get("en") ?: a.titles?.get("en_jp") ?: a.canonicalTitle ?: return null
        val type = when (a.subtype) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = a.posterImage?.best()
            this.year = a.startDate?.substringBefore("-")?.toIntOrNull()
            this.otherName = a.titles?.get("en_jp")?.takeIf { it != title }
        }
    }

    // -------------------------------------------------------------------------
    // Fiche : saisons & épisodes FRAnime + métadonnées Kitsu en parallèle
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val id = url.trimEnd('/').substringAfterLast('/')

        val (seasons, kitsuAttr) = coroutineScope {
            val seasonsAsync = async(Dispatchers.IO) { runCatching { fetchSeasons(id) }.getOrNull() }
            val kitsuAsync = async(Dispatchers.IO) {
                runCatching {
                    AppUtils.parseJson<KitsuSingle>(
                        app.get("$KITSU/anime/$id", headers = mapOf("Accept" to "application/vnd.api+json", "User-Agent" to USER_AGENT)).text
                    ).data?.attributes
                }.getOrNull()
            }
            seasonsAsync.await() to kitsuAsync.await()
        }

        if (seasons.isNullOrEmpty()) {
            throw ErrorLoadingException("Cet anime n'est pas disponible sur FRAnime.")
        }

        val attr = kitsuAttr
        val title = attr?.titles?.get("en") ?: attr?.titles?.get("en_jp") ?: attr?.canonicalTitle
        ?: "Anime $id"
        val poster = attr?.posterImage?.best()
        val backdrop = attr?.coverImage?.best()
        val plot = attr?.synopsis
        val year = attr?.startDate?.substringBefore("-")?.toIntOrNull()
        val rating = attr?.averageRating?.toDoubleOrNull()
        val isMovie = attr?.subtype == "movie" ||
            (seasons.size == 1 && seasons[0].episodes.size == 1 && attr?.episodeCount == 1)

        val showStatus = when (attr?.status) {
            "current" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> null
        }

        fun episodeDataUrl(saisonIndex: Int, episodeIndex: Int, numberText: String): String =
            mainUrl + "/watch?id=" + java.net.URLEncoder.encode(id, "UTF-8") +
                "&s=$saisonIndex&e=$episodeIndex&n=" + java.net.URLEncoder.encode(numberText, "UTF-8")

        if (isMovie) {
            val (saisonIndex, episodeIndex, numberText) = with(seasons[0]) {
                Triple(index, 0, episodes[0].displayNumber())
            }
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, episodeDataUrl(saisonIndex, episodeIndex, numberText)) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it / 10.0) }
                this.comingSoon = attr?.status == "upcoming"
            }
        }

        val episodes = mutableListOf<Episode>()
        seasons.forEach { season ->
            season.episodes.forEachIndexed { episodeIndex, ep ->
                episodes += newEpisode(episodeDataUrl(season.index, episodeIndex, ep.displayNumber())) {
                    this.name = ep.title?.takeIf { it.isNotBlank() && !it.startsWith("Épisode") }
                    this.season = season.seasonNumber
                    this.episode = ep.number?.toInt() ?: (episodeIndex + 1)
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.score = rating?.let { Score.from10(it / 10.0) }
            this.showStatus = showStatus
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    /** Réponse /api/anime-seasons/{id} sous ses formes connues. */
    private data class FranimeSeason(
        val index: Int,
        val seasonNumber: Int,
        val episodes: List<FranimeEpisode>
    )

    private data class FranimeEpisode(
        val number: Double?,
        val title: String?,
        val thumbnail: String?
    ) {
        fun displayNumber(): String {
            val n = number
            return if (n != null) {
                if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
            } else {
                Regex("""(\d+(?:\.\d+)?)""").find(title ?: "")?.groupValues?.get(1) ?: "?"
            }
        }
    }

    private suspend fun fetchSeasons(animeId: String): List<FranimeSeason> {
        val body = app.get(apiBase() + "/anime-seasons/" + animeId, headers = apiHeaders(), interceptor = cfKiller).text
        val root = mapper.readTree(body)
        val out = mutableListOf<FranimeSeason>()

        // Forme principale : { episodeDetails: [{ seasonNumber, episodes: […] }] }
        root.get("episodeDetails")?.takeIf { it.isArray }?.forEachIndexed { index, season ->
            val eps = season.get("episodes")?.takeIf { it.isArray } ?: return@forEachIndexed
            val parsed = eps.mapNotNull { node ->
                FranimeEpisode(
                    number = node.get("number")?.asDouble(),
                    title = node.get("title")?.asText(),
                    thumbnail = node.get("thumbnail")?.asText()
                )
            }
            if (parsed.isNotEmpty()) {
                val sn = season.get("seasonNumber")?.asDouble()?.toInt() ?: (index + 1)
                out += FranimeSeason(index, sn, parsed)
            }
        }

        // Repli 1 : { episodes: […] } (endpoint « par saison »)
        if (out.isEmpty()) {
            root.get("episodes")?.takeIf { it.isArray }?.let { eps ->
                val parsed = eps.mapNotNull { node ->
                    FranimeEpisode(
                        number = node.get("number")?.asDouble(),
                        title = node.get("title")?.asText(),
                        thumbnail = node.get("thumbnail")?.asText()
                    )
                }
                if (parsed.isNotEmpty()) out += FranimeSeason(0, 1, parsed)
            }
        }

        // Repli 2 : objet catalogue { saisons: [{ episodes: […] }] }
        if (out.isEmpty()) {
            root.get("saisons")?.takeIf { it.isArray }?.forEachIndexed { index, season ->
                val eps = season.get("episodes")?.takeIf { it.isArray } ?: return@forEachIndexed
                val parsed = eps.mapNotNull { node ->
                    FranimeEpisode(
                        number = node.get("number")?.asDouble(),
                        title = node.get("title")?.asText(),
                        thumbnail = node.get("thumbnail")?.asText()
                    )
                }
                if (parsed.isNotEmpty()) out += FranimeSeason(index, index + 1, parsed)
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Lecture : résolution des lecteurs + extraction + docteur de liens
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        syncUrl()
        val params = parseParams(data.substringAfter("?", ""))
        val animeId = params["id"] ?: return false
        val saisonIndex = params["s"]?.toIntOrNull() ?: 0
        val episodeIndex = params["e"]?.toIntOrNull() ?: 0
        val numberText = params["n"] ?: "?"

        var found = false
        val lock = Any()
        val doctorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val doctorJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** N'émet un lien qu'une fois vérifié jouable (anti-erreurs 3003). */
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

        // ---- Étape 1 : résoudre les lecteurs (vo = VOSTFR puis vf = VF), index 0..4
        //      en parallèle — l'API répond par une URL watch2 (chiffrée) ou l'embed direct.
        data class Resolved(val lang: String, val langLabel: String, val url: String)
        val resolvedList = java.util.concurrent.CopyOnWriteArrayList<Resolved>()
        coroutineScope {
            val jobs = mutableListOf<Job>()
            for ((lang, langLabel) in listOf("vo" to "VOSTFR", "vf" to "VF")) {
                for (i in 0..4) {
                    jobs += launch(Dispatchers.IO) {
                        val embed = runCatching { resolvePlayer(animeId, saisonIndex, episodeIndex, lang, i) }
                            .getOrNull() ?: return@launch
                        resolvedList += Resolved(lang, langLabel, embed)
                    }
                }
            }
            jobs.forEach { it.join() }
        }
        val resolved = resolvedList.toList()

        // Anti-leurre : un embed de la liste noire, ou deux lecteurs d'une même
        // langue qui résolvent le MÊME embed → placeholder générique → langue ignorée.
        val usable = mutableListOf<Resolved>()
        for ((lang, langLabel) in listOf("vo" to "VOSTFR", "vf" to "VF")) {
            val forLang = resolved.filter { it.lang == lang }.distinctBy { it.url }
            if (forLang.isEmpty()) continue
            if (forLang.any { it.url.trimEnd('/').lowercase() in DECOY_EMBEDS }) continue
            if (forLang.size > 1 && forLang.all { it.url == forLang.first().url }) continue
            usable += forLang
        }

        // ---- Étape 2 : extraction en parallèle sur les embeds retenus
        val semaphore = Semaphore(6)
        coroutineScope {
            usable.map { r ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val label = playerLabelFromUrl(r.url)
                        val name = "FRAnime · $label · ${r.langLabel}"
                        // Flux direct (lecteur « vido », liens .mp4/.m3u8 nus)
                        if (directStreamRegex.matches(r.url)) {
                            emit(
                                newExtractorLink(name, name, r.url) {
                                    this.referer = currentUrl()
                                    this.quality = Qualities.Unknown.value
                                    this.type = if (".m3u8" in r.url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                            return@async
                        }
                        var produced = 0
                        runCatching {
                            loadExtractor(r.url, currentUrl(), { sub ->
                                synchronized(lock) { subtitleCallback(sub) }
                            }, { link ->
                                produced++
                                emit(relabel(link, r.langLabel))
                            })
                        }
                        // Secours générique si l'extracteur n'a rien donné
                        if (produced == 0) {
                            runCatching {
                                genericExtract(
                                    r.url, label, r.langLabel,
                                    { sub -> synchronized(lock) { subtitleCallback(sub) } },
                                    { link -> emit(link) }
                                )
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

    /**
     * /api/anime/{id}/{saisonIndex}/{episodeIndex}/{lang}/{lecteurIndex}
     * → corps = URL franime.fr/watch2/?a=…&b=… (chiffrée) OU embed direct.
     */
    private suspend fun resolvePlayer(
        animeId: String,
        saisonIndex: Int,
        episodeIndex: Int,
        lang: String,
        lecteurIndex: Int
    ): String? {
        val url = "${apiBase()}/anime/$animeId/$saisonIndex/$episodeIndex/$lang/$lecteurIndex"
        val body = runCatching {
            app.get(url, headers = apiHeaders(), interceptor = cfKiller).text
        }.getOrNull() ?: return null
        var target = body.trim().trim('"')
        // Certaines réponses sont JSON : { "url": "…" } / { "iframe": "…" }
        if (target.startsWith("{")) {
            target = Regex(""""(?:url|iframe)"\s*:\s*"([^"]+)"""")
                .find(target)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        }
        if (!target.startsWith("http")) return null
        if ("/watch2" !in target) return target
        // L'URL watch2 contient parfois déjà l'embed (paramètre b) ; sinon on la
        // suit (302) et on décode l'URL finale.
        return decodeWatch2(target) ?: runCatching {
            val resp = app.get(target, headers = apiHeaders(), interceptor = cfKiller)
            decodeWatch2(resp.url)
        }.getOrNull()
    }

    /**
     * Décode un paramètre de l'URL watch2 : base64 → hex → XOR (clé 1 octet).
     * Brute-force les 256 clés — la bonne donne une URL ASCII commençant par http.
     */
    private fun decodeWatch2(watchUrl: String): String? {
        val query = watchUrl.substringAfter("?", "")
        if (query.isBlank()) return null
        for (pair in query.split("&")) {
            val raw = pair.substringAfter("=", "")
            if (raw.isBlank()) continue
            val b64 = runCatching {
                java.net.URLDecoder.decode(raw, "UTF-8")
            }.getOrDefault(raw)
            val hex = runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            }.getOrNull() ?: continue
            if (hex.length < 8 || hex.length % 2 != 0) continue
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) continue
            val bytes = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            // Clé 1 d'abord (la plus courante), puis le reste en brute-force
            val keyOrder = sequenceOf(1) + (0..255).asSequence().filter { it != 1 }
            for (key in keyOrder) {
                val out = StringBuilder(bytes.size)
                var printable = true
                for (b in bytes) {
                    val c = ((b.toInt() and 0xFF) xor key)
                    if (c < 0x20 || c > 0x7E) { printable = false; break }
                    out.append(c.toChar())
                }
                if (!printable) continue
                val candidate = out.toString()
                if (candidate.startsWith("http") && "franime.fr" !in candidate) return candidate
            }
        }
        return null
    }

    /** Étiquette lisible d'un lecteur déduite de l'URL embed. */
    private fun playerLabelFromUrl(url: String): String {
        val u = url.lowercase()
        return when {
            "sibnet" in u -> "Sibnet"
            "sendvid" in u -> "SendVid"
            "vidmoly" in u -> "VidMoly"
            "filemoon" in u || "moonplayer" in u -> "FileMoon"
            "uqload" in u -> "Uqload"
            "oneupload" in u -> "OneUpload"
            "vidoza" in u -> "Vidoza"
            "streamtape" in u -> "Streamtape"
            "dood" in u -> "Dood"
            "vk" in u -> "VK"
            "vido" in u -> "Vido"
            else -> Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)?.removePrefix("www.") ?: "Lecteur"
        }
    }

    /** Paramètres de l'URL de données (?id=…&s=…&e=…&n=…). */
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
    // Docteur de liens : chaque lien est VÉRIFIÉ jouable avant d'être affiché
    // (petite requête Range) — élimine les erreurs ExoPlayer 3003 « container
    // unsupported » des liens morts, des pages HTML et des fichiers mal typés.
    // En cas de doute (timeout, réseau), le lien est conservé.
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
                // 403 (géoblocage / anti-bot incertain), 416 (Range mal géré) et
                // 429 (limite momentanée) → on garde le lien par prudence ;
                // 404/410/5xx → vraiment mort → on le retire.
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
        true // doute réseau → on garde le lien
    }

    /** Ré-étiquette un lien avec sa langue (« Sibnet · VOSTFR ») pour l'utilisateur. */
    private fun relabel(link: ExtractorLink, langLabel: String): ExtractorLink {
        return ExtractorLink(
            link.source,
            "${link.name} · $langLabel",
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
    // Fallback générique pour les hébergeurs sans extracteur intégré
    // -------------------------------------------------------------------------
    private suspend fun genericExtract(
        embedUrl: String,
        label: String,
        langLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(
            embedUrl,
            referer = currentUrl(),
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
        // Lecteurs videojs obfusqués (vidzy.cc, fsvid.lol…) : src = XOR(base64, hostname)
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
                val name = "FRAnime · $label · $langLabel"
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

    /**
     * Décode les sources videojs obfusquées « (function(s){var h=…})("base64") »
     * utilisées par vidzy.cc, fsvid.lol… : base64 → inversion → XOR avec une
     * clé dérivée du hostname de la page d'embed.
     */
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

// ===========================================================================
// Modèles Kitsu (API publique — mêmes IDs que FRAnime)
// ===========================================================================
private data class KitsuEnvelope(
    @JsonProperty("data") val data: List<KitsuItem>? = null
)

private data class KitsuSingle(
    @JsonProperty("data") val data: KitsuItem? = null
)

private data class KitsuItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("attributes") val attributes: KitsuAttributes? = null
)

private data class KitsuAttributes(
    @JsonProperty("canonicalTitle") val canonicalTitle: String? = null,
    @JsonProperty("titles") val titles: Map<String, String>? = null,
    @JsonProperty("posterImage") val posterImage: KitsuImage? = null,
    @JsonProperty("coverImage") val coverImage: KitsuImage? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("subtype") val subtype: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("averageRating") val averageRating: String? = null,
    @JsonProperty("startDate") val startDate: String? = null
)

private data class KitsuImage(
    @JsonProperty("small") val small: String? = null,
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("original") val original: String? = null
) {
    fun best(): String? = large ?: medium ?: small ?: original
}
