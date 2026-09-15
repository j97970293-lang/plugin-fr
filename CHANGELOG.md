# Journal des mises à jour

## v9 — 2026-09-16 (vidsrc.buzz + hébergeurs craqués + TV Madagascar)

### 🆕 Agrégateur vidsrc.buzz — WaveWatch v7, Zenix v8, CineStream v5, UnJour1Film v6
- **Nouvelle source multi-serveurs TMDB** (films, séries ET **animes**) : la page d'embed `vidsrc.buzz/embed/{type}/{tmdb}` porte un jeton signé, l'API `/pl/api.php?a=sources` liste les serveurs, `a=play` rend l'URL du flux `/_stream?id=…` (HLS proxysé, direct). Chaîne entièrement reversée et testée (Breaking Bad S1E1 et Fight Club → m3u8 200 OK ; Redo of Healer 99071 → serveur détecté).
- **WaveWatch** : c'est la réponse au « aucun serveur sur les animes » (Redo of Healer & co) — la pile d'agrégateurs TMDB existante (wwembed, zeus, apiwiflix, playerix, movix, moviesApi, mouve, 1embed) est vide pour les animes ; vidsrc.buzz s'ajoute à la pile pour tout contenu TMDB.
- **Zenix, CineStream, 1jour1film** : même ajout — plus de serveurs sur les films ET les séries.

### 🛠️ UnJour1Film v6 — « peu de serveurs films / aucun sur les séries » réparé
- Diagnostic : les sources du site utilisent des hébergeurs que rien n'extrayait.
- **Byse (bysezoxexe.com) craqué** : l'API `/api/videos/{code}/` renvoie un `playback` chiffré **AES-256-GCM** ; la clé se dérive de `key_parts` selon la version (`parts[v-1] + parts[30-v]`, reverse du bundle JS lazy-loadé) → HLS direct déchiffré (vérifié : m3u8 lisible).
- **« C'était mieux avant » (cetaitmieuxavant.website)** : chaque page épisode embarque `videoData.servers` = 4 serveurs (FireStream, Byse, Lulustream, p2p) → tous relayés sauf p2p.
- **FireStream (firestream.site)** : `<script id="video-data">` avec `signedVideoUrl` (MP4/HLS direct). NB : le champ est masqué aux IP flaggées VPN — depuis un mobile il est là.
- Résultat : films = Lulustream + Byse + agrégateurs ; séries = jusqu'à 3 serveurs site + pile TMDB + vidsrc.buzz.

### 🆕 TeleFrance v2 — section 🇲🇬 Madagascar (chaînes d'Antananarivo)
- **TVM (Télévision Malagasy), RealTV, RTA (Radio Télévision Analamanga), Viva TV, KOLO TV, TV Plus Madagascar** : leurs directs YouTube sont résolus à la volée (page `/live` de la chaîne → vidéo en cours → extracteur YouTube intégré à CloudStream). Vérifié en direct : TVM « Vaovao » et RealTV « L'invité du jour » en flag `isLiveContent`.
- Hors direct, la page joue la dernière vidéo de la chaîne (utile pour les chaînes info). Logos inclus, recherche « madagascar » fonctionne.
- Les playlists IPTV publiques n'ont aucune chaîne malgache (mg.m3u = 1 chaîne hors-sujet) — les lives YouTube officiels des chaînes sont la seule source fiable.

### 📚 TUTO
- §4 : ligne vidsrc.buzz dans le tableau des agrégateurs.
- §4.7 : méthode complète « reverse d'un hébergeur chiffré AES-256-GCM » (bysezoxexe) + les cousins firestream/cetaitmieuxavant.
- §4.8 : TV malgache — résolution runtime des lives YouTube.
- §4.9 : leçon transversale — une IP datacenter n'est pas un téléphone (VPN-flagging, CF) : toujours garder `runCatching` + serveurs redondants.

