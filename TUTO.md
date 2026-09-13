# TUTO — Créer des extensions CloudStream & analyser des sites de streaming

> **Document écrit pour être lu par une IA** (ou un humain). Il synthétise l'expérience
> réelle de création de 4 extensions CloudStream francophiones (AnimoFlix, Zenix,
> WaveWatch, Afterdark) et de l'analyse reverse-engineering de leurs sites.
> Chaque règle ci-dessous a été **vérifiée par des requêtes réelles** — pas de théorie.
> Structure : règles explicites, URL exactes, pièges constatés, code minimal.
> Si vous êtes une IA chargée de créer/maintenir une extension : lisez tout, appliquez
> les checklists, et **ne jamais supposer — testez**.

---

## 1. RÈGLES FONDAMENTALES (à ne jamais violer)

1. **Toujours vérifier par requête réelle.** curl/python depuis un terminal, jamais
   « ça devrait marcher ». Un site qui répond 200 peut être vide, soft-404, ou servir
   du contenu différent selon l'épisode demandé.
2. **Un lien doit être spécifique au contenu demandé.** Avant d'intégrer une source,
   comparer sa réponse pour 2 épisodes différents (ex. S02E01 vs S02E02) : si les
   URLs sont identiques → la source est **pourrie**, on la retire ou on la filtre.
3. **Une page inexistante peut répondre 200.** (soft-404 : animoflix.to redirige en
   douce vers l'épisode 1 ; zeus renvoie des liens génériques même pour un épisode
   fictif E9999). → Toujours vérifier un marqueur de contenu (titre contenant
   « Épisode {n} », id, etc.).
4. **Ne jamais casser ce qui marche.** Un fournisseur qui marche à 90 % est mieux
   qu'une réécriture risquée. Corrections ciblées, versions incrémentées (v1→v2→v3).
5. **Le datacenter n'est pas l'appareil de l'utilisateur.** Cloudflare bloque souvent
   les IPs datacenter (403) alors que l'appareil réel passe. Un embed 403 depuis un
   serveur peut être utile quand même → best-effort. L'inverse est faux : un embed
   mort (000/404/dns) est mort partout.
6. **Langue affichée sur chaque lien** quand elle est connue (« Filemoon · VOSTFR ») :
   énorme gain d'UX, tri possible (VOSTFR d'abord pour un site VOSTFR, VF d'abord
   pour un site VF).

---

## 2. PIPELINE DE CRÉATION D'EXTENSION CLOUDSTREAM

### 2.1 Outillage

- Modèle : https://github.com/recloudstream/cloudstream (template gradle).
- JDK 21 + Android SDK (platforms;android-35, build-tools;35.0.0).
- Build : `./gradlew make makePluginsJson --no-daemon` → un `.cs3` par module
  `XxxProvider/` + `build/plugins.json`.
- RAM limitée : tuer `[K]otlinCompileDaemon` avant un rebuild si OOM.
- CI GitHub : workflow qui copie les `.cs3` + `plugins.json` sur la branche `builds/`.

### 2.2 Anatomie d'un provider (Kotlin) — le minimum vital

```kotlin
@CloudstreamPlugin
class MonPlugin : Plugin() {                     // Plugin (avec Context), pas BasePlugin
    override fun load(context: Context) {
        MonProvider.appContext = context.applicationContext   // pour les réglages
        registerMainAPI(MonProvider())
        registerExtractorAPI(MonExtracteur())    // extracteur maison éventuel
        openSettings = { ctx -> MonProvider.showSettings(ctx) }  // bouton Réglages
    }
}

class MonProvider : MainAPI() {
    override var mainUrl = "https://exemple.fr"
    override var name = "Exemple"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("/chemin" to "🔥 Section", ...) // chemins SANS domaine

    override suspend fun getMainPage(page, request) : HomePageResponse  // catalogue
    override suspend fun search(query): List<SearchResponse>
    override suspend fun load(url): LoadResponse        // fiche + épisodes
    override suspend fun loadLinks(data, isCasting, subtitleCallback, callback): Boolean
}
```

