# Plugin fr · Dépôt d'extensions CloudStream (`.cs3`)

Dépôt d'extensions [CloudStream](https://github.com/recloudstream/cloudstream) en français — **deux extensions** :

| Extension | Site | Contenu |
|---|---|---|
| **AnimoFlixProvider** | [animoflix.to](https://animoflix.to/) | animes, films & OAV en VF / VOSTFR |
| **ZenixProvider** | [zenix.best](https://zenix.best/) (secours : zenix.lol) | films & séries en VF / VOSTFR |

---

# AnimoFlix · Extension CloudStream (`.cs3`)

Extension pour **[animoflix.to](https://animoflix.to/)** — animes, films & OAV en **VF / VOSTFR**.

## ✨ Fonctionnalités

- **Page d'accueil** : Derniers épisodes VOSTFR, derniers épisodes VF, derniers ajouts, catalogue paginé (93 pages)
- **Recherche** : recherche serveur (`?search=`) + fallback autocomplete (reconnaît aussi les titres alternatifs)
- **Fiches animes** : titre, poster, synopsis, genres, statut (en cours / terminé), titre alternatif
- **Épisodes** : toutes les saisons parcourues automatiquement, VF et VOSTFR séparées (onglets « VOSTFR » / « VF » dans l'app), saisons spéciales (films, OAV, arcs) regroupées en saison 0
- **Lecteurs** : tous les lecteurs de la page épisode sont listés (SibNet, SendVid, AnsEmbed, Odysee…) — extracteurs maison pour `ansembed.net` (clone VidMoly/JWPlayer) et `odysee.com` (API LBRY), extracteurs intégrés CloudStream pour les autres, et fallback générique (og:video, `<source>`, `file:` mp4/m3u8) si un extracteur ne renvoie rien
- **Miniatures** : chaque épisode affiche l'affiche de la fiche de l'anime
- **Cloudflare** : le site est protégé par Cloudflare — l'extension utilise `CloudflareKiller` (résolution du challenge via WebView au premier lancement)

## 📥 Installation

### Méthode 0 — Test immédiat (hébergement public temporaire)

Avant même de pousser ce dépôt sur GitHub, vous pouvez tester l'extension en ajoutant ce `repo.json` public dans CloudStream (**Paramètres → Extensions → Ajouter un dépôt**) :

```
https://x0.at/gMeP.json
```

- `repo.json` (v2) : https://x0.at/gMeP.json
- `plugins.json` : https://x0.at/aVsv.json
- `AnimoFlixProvider.cs3` (v2) : https://x0.at/nrrJ.cs3

> ⚠️ Hébergement temporaire (expiration sous quelques semaines/mois). Pour un lien pérenne, poussez le dépôt sur GitHub — le workflow publiera tout automatiquement sur la branche `builds`.
> ℹ️ Si vous aviez ajouté l'ancienne URL de test (v1, `x0.at/RluP.json`) : supprimez d'abord ce dépôt et l'extension dans CloudStream, puis ajoutez la nouvelle ci-dessus.

**v2** : tous les serveurs/lecteurs de la page épisode sont maintenant listés (y compris SendVid, AnsEmbed et Odysee via un extracteur dédié + un fallback générique mp4/m3u8), et les épisodes affichent la miniature de la fiche de l'anime.

### Méthode 1 — Ajouter le dépôt (recommandé)

Dans CloudStream : **Paramètres → Extensions → Ajouter un dépôt** et coller l'URL `repo.json` de la branche `builds` de ce dépôt :

```
https://raw.githubusercontent.com/j97970293-lang/plugin-fr/builds/repo.json
```

> Le workflow GitHub Actions construit et publie automatiquement `AnimoFlixProvider.cs3` + `plugins.json` + `repo.json` sur la branche `builds` à chaque push.

### Méthode 2 — Depuis la release GitHub

Téléchargez le `.cs3` attaché à la release [**v2 (Pre-release)**](https://github.com/j97970293-lang/plugin-fr/releases/tag/v2) puis dans CloudStream : **Paramètres → Extensions → Installer un fichier**.

### Méthode 3 — Installer le fichier du dépôt

Téléchargez [`releases/AnimoFlixProvider.cs3`](releases/AnimoFlixProvider.cs3) puis dans CloudStream : **Paramètres → Extensions → Installer un fichier** (ou placez le fichier dans le dossier `Cloudstream3/extensions`).

> **Note** : cette extension est compilée contre l'API `pre-release` de CloudStream. Utilisez une version récente de l'app ([releases pre-release](https://github.com/recloudstream/cloudstream/releases)).

---

# Zenix · Extension CloudStream (`.cs3`)

Extension pour **[zenix.best](https://zenix.best/)** (domaine de secours officiel : `zenix.lol`) — films & séries en **VF / VOSTFR**.

## ✨ Fonctionnalités

- **Page d'accueil** : 14 sections réelles du site (Tendances, Top IMDb, Films, Séries + 10 genres : action, aventure, animation, comédie, SF, horreur, thriller, romance, policier, drame) — la pagination des listes est chargée en JS sur le site, chaque section affiche sa première page
- **Recherche** : API JSON du site (`/ajax/search/suggest`) avec titre, affiche, année — fallback HTML `/search/{requête}`
- **Fiches** : titre, affiche, note, année, synopsis, genres — extraits des badges de la fiche (les badges « vues » et IMDb sont ignorés)
- **Séries** : toutes les saisons et épisodes parsés depuis les liens `/episode/{slug}/{saison}-{épisode}`, avec titre et miniature d'épisode ; ouvrir un lien épisode remonte automatiquement à la série
- **Lecture — TOUS les serveurs listés** : chaque page film/épisode contient les ~24 serveurs du site (« Serveurs de lecture » + « Autres sources ») : Frembed, Peachify, VidFast, Mostream, HNEmbed, Catflix, WaveWatch, Videasy, PrimeSrc, VidLove, VidUp, Braflix, StreamIMDb, VidSrc PM/IO, AnyEmbed, Viduki, **1Embed**, VidKing, 2Embed, SuperFlix… Ils sont essayés **en parallèle** (6 à la fois), les **VF d'abord**, puis VOSTFR, puis le reste ; les lecteurs internes Zenix (BlinkFlux / « Lecteur Gratuit 4K », protégés par pubs/captcha) en dernier
- **Extracteur maison `1embed.cc`** : les pages de ce lecteur contiennent la liste de ses serveurs (Solari, Necro…) avec des playlists HLS directement jouables — testé et validé sur films **et** épisodes
- **Dernier recours** : l'ID TMDB de la fiche est utilisé pour reconstruire une URL 1embed si aucune source n'a répondu
- **Fallback générique** : pour tout lecteur sans extracteur, la page de l'embed est analysée (og:video, `<source>`, `file:`, .m3u8/.mp4/.webm)

## 📥 Installation

Mêmes méthodes que AnimoFlix (dépôt ci-dessus), ou directement : [`releases/ZenixProvider.cs3`](releases/ZenixProvider.cs3) → **Paramètres → Extensions → Installer un fichier**.

## 🔨 Compiler soi-même

```bash
./gradlew ZenixProvider:make    # → ZenixProvider/build/ZenixProvider.cs3
```

## ⚠️ Notes techniques

- Le site est un rendu serveur PHP **sans Cloudflare** — pas de WebView nécessaire.
- Les serveurs sont lus depuis les boutons `selectStream(n, 'url', 'Libellé | LANGUE', 'type')` présents dans le HTML brut (les « Autres sources » sont dépliables dans l'UI web mais déjà dans la page).
- Les lecteurs externes sont des embeds JS pour la plupart : CloudStream n'a pas d'extracteur intégré pour eux ; l'extension compte sur **1embed** (fiable, HLS direct), les extracteurs intégrés (certains peuvent s'ajouter dans les futures versions de l'app) et le fallback générique.
- Si le site change d'adresse, modifiez `mainUrl` en haut de `ZenixProvider.kt` (`https://zenix.lol`).

---

## 🔨 Compiler soi-même

```bash
./gradlew AnimoFlixProvider:make   # → AnimoFlixProvider/build/AnimoFlixProvider.cs3
./gradlew makePluginsJson          # → build/plugins.json
```

Prérequis : JDK 17 + Android SDK (`platforms;android-35`, `build-tools`). La tâche `make` peut aussi être lancée pour tous les modules via `./gradlew make`.

Pour déployer directement sur un téléphone connecté en USB (ADB) :

```bash
./gradlew AnimoFlixProvider:deployWithAdb
```

## 📁 Structure du projet

```
animoflix-cloudstream/
├── build.gradle.kts              # config racine (plugin Gradle CloudStream, AGP 8.7.3, Kotlin 2.3.0)
├── settings.gradle.kts           # inclusion automatique des modules
├── repo.json                     # descripteur du dépôt (la version à jour est générée sur la branche builds)
├── releases/                     # derniers .cs3 compilés
├── .github/workflows/build.yml   # build automatique → branche builds
├── AnimoFlixProvider/
│   ├── build.gradle.kts          # métadonnées du plugin (version, tvTypes, langue, icône…)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/animoflix/
│           ├── AnimoFlixProvider.kt   # provider (accueil, recherche, fiche, épisodes, liens vidéo)
│           └── (plugin + extracteur AnsEmbed dans le même fichier)
└── ZenixProvider/
    ├── build.gradle.kts          # métadonnées du plugin Zenix
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/zenix/
            └── ZenixProvider.kt        # provider + plugin + extracteur 1embed
```

## ⚠️ Notes techniques

- **`animoflix.to` est protégé par Cloudflare** : au premier chargement, CloudStream ouvre une WebView pour résoudre le challenge, puis les cookies sont réutilisés.
- Les épisodes **VOSTFR** sont mappés sur l'onglet *Subbed* et les **VF** sur l'onglet *Dubbed* de CloudStream.
- Les URLs du site suivent le schéma `/anime/{slug}/[saison-N|film|…]/{vf|vostfr}/episode-N/` — les saisons sans numéro (film, heroines, kai…) sont placées en **saison 0** (spéciaux).
- Si le site change de structure, ajustez les sélecteurs dans `AnimoFlixProvider.kt` et bumpsez `version` dans `AnimoFlixProvider/build.gradle.kts` (déclenche la mise à jour auto côté app).

## 📜 Licence / Avertissement

Projet à but éducatif. CloudStream et cette extension ne hébergent aucun contenu : elles indexent uniquement un site public. Utilisez-la conformément aux lois applicables dans votre pays.