### ✅ Révision des 14 sources
Afterdark, AnimeSama, AnimeSite, Flemmix, FrenchStream, Purstream, Vostfree, WaveWatch, 1jour1film : répondent OK depuis le sandbox. AnimoFlix et Franime : Cloudflare sur IP datacenter (CloudflareKiller en place — l'app sur mobile passe, comme vérifié v8 pour 1jour1film).

---

## v8 — 2026-09-15 (résolution Cloudflare partout + TV française en direct)

### 🆕 TeleFranceProvider v1 — Télé FR Direct
- **14ᵉ extension** : 200+ chaînes de télévision françaises **en direct** (généralistes, info, cinéma, séries, divertissement, animation, enfants, sport, documentaires, musique) via les flux publics IPTV du projet iptv-org.
- Chaînes géo-bloquées / non permanentes filtrées, logos inclus, **recherche par nom de chaîne**.
- Réglage ⚙ : n'importe quelle playlist M3U peut être branchée (par ex. Madagascar : `iptv-org.github.io/iptv/countries/mg.m3u`).

### 🛡️ Résolution Cloudflare sur TOUTES les sources (8 extensions)
- **UnJour1Film (conservé), AnimeSama, AnimeSite, CineStream, FrenchStream, Purstream, Vostfree** reçoivent l'intercepteur **CloudflareKiller** : si le défi Cloudflare se déclenche (selon le réseau/opérateur), CloudStream ouvre un mini-navigateur qui le résout — automatiquement pour les challenges JS, en un clic pour Turnstile — puis le cookie `cf_clearance` fait passer toutes les requêtes suivantes. AnimoFlix, Flemmix, Franime, WaveWatch et Zenix l'avaient déjà ; Afterdark passe par un proxy TMDB et n'en a pas besoin.
- C'est la réponse au « catalogue vide » de 1jour1film chez certains utilisateurs : le site est derrière Cloudflare et certains réseaux reçoivent un défi au lieu de la page.

---

## v7 — 2026-09-15 (correctifs des retours v6 : réglages, recherche, diagnostics)

### 🔧 UnJour1FilmProvider v4 — le bouton réglages apparaît enfin
- **Correction de câblage** : la fonction « changer l'adresse » existait depuis la v6 mais n'était pas branchée sur le bouton ⚙ (oubli d'`openSettings`) → le bouton n'apparaissait jamais. Corrigé.
- **Erreurs visibles** : si le site est inaccessible, l'accueil affiche désormais un message explicite (« changez l'adresse dans les réglages ⚙ ») au lieu de sections vides silencieuses — plus de devinettes.
- **En-têtes navigateur complets** (Accept, Sec-Fetch-…) : le WAF du site bloque les requêtes au user-agent non-navigateur (okhttp → 403) et peut noter les requêtes minimalistes.
- **Fallback REST étendu** aux « Dernières sorties » (en plus de Films/Séries).

### 🔧 PurstreamProvider v4 — même correction de câblage
- Le bouton ⚙ « adresse du site + API » apparaît désormais (oubli d'`openSettings` en v6).

### 🔧 AnimeSamaProvider v5 — recherche blindée
- Le POST de recherche peut être bloqué par certains réseaux/WAF alors que les GET passent : si le POST ne donne rien, **fallback GET `/catalogue/?search=…`** (recherche côté serveur du formulaire catalogue) + en-tête `X-Requested-With` pour mimer le site.
- **Correction de câblage** identique vérifiée (le réglage URL existait déjà et fonctionnait).

### 🔧 AnimeSiteProvider v4 — réglages d'URL ajoutés
- Bouton ⚙ pour changer l'adresse du site (demandé) + invalidation du cache sitemap si l'URL change.

---

## v6 — 2026-09-15 (retours utilisateur : saisons, épisodes, serveurs, réglages)

### 🔧 AnimeSamaProvider v4 — numérotation des saisons enfin lisible
- **One Piece n'affichait plus « 100+ saisons »** : les Kai étaient numérotés 101+, les hors-séries 41+, les films 90. Désormais **numérotation séquentielle** : les saisons/sagas normales gardent leur numéro (1-12), puis Films (13), Fan Letter/OAV (14), hors-séries (15-16) et Kai (17-27) continuent à la suite. Le libellé complet reste visible dans le nom de la saison et des épisodes (« Kai - Saga 1 (East Blue) · Épisode 5 »).

### 🔧 PurstreamProvider v3 — épisodes complets + réglages + serveurs en plus
- **Épisodes manquants des animes** : l'endpoint `media/{id}/seasons` est incomplet pour certains animes (Naruto : saison 1 seule, Naruto Shippuden : 32 épisodes sur 500). Les épisodes sont désormais construits par **UNION** de `media/{id}/sheet` (`urls`, numérotation continue — c'est celle qu'attend l'API stream) et de `seasons` (noms, vignettes, résumés). Naruto : **220 épisodes** (S1-S4), Shippuden : **500** (S1-S20).
- **Serveurs supplémentaires** : le champ `tmdbId` de la fiche alimente les lecteurs publics (Videasy, Frembed, Peachify, VidFast, VidSrc.cc, VidSrc.wtf, 2Embed, 111Movies, Braflix, VidKing, VidNest) et l'agrégateur apiwiflix (langues VF/VOSTFR), en plus des flux du site.
- **Réglages** : bouton ⚙ pour changer l'adresse du site **et** de l'API si le domaine change.
- ⚠ Limitation site : certains épisodes sont réellement absents chez Purstream (Jujutsu Kaisen S1E3-7, Demon Slayer S1E15 → 404 côté serveur) — les lecteurs publics TMDB peuvent alors les fournir en secours.

### 🔧 UnJour1FilmProvider v3 — catalogue blindé + serveurs en plus
- **Catalogue** : fallback **REST WordPress** (`/wp-json/wp/v2/movies|tvshows`, 8 423 titres) si l'ajax `j1f_catalogue` devient indisponible — le catalogue s'affiche dans tous les cas. (Le site a été re-testé avec le user-agent exact de CloudStream : l'ajax fonctionne ; si le catalogue restait vide, c'est que la **v1** était encore installée.)
- **Serveurs supplémentaires** : l'ID TMDB est extrait des scripts du site (`vp4-{tmdb}` pour les films, `var tmdb`/`var season` pour les saisons) → mêmes lecteurs publics + agrégateur apiwiflix que Purstream, en plus des sources Vidara/Lulustream du site.
- **Réglages** : bouton ⚙ pour changer l'adresse du site (rotation de domaine fréquente).

---

## v5 — 2026-09-15 (correctifs des retours v4)

### 🔧 AnimeSamaProvider v3
- **One Piece complet** : en 4G, les 27 requêtes de comptage simultanées échouaient pour la moitié des saisons (réduites à 1 épisode → ~220 visibles). Désormais : concurrence limitée à 3 + 2 relances par saison. Total réel : **1 369 épisodes** (sagas 1-12, films, OAV, hors-séries, Kai).
- **Piste DUB (VF)** : les fiches ne listent que les panneaux VOSTFR alors que les pages `/vf/` existent (découvert via les CTA de la home) — l'extension sonde la VF et remplit le sélecteur **SUB/DUB**.
- **Catalogue élargi** : section « Animes du planning » (~255 animes en cours) + routage des sections corrigé (`request.data`) — les 4 sections affichaient la même liste.

### 🔧 AnimeSiteProvider v3 — corrige le crash à l'ouverture
- Le sitemap (486 Ko) était téléchargé **20 fois en parallèle** (un par carte de l'accueil) → gel + fermeture de l'application (ANR). Désormais un seul téléchargement, protégé par verrou, mis en cache par session.

### 🔧 PurstreamProvider v2 — corrige « la fiche ne donne rien »
- Fiches sur de vraies URLs (`/movie/{id}`, `/serie/{id}`) au lieu du schéma interne `ps:{id}:{type}` + routage des sections corrigé.

### 🔧 UnJour1FilmProvider v2 — corrige « pas de catalogue »
- Le catalogue était interrogé avec le **libellé** de la section (« Films ») au lieu de sa clé (« movies ») → réponse vide. Corrigé.

> Leçon commune : ne JAMAIS router `getMainPage` sur `request.name` (libellé affiché) mais sur `request.data` (clé) — bug déjà rencontré en v3 et réintroduit par erreur dans les 3 nouveaux providers.

---

## v4 — 2026-09-15 : 13 extensions

### 🆕 PurstreamProvider (v1) — purstream.ad
- Site SPA React : API JSON ouverte `api.purstream.ad/api/v1/` découverte en reversant le bundle JS.
- Catalogue (~5 000 titres) paginé, carrousels par genre, recherche instantanée.
- **Flux HLS (m3u8) directs** — aucun lecteur intermédiaire. Épisodes avec vignettes TMDB.

### 🆕 UnJour1FilmProvider (v1) — 1jour1film0826b.website
- WordPress/DooPlay : catalogue AJAX `j1f_catalogue` (8 418 films + 1 269 séries), fiches TMDB.
- Sources servies par AJAX `j1f_get_source` / `j1f_get_ep_source` (nonce renouvelé à chaque session).
- Extraction locale des lecteurs **Vidara** (POST `/api/stream` → m3u8) et **Lulustream** (JS packé → m3u8).
- Épisodes avec vignettes TMDB (backdrop), sections Dernières sorties / Films / Séries.

### 🔧 AnimeSamaProvider v2
- **One Piece enfin complet** : la regex saison ne reconnaissait pas « Saga N » / « Kai - Saga N » / `saisonNhs` → toutes les sagas avaient `season = null` et CloudStream fusionnait les épisodes par numéro (il n'en restait que ~74). Numéros de saison désormais **uniques par panneau** (sagas 1-12, films 90, OAV 91, hors-séries 41+, Kai 101+, autres à la suite) + noms d'épisodes explicites (« Saga 1 (East Blue) · Épisode 5 »).
- **Sections VF / VOSTFR** : Nouveautés (carrousel), Derniers épisodes VOSTFR, Derniers épisodes VF, Catalogue complet — cartes avec posters et dédupliquées.
- Poster de la fiche appliqué aux épisodes.

### 🔧 AnimeSiteProvider v2 — corrige « installe avec erreur / extension invisible »
- Cause racine : l'annotation `@CloudstreamPlugin` était posée sur la classe provider au lieu d'une **classe `Plugin` séparée**. Le `.cs3` s'installait mais aucune extension n'était enregistrée. Classe `AnimeSitePlugin : Plugin()` ajoutée (pattern commun aux 12 autres extensions).
- Poster de la fiche appliqué aux épisodes.

### 🔧 FlemmixProvider v4 — la recherche wiflix fonctionne
- Le « bot shield » DLE exige le cookie JS **`h_check=25`** (posé par un script inline de la page d'accueil) : le POST de recherche le renvoie désormais → « batman » renvoie 84 résultats, 20 cartes par page avec posters.
- Bloc de recommandations masqué (`#no-results-rec`) correctement ignoré pour ne retourner que les vrais résultats.
- Nouvelles catégories gérées : `/film-ancien/`, `/saison-complete/`, `/vf/`, `/vostfr/`.
- Poster de la fiche appliqué aux épisodes des séries.

### 🔧 Divers
- **Auteur des extensions** : toutes les extensions sont maintenant signées **j97970293-lang** (13/13).
- **Posters sur les épisodes** (règle : si le site n'a pas de vignette d'épisode, on met l'affiche de la fiche) : Franime (vignette réelle si fournie, sinon affiche), Vostfree, Flemmix, FrenchStream, AnimeSama, AnimeSite. AnimoFlix / Zenix / Afterdark / WaveWatch / Purstream / 1JOUR1FILM avaient déjà des vignettes (TMDB/API).
- Versions : Afterdark 6, AnimoFlix 6, CineStream 3, Franime 3, FrenchStream 4, Vostfree 3, WaveWatch 6, Zenix 7 (rebuilds avec nouvel auteur).

### ❌ Sites re-testés et écartés
- **dulourd.hair** : lecteurs derrière un mur d'inscription (« S'inscrire pour regarder », reCAPTCHA) — arnaque classique, exclu.
- **movix.online** : simple vitrine qui redirige vers movix.men (déjà couvert par 3 agrégateurs).
- **Nakio / nakios.homes / nakios.biz** : pages d'atterrissage SEO sans catalogue propre (liens vers revestream.cc, SPA « accès réservé »).

---

## v3 — 2026-09-14 (après-midi) : 11 extensions, catalogues & recherches

### Correctif « catalogues identiques » (5 extensions)
Les sections routaient sur `request.name` (libellé) au lieu de `request.data` (clé) → chaque section affichait la même liste. Corrigé dans FrenchStream 3, Flemmix 3, Vostfree 2, CineStream 2, Franime 2.

### Recherches corrigées
- **FrenchStream** : la recherche GET renvoyait toujours le catalogue par défaut → bascule en POST DLE (« batman » → 18 fiches Batman).
- **Franime** : l'API Kitsu cherche en anglais/romaji → la recherche passe par le catalogue FRAnime lui-même (titres français) avec repli Kitsu.
- **Flemmix** : fiches corrompues (meta description = nom de fichier du poster, épisode 0 fantôme) → poster `#posterimg`, année « Date de sortie », `ep00` ignoré.

### 🆕 AnimeSiteProvider (v1) — animesite.fr
API cachée `/api/medias` + conversion des IDs internes en ID TVDB (× 336) + sitemap pour les slugs + lecteurs SibNet en MP4 directs.

### Enquête wiflix
- wiflix.voto = domaine parqué ; le vrai « wiflix » est **flemmix.cloud** (le site s'affiche « wiflix » dans ses titres).

---

## v2 — 2026-09-14 (matin) : 10 extensions
- Ajout CineStreamProvider (v1), miroirs de secours, réparations d'URLs des sources.
- `request.data` introduit pour distinguer les sections.

---

## v1 — 2026-09-14 (nuit) : 9 extensions
- Première version publique : AnimoFlix, Zenix, WaveWatch, Afterdark, Franime, FrenchStream, Flemmix, Vostfree, Anime-Sama.
- Dépôt + CI (branche `builds`) + releases + miroirs x0.at.