APIs clés (vérifiées par javap sur cloudstream.jar) :
- `newMovieSearchResponse/newTvSeriesSearchResponse/newAnimeSearchResponse(title, url, type){}`
- `newEpisode(url){ season, episode, name, posterUrl, description, score, runTime }`
  ⚠ champ **`runTime`** (pas `duration`) pour les épisodes ; `duration` existe sur les films.
- `newMovieLoadResponse/newTvSeriesLoadResponse`, `Score.from10(double)`.
- `loadExtractor(url, referer, subtitleCallback, linkCallback)` → extracteurs intégrés
  (Filemoon, Uqload, Vidmoly, **SibNet**, Doodstream… des centaines) + vos extracteurs
  enregistrés. Renvoie le nombre de liens produits via le callback.
- `app.get(url, headers=, params=, referer=, interceptor=cfKiller)` : client HTTP
  suspend ; `CloudflareKiller()` résout les challenges CF via WebView **sur l'appareil**.
- `newExtractorLink(source, name, url){ quality, type, referer, headers }` ;
  `ExtractorLinkType.M3U8 / VIDEO / DASH`.
- Plugin settings : étendre `Plugin()` (pas `BasePlugin`), surcharger
  `load(context: Context)`, affecter `openSettings = { ctx -> ... }` → un bouton
  « Réglages » apparaît sur la fiche de l'extension. Stocker l'URL dans
  `SharedPreferences`, la relire à CHAQUE requête (`syncUrl()` en tête de
  getMainPage/search/load/loadLinks) pour que le changement prenne effet sans recharger.

### 2.3 Pattern « plein de serveurs » (prouvé sur 4 extensions)

```
loadLinks(data) :
  1. parser l'id du contenu + saison/épisode depuis data
  2. lancer EN PARALLÈLE (coroutineScope + async(Dispatchers.IO)) :
       - N agrégateurs (chacun dans runCatching → emptyList en cas d'échec)
       - extracteur HLS direct (1embed) → callback immédiat
       - secours spécialisé (ex. animoflix pour les animes)
  3. dédupliquer par URL (LinkedHashMap), trier par (priorité langue ×10 + priorité origine)
  4. liens « direct=true » (playlists .m3u8) → émettre immédiatement
  5. les autres → loadExtractor (Sémaphore(6) pour la parallélisation), et si
     produced == 0 → extraction générique de secours (regex og:video, <source>,
     file:…, .m3u8/.mp4/.webm) avec FILTRE ANTI-JUNK (images, pubs, youtube, .srt…)
```

---

## 3. ANALYSER UN SITE DE STREAMING (méthode de recon)

### 3.1 Ordre d'attaque

1. **Ouvrir le site** (curl, UA navigateur). Noter : statut, CF ?, SSR (données dans
   le HTML) ou CSR (rendu JS → chercher les API).
2. **Si SSR PHP classique** (Zenix) : parser le HTML directement (Jsoup). Chercher
   les endpoints `ajax/` (souvent un JSON de recherche : `zenix.best/ajax/search/suggest?q=`).
3. **Si SPA/bundles JS** (Afterdark : React+TanStack) :
   - télécharger les bundles (`index-*.js`, `src-*.js`) listés dans le HTML ;
   - `grep` les routes API (`/api/...`), les mots-clés (`m3u8`, `sources`, `referer`,
     `token`, `proof`), les URLs d'embeds de secours ;
   - reconstituer le flow : beaucoup de sites ont UNE API cachée `POST /api/sources
     {token, titleKey}` protégée par **Cloudflare Turnstile** → preuve signée exigée
     (header `x-nabi-proof`), impossible à forger sans navigateur réel. NE PAS
     INSISTER : pivoter vers les lecteurs de secours du site (souvent publics).
