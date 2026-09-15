# Journal des mises à jour

## v4 — 2026-09-14 (soir) : 13 extensions

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
