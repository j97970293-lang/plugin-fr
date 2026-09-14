package com.cinestream

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.ExtractorApi
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
class CineStreamPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        CineStreamProvider.appContext = context.applicationContext
        registerMainAPI(CineStreamProvider())
        openSettings = { ctx -> CineStreamProvider.showSettings(ctx) }
    }
}

class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Les 16 miroirs (vidaraw.com, vidaraa.cc…) partagent la même API et les
        // mêmes filecodes : on interroge le domaine du lien lui-même.
        val base = Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: mainUrl
        val fileCode = url.substringAfterLast("/").substringBefore("?")
        val res = runCatching {
            app.post(
                "$base/api/stream",
                json = mapOf("filecode" to fileCode, "device" to "web"),
                referer = base
            ).text
        }.getOrNull() ?: return
        val stream = Regex("\"streaming_url\"\\s*:\\s*\"([^\"]+)\"")
            .find(res)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        if (!stream.startsWith("http")) return
        callback(
            newExtractorLink(name, name, stream) {
                this.type = if (".m3u8" in stream) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.referer = base
            }
        )
        // Sous-titres : le champ vaut parfois la chaîne "None" — on ne parse que les vrais tableaux
        val arr = Regex("\"subtitles\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(res)?.groupValues?.get(1) ?: return
        val paths = Regex("\"file_path\"\\s*:\\s*\"([^\"]+)\"").findAll(arr)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        val langs = Regex("\"language\"\\s*:\\s*\"([^\"]+)\"").findAll(arr)
            .map { it.groupValues[1] }.toList()
        paths.zip(langs).forEach { (path, lang) ->
            subtitleCallback(SubtitleFile(lang, path))
        }
    }

    companion object {
        /** Famille « StreamUp » : même API /api/stream et mêmes filecodes partout (vérifié). */
        private val domains = listOf(
            "vidara.to", "vidaraa.cc", "vidaraw.com", "vidarax.cc", "vidara.so",
            "vidavaca.net", "vidaarax.net", "vidaarax.com", "vidaratem.com",
            "odysseusa.cc", "handfacesnap.cc", "namefacesnap.cc", "thebesthosterv.com",
            "vidmatrixa.com", "vidchampions.com", "antarcticadocs.com", "nameitweb.com"
        )

        fun isVidara(url: String): Boolean {
            val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return false
            return host in domains
        }
    }
}

// ===========================================================================
// CineStream (cinestream.info) — films FR (Next.js SSR, affiches TMDB)
//   · Listes paginées : /film-en-streaming/{n}, /films-ajoutes-recemment/{n},
//     /films-populaires/{n}, /films/{Genre}/{n} (16 genres), /annee/{YYYY}/{n}.
//   · Recherche SSR : /search?q=…
//   · Fiche : /film/{slug} → boutons lecteurs (≈15 : Vidara, Voe, LuLuTV,
//     Vidsonic, FMX, Hxfile, DdStream, Save, uqload, Vmoly, Filelions,
//     Swish, vostfr 1-3) + tmdbid dans le payload RSC.
//   · Lecteurs : /player/{tmdbid}/{index} → <iframe src="URL hébergeur">.
//   · Films uniquement (pas de séries sur ce site).
// ===========================================================================
class CineStreamProvider : MainAPI() {