4. **Tester les gates** : proof absent/invalide → 403 ? POST avec token bidon →
   `{ok:false}` ? Alors abandonner cette voie (vérifié sur Afterdark).
5. **Chercher les fallbacks publics** : les bundles contiennent presque toujours des
   lecteurs externes de secours (videasy, frembed, peachify…) avec leurs URLs
   exactes + paramètres → ce sont des sources LÉGITIMES (le site lui-même les utilise).

### 3.2 Recon d'un embed public (lecteurs TMDB-keyed)

Format quasi universel : `https://lecteur.tld/embed/tv/{tmdb}/{saison}/{épisode}`
ou `/embed/movie/{tmdb}`. Test systématique :
```
pour chaque lecteur :
  GET /embed/movie/{id connu}       → 200 + contenu ?
  GET /embed/movie/{id bidon}       → différence ? (sinon soft-404 constant)
  depuis datacenter ET si possible avec referer du site
```
Lecteurs vivants (testés 2026-09) : player.videasy.net, vidfast.pro,
vidsrc.wtf/api/{2|3}/tv|movie, 2embed.cc/embedtv, 111movies.com, braflix.win,
vidking.net, frembed.skin, 1embed.cc, peachify.top & vidsrc.cc (CF: 403 datacenter,
OK appareil réel). Morts : vidsrc.xyz/.icu, moviesapi.club, vidora.su,
multiembed.mov, smashy, anyembed. **Re-tester avant chaque publication** — ça meurt vite.

### 3.3 Analyser un lecteur JS (1embed.cc — cas réel)

1. `curl https://1embed.cc/embed/movie/27205` → HTML Next.js.
2. Le payload contient (échappé `\"` et `\u0026`) : soit l'ancien format
   `{"name":"Solari","url":"/v/solari_x.m3u8","type":"hls"}`, soit le nouveau
   `{"name":"Necro","url":"https://abdx.tv/hls-proxy?url=<enc>&referer=<enc>","type":"hls"}`.
3. Regex `""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""` + un-escape.
4. **Vérifier la jouabilité** du proxy : GET direct → `application/vnd.apple.mpegurl`
   + `#EXTM3U` = bon. Le proxy gère lui-même le referer → utiliser l'URL telle quelle.
   Les formats CHANGENT (1embed a changé en sept. 2026) → écrire les extracteurs
   pour supporter ancien ET nouveau format.

---

## 4. LA STACK « AGRÉGATEURS TMDB » (sources multi-serveurs)

Tous sont keyés par ID TMDB — réutilisables pour N'IMPORTE QUEL site de streaming
ayant un catalogue TMDB. URLs exactes (testées 2026-09) :

