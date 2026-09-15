package com.telefrance

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

// ===========================================================================
// Télé FR Direct — chaînes de télévision françaises en direct
// ===========================================================================
// Source : playlists M3U publiques du projet iptv-org (GitHub Pages, aucune
// protection). Liste France par défaut :
//   https://iptv-org.github.io/iptv/countries/fr.m3u  (~215 chaînes)
// Chaque entrée #EXTINF porte le nom, le logo (tvg-logo) et le groupe
// (group-title : General, News, Movies, Series, Kids, Sports, Documentary,
// Music, Entertainment, Animation). Le flux est un HLS public (m3u8).
//  · Chaînes [Geo-blocked] / [Not 24/7] et noms parasites filtrés.
//  · L'adresse de la playlist est modifiable dans les réglages ⚙ — n'importe
//    quelle liste M3U compatible fonctionne (ex. Madagascar :
//    https://iptv-org.github.io/iptv/countries/mg.m3u).
// ===========================================================================

/**
 * Point d'entrée du plugin — c'est CETTE classe que CloudStream charge.
 */
@CloudstreamPlugin
class TeleFrancePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        TeleFranceProvider.appContext = context.applicationContext
        registerMainAPI(TeleFranceProvider())
        openSettings = { ctx -> TeleFranceProvider.showSettings(ctx) }
    }
}

class TeleFranceProvider : MainAPI() {