    override var mainUrl = DEFAULT_URL
    override var name = "CineStream"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie)

    // -------------------------------------------------------------------------m
    // Réglages
    // -------------------------------------------------------------------------
    companion object {
        const val DEFAULT_URL = "https://cinestream.info"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "cinestream_settings"
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
                .setTitle("Adresse de CineStream")
                .setMessage("Si le site déménage, indiquez sa nouvelle adresse.")
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
    override val mainPage = mainPageOf(
        "films" to "Films (derniers)",
        "recents" to "Ajoutés récemment",
        "populaires" to "Films populaires",
        "action" to "Action",
        "animation" to "Animation",
        "aventure" to "Aventure",
        "comedie" to "Comédie",
        "sf" to "Science-Fiction",
        "horreur" to "Horreur",
        "thriller" to "Thriller"
    )

    private fun pageUrl(name: String, page: Int): String {
        val path = when (name) {
            "recents" -> "/films-ajoutes-recemment/$page"
            "populaires" -> "/films-populaires/$page"
            "action" -> "/films/Action/$page"
            "animation" -> "/films/Animation/$page"
            "aventure" -> "/films/Aventure/$page"
            "comedie" -> "/films/Com%C3%A9die/$page"
            "sf" -> "/films/Science-Fiction/$page"
            "horreur" -> "/films/Horreur/$page"
            "thriller" -> "/films/Thriller/$page"
            else -> "/film-en-streaming/$page"
        }
        return currentUrl() + path
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncUrl()
        val html = runCatching {
            app.get(pageUrl(request.name, page), headers = baseHeaders).text
        }.getOrNull() ?: return newHomePageResponse(request, emptyList(), false)
        val items = parseCards(html)
        // 24 cartes par page sur ce site — en dessous, c'est la fin de la liste.
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncUrl()
        val html = runCatching {
            app.get(
                currentUrl() + "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"),
                headers = baseHeaders
            ).text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    /**
     * Cartes : <a href="/film/{slug}"> … <img alt="Affiche du film {titre} en
     * streaming en {langue} - {qualité}" src="https://image.tmdb.org/t/p/…">
     */
    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        Regex(
            """<a href="(/film/([a-z0-9-]+))">\s*<div[^>]*>\s*<img[^>]+alt="([^"]*)"[^>]*src="(https://image\.tmdb\.org/t/p/[^"]+)"""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).forEach { m ->
            val url = currentUrl() + m.groupValues[1]
            val alt = m.groupValues[3]
            val poster = m.groupValues[4]
            var title = alt.substringAfter("Affiche du film ", alt).substringBefore(" en streaming").trim()
            if (title.isBlank()) title = m.groupValues[2].replace('-', ' ')
            if (title.isBlank()) return@forEach
            out += newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
        return out.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Fiche
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        syncUrl()
        val html = runCatching {
            app.get(url, headers = baseHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException("Fiche CineStream inaccessible")

        // Titre : <meta property="og:title" content="Film {titre} {année} en Streaming">
        val ogTitle = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Fiche CineStream illisible")
        var title = ogTitle
            .removePrefix("Film ")
            .replace(Regex("""\s+en\s+Streaming\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val year = Regex("""\b((?:19|20)\d{2})\s*$""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (year != null) title = title.removeSuffix(year.toString()).trim().trimEnd('(', ')').trim()

        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""Synopsis du film</h3>\s*<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { org.jsoup.Jsoup.parse(it).text()?.trim() }

        val dataUrl = url // la fiche sert de data : loadLinks la recharge pour les lecteurs
        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
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
        val ficheUrl = data.substringBefore("?")
        val html = runCatching { app.get(ficheUrl, headers = baseHeaders).text }.getOrNull()
            ?: return false

        // tmdbid caché dans le payload RSC (\"tmdbid\":1368337)
        val tmdbId = Regex("""tmdbid[\\"]*:+(\d+)""").find(html)?.groupValues?.get(1)
            ?: return false
        // boutons lecteurs : <button id="Vidara" aria-label="Lecteur Vidara pour …">
        val players = Regex("""<button id="([^"]+)" aria-label="Lecteur""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        if (players.isEmpty()) return false

        var found = false
        val semaphore = Semaphore(5)
        coroutineScope {
            players.mapIndexed { idx, label ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val lang = if (label.contains("vostfr", true)) "VOSTFR" else null
                        var produced = 0
                        // /player/{tmdbid}/{index} → <iframe src="URL hébergeur">
                        val playerUrl = "$mainUrl/player/$tmdbId/$idx"
                        val page = runCatching {
                            app.get(playerUrl, referer = ficheUrl, headers = baseHeaders).text
                        }.getOrNull() ?: return@async
                        val embed = Regex("""<iframe[^>]*src="([^"]+)"""").find(page)?.groupValues?.get(1)
                            ?: return@async
                        if (!embed.startsWith("http")) return@async
                        val name = "CineStream · $label" + (lang?.let { " · $it" } ?: "")
                        if (Vidara.isVidara(embed)) {
                            runCatching {
                                Vidara().getUrl(embed, mainUrl, { sub -> synchronized(Any()) { subtitleCallback(sub) } }) { link ->
                                    produced++
                                    callback(relabel(link, name))
                                }
                            }
                        } else {
                            runCatching {
                                loadExtractor(embed, ficheUrl, { sub -> synchronized(Any()) { subtitleCallback(sub) } }) { link ->
                                    produced++
                                    callback(relabel(link, name))
                                }
                            }
                        }
                        if (produced == 0) {
                            runCatching {
                                genericExtract(embed, name, { sub -> synchronized(Any()) { subtitleCallback(sub) } }) { link ->
                                    callback(link)
                                }
                            }
                        }
                        if (produced > 0) found = true
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
        return found
    }

    /** Renomme un lien extrait avec le libellé du bouton du site. */
    private fun relabel(link: ExtractorLink, name: String): ExtractorLink = ExtractorLink(
        name, name, link.url, link.referer, link.quality,
        link.headers, link.extractorData, link.type, link.audioTracks
    )

    // -------------------------------------------------------------------------
    // Extraction générique de secours (identique aux autres extensions du dépôt)
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