| Source | URL | Format | Fiabilité épisode (test E01 vs E02) |
|---|---|---|---|
| apiwiflix | `apis.wavewatch.top/apiwiflix.php?id={tmdb}&season={s}&episode={e}` | `allSources=[{url,name,language}]` dans le HTML | ✅ 100 % spécifique |
| zeus (SSE) | `apis.wavewatch.top/zeus.php?sse&type=tv&id={s}&s={s}&e={e}` | NDJSON `data: {"sources":[{url,quality,lang,format,iframe,premium}]}` | ✅ spécifique (blinkflux/cinefuse passent s/e dans l'URL) |
| movix | `api.movix.cash/api/tmdb/tv/{id}?season=&episode=` \| `/movie/{id}` | JSON `player_links[{decoded_url,quality,language}]` | ✅ 100 % spécifique |
| fstream | `api.movix.cash/api/fstream/movie/{id}` | JSON `players{VF:[{url,player}],VOSTFR:…}` (films) | ✅ (films seulement, TV=404) |
| playerix | `apis.wavewatch.top/playerix.php?type=tv&id={s}&season=&episode=` | boutons `<button data-url="…u={base64url}">` + `data-fmt="m3u8"` + `data-alive` | ⚠️ **TV : seuls les boutons `data-fmt="m3u8"` sont spécifiques** ; les iframes sont IDENTIQUES pour tous les épisodes → filtrer ! Films : tout bon |
| mouve | `apis.wavewatch.top/mouve.php?json=1&type=&id=&s=&e=` | JSON `streams[{url,quality,lang,format,iframe}]` | ⚠️ **TV : ~2/3 des liens identiques entre épisodes → films seulement** |

Règles :
- Langues des agrégateurs : `vf/vff/vfq/vo/vostfr/multi` (apiwiflix : `language` ;
  zeus : `lang` ; playerix : badge `class="lang"` → dernier mot du texte).
- 404 anti-rafale TRANSITOIRES si requêtes parallèles ×6 → re-tester en séquentiel ;
  dans le code, `runCatching` + redondance suffisent (ne pas « réparer »).
- Le décodage playerix : base64 **URL_SAFE** du param `u=` de `data-url`.

### 4.1 Le cas particulier des ANIMES LONGS (One Piece & co)

**Problème constaté** : TMDB numérote One Piece en ABSOLU par saison
(S23 = E1156..E1181), les agrégateurs ne l'ont pas du tout (« Serie non trouvee »),
et les conventions divergent partout.

**Solution éprouvée — secours animoflix.to** :
1. animoflix.to a les derniers épisodes VOSTFR (hébergeurs : ansembed.net = HLS
   JWPlayer, video.sibnet.ru = extracteur SibNet intégré à CloudStream).
2. Autocomplétion : `animoflix.to/search-autocomplete.php?q={titre}` →
   `[{title, slug}]`. Match : titre normalisé (minuscules, sans accents/ponctuation).
3. Structure : `/anime/{slug}/saison-{n}/{vf|vostfr}/episode-{k}` où k = **numéro
   dans la saison** (pas absolu). Vérifié : découpage saisonnier identique à TMDB
   sur One Piece S1→S22 (comptes égaux à l'épisode près).
4. Calcul : `sIndex = numéroTMDB - premierNuméroDeSaison + 1` ;
   `absIndex = position cumulée` (pour les animes numérotés en continu).
   Ces deux valeurs sont packées dans le data URL de chaque épisode par load() :
   `/tv/{tmdb}/{s}/{e}?t={titre}&i={sIndex}&a={absIndex}&af={0|1}`.
5. Variantes à essayer dans l'ordre : `saison-{s}/vostfr/episode-{i}` →
   `vostfr/episode-{a}` → `saison-1/vostfr/episode-{a}` (idem vf).
6. **Vérification obligatoire** : le `<title>` de la page doit contenir
   « Épisode {numéro demandé} » (regex insensible aux accents) sinon soft-404 → skip.
   (animoflix redirige les épisodes inexistants vers l'épisode 1 !)
7. Extraire `#epLecteurSelect option[value]` → URLs des lecteurs (+ `data-restricted`).
8. Garde-fou anti-confusion anime/live-action : n'activer que si genre TMDB
   « Animation » OU ≥ 60 épisodes au total (évite de servir l'anime « One Piece »
   quand l'utilisateur regarde le live-action Netflix du même nom).
9. CF : passer `CloudflareKiller()` en interceptor sur les requêtes animoflix.

---

## 5. PIÈGES RENCONTRÉS (leçons réelles)

| Piège | Symptôme | Parade |
|---|---|---|
| Turnstile/proof signé | 403 / `{ok:false}` sur l'API du site | Ne pas forcer ; lecteurs de secours du site + agrégateurs |
| Liens non spécifiques à l'épisode | « ça lit le mauvais épisode » | Test E01 vs E02 avant intégration (cf. §4) |
| Soft-404 | Page 200 mais contenu d'un autre épisode | Vérifier « Épisode {n} » dans le titre |
| Numérotation absolue vs par saison | 0 résultat ou mauvais épisode sur animes longs | Packer sIndex/absIndex + variantes (cf. §4.1) |
| Anti-bot polling | `{"status":"wait"}` infini (vidsrc.buzz) | Abandonner cette source |
| WASM crypto | `/fu.wasm`, liens chiffrés côté client (vidlink) | Abandonner |
| API d'embed down | 522 (api.videasy.net) | Abandonner, garder player.videasy.net |
| HTML avec octet nul | grep/file casse | `grep -a`, python `errors='replace'` |
| Extracteur générique 0 flux | embeds CSR (JS pur) | Les passer à `loadExtractor` best-effort ; ne pas promettre |
| Mauvais lecteurs dans la liste | « container unsupported » | Regex anti-junk (jpg/png/vtt/youtube/ads/poster/trailer) |
| OOM gradle (RAM 2 Go) | build tué | `pkill -9 -f "[K]otlinCompileDaemon"` avant rebuild |
| GitHub: `body is not an object` (422) | release API refuse | Écrire le JSON payload dans un fichier, `curl -d @file` |
| Permissions gradlew perdues | `Permission denied` | `chmod +x gradlew` après restauration de workspace |
| API CloudStream | champs/settings inconnus | `javap -classpath jar com.lagradost.cloudstream3.…` (vérifier AVANT de coder) |

---

## 6. PUBLICATION (pipeline complet)

1. `./gradlew make makePluginsJson` → `.cs3` par provider + `plugins.json`.
2. Copier les `.cs3` dans `releases/` ; `plugins.json` avec
   `"url": "https://raw.githubusercontent.com/{user}/{repo}/builds/{Name}.cs3"`.
3. `repo.json` : `{name, description, manifestVersion:1, pluginLists:[url plugins.json]}`.
4. README : tableau des extensions + versions + notes + miroirs.
5. Commit/push main → CI (GitHub Actions) → attendre le statut `success` via l'API.
6. Release **prerelease:true** (tag `xxx-vN`) + uploader les `.cs3` en assets
   (POST `uploads.github.com/.../assets?name=`).
7. Pousser `.cs3` + `plugins.json` sur la branche `builds/` (c'est CE que
   plugins.json référence).
8. Miroirs x0.at : `curl -F "file=@x" https://x0.at/` pour repo.json, plugins.json,
   chaque .cs3 ; vérifier le sha256 du miroir == local.
9. Réponse à l'utilisateur avec : lien release, liens miroirs, changelog.

---

## 7. CHECKLIST FINALE (avant de dire « c'est fini »)

- [ ] Compile sans erreur (`make`), versions incrémentées.
- [ ] `plugins.json` : 4 champs (name, version, url raw builds/, repositoryUrl).
- [ ] Recherche : 2-3 titres renvoient les bons contenus.
- [ ] Fiche film + fiche série + liste épisodes (saisons complètes).
- [ ] loadLinks film : ≥ 20 liens, dont HLS directs.
- [ ] loadLinks épisode standard : ≥ 20 liens, **liens différents entre 2 épisodes**.
- [ ] loadLinks anime long (One Piece) : liens du BON épisode, y compris récent.
- [ ] Épisode inexistant → aucun lien faux (soft-404 géré).
- [ ] Les 3-4 extensions précédentes fonctionnent toujours (build intact).
- [ ] CI verte, release Pre-release créée, builds/ poussé, miroirs vérifiés sha256.

---

*Basé sur l'expérience réelle de : AnimoFlix v1→v3 (scraping animoflix.to, CF,
extracteurs AnsEmbed/Odysee), Zenix v1→v3 (scraping PHP SSR + boutons serveurs),
WaveWatch v1→v2 (API TMDB maison + wwembed + TV live + agrégateurs), Afterdark
v1→v2 (RE de bundles React/TanStack, gate Turnstile analysée et contournée par
sources publiques équivalentes).*