    companion object {
        const val DEFAULT_PLAYLIST = "https://iptv-org.github.io/iptv/countries/fr.m3u"

        @Volatile
        var appContext: android.content.Context? = null

        private const val PREFS_NAME = "telefrance_settings"
        private const val PREF_URL = "playlist_url"

        fun currentPlaylist(): String = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                ?.getString(PREF_URL, null)
                ?.trim()?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: DEFAULT_PLAYLIST

        fun setPlaylistUrl(context: android.content.Context, url: String?) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_URL, url?.trim()?.takeIf { it.startsWith("http") })
                .apply()
        }

        fun showSettings(context: android.content.Context) {
            val input = android.widget.EditText(context).apply {
                setText(currentPlaylist()); hint = "https://…/liste.m3u"
            }
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            val layout = android.widget.LinearLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("Liste de chaînes (M3U)")
                .setMessage(
                    "Adresse de la playlist M3U à utiliser.\n\n" +
                        "Par défaut : chaînes françaises (iptv-org).\n" +
                        "Autre exemple — Madagascar :\n" +
                        "https://iptv-org.github.io/iptv/countries/mg.m3u"
                )
                .setView(layout)
                .setPositiveButton("Enregistrer") { _, _ -> setPlaylistUrl(context, input.text.toString()) }
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Par défaut") { _, _ -> setPlaylistUrl(context, DEFAULT_PLAYLIST) }
                .show()
        }
    }

    override var mainUrl = "https://iptv-org.github.io"
    override var name = "Télé FR Direct"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Live)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    // -------------------------------------------------------------------------
    // Données
    // -------------------------------------------------------------------------
    data class Channel(
        val rawName: String,   // nom brut de la playlist (« France 24 (1080p) »)
        val name: String,      // nom nettoyé (« France 24 »)
        val logo: String?,
        val groups: List<String>,
        val url: String
    )

    /** Cache de la playlist (10 min). */
    private var playlistCache: Pair<Long, List<Channel>>? = null

    private suspend fun channels(): List<Channel> {
        playlistCache?.let { (ts, list) ->
            if (System.currentTimeMillis() - ts < 600_000L && list.isNotEmpty()) return list
        }
        val text = runCatching {
            app.get(currentPlaylist(), headers = baseHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException(
            "Playlist inaccessible — vérifiez votre connexion ou changez l'adresse dans les réglages (⚙)."
        )
        val out = mutableListOf<Channel>()
        var pending: Triple<String, String?, List<String>>? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val rawName = line.substringAfterLast(',').trim()
                    val logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1)
                        ?.takeIf { it.startsWith("http") }
                    val groups = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1)
                        ?.split(';')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    pending = Triple(rawName, logo, groups)
                }
                line.startsWith("http") && pending != null -> {
                    val (rawName, logo, groups) = pending!!
                    pending = null
                    // filtres qualité : flux géo-bloqués, non permanents, noms parasites
                    if ("[Geo-blocked]" in rawName || "[Not 24/7]" in rawName) return@forEach
                    if ("Chrome/" in rawName || "Mozilla" in rawName || "like Gecko" in rawName) return@forEach
                    val clean = rawName
                        .replace(Regex("""\s*\[\d+x\d+\]"""), "")
                        .replace(Regex("""\s*\((?:\d{3,4}p|SD|HD|FHD|UHD)\)"""), "")
                        .trim()
                    if (clean.isBlank()) return@forEach
                    out += Channel(rawName, clean, logo, groups, line)
                }
            }
        }
        if (out.isEmpty()) throw ErrorLoadingException(
            "Aucune chaîne trouvée dans la playlist — adresse correcte ? (⚙ pour la modifier)"
        )
        playlistCache = System.currentTimeMillis() to out
        return out
    }

    /** group-title iptv-org → clé de section (premier groupe reconnu). */
    private val groupKeys = linkedMapOf(
        "General" to "general", "News" to "info", "Movies" to "cinema",
        "Series" to "series", "Entertainment" to "divertissement", "Animation" to "animation",
        "Kids" to "enfants", "Sports" to "sport", "Documentary" to "documentaires",
        "Music" to "musique", "Travel" to "voyages", "Business" to "info",
        "Culture" to "documentaires", "Family" to "enfants", "Comedy" to "divertissement",
        "Outdoor" to "documentaires", "Religious" to "divers", "Auto" to "divertissement",
        "Legislative" to "info", "Weather" to "info", "Science" to "documentaires",
        "Education" to "documentaires", "Shop" to "divers", "Classic" to "cinema",
        "Lifestyle" to "divertissement", "Food" to "divertissement"
    )

    private fun sectionFor(ch: Channel): String =
        ch.groups.firstNotNullOfOrNull { groupKeys[it.trim()] } ?: "divers"

    // -------------------------------------------------------------------------
    // Accueil
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "general" to "📺 Généralistes",
        "info" to "📰 Info & Actualité",
        "cinema" to "🎬 Cinéma",
        "series" to "🎞️ Séries",
        "divertissement" to "🎭 Divertissement",
        "animation" to "✨ Animation",
        "enfants" to "🧒 Enfants",
        "sport" to "⚽ Sport",
        "documentaires" to "🌍 Documentaires",
        "musique" to "🎵 Musique",
        "divers" to "📡 Divers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), false)
        val list = channels().filter { sectionFor(it) == request.data }
        val items: List<SearchResponse> = list.map { ch ->
            newLiveSearchResponse(ch.name, "$mainUrl/channel/${ch.hashCode()}") {
                this.posterUrl = ch.logo
            }
        }
        return newHomePageResponse(request, items, hasNext = false)
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return channels().filter { it.name.lowercase().contains(q) }.map { ch ->
            newLiveSearchResponse(ch.name, "$mainUrl/channel/${ch.hashCode()}") {
                this.posterUrl = ch.logo
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fiche = chaîne
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfterLast('/').toIntOrNull()
            ?: throw ErrorLoadingException("Chaîne introuvable")
        val ch = channels().firstOrNull { it.hashCode() == key }
            ?: throw ErrorLoadingException("Chaîne introuvable dans la playlist — rechargez l'accueil.")
        return newLiveStreamLoadResponse(ch.name, url, ch.url) {
            this.posterUrl = ch.logo
            this.plot = "Chaîne en direct — flux ${ch.rawName}" +
                (ch.groups.firstOrNull()?.let { " · $it" } ?: "")
        }
    }

    // -------------------------------------------------------------------------
    // Lecture : le flux HLS public est joué directement
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        callback(
            newExtractorLink(name, "Direct", data) {
                this.type = ExtractorLinkType.M3U8
            }
        )
        return true
    }
}
