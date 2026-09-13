# AnimoFlix · Extension CloudStream (`.cs3`)

Extension [CloudStream](https://github.com/recloudstream/cloudstream) pour **[animoflix.to](https://animoflix.to/)** — animes, films & OAV en **VF / VOSTFR**.

## ✨ Fonctionnalités

- **Page d'accueil** : Derniers épisodes VOSTFR, derniers épisodes VF, derniers ajouts, catalogue paginé (93 pages)
- **Recherche** : recherche serveur (`?search=`) + fallback autocomplete (reconnaît aussi les titres alternatifs)
- **Fiches animes** : titre, poster, synopsis, genres, statut (en cours / terminé), titre alternatif
- **Épisodes** : toutes les saisons parcourues automatiquement, VF et VOSTFR séparées (onglets « VOSTFR » / « VF » dans l'app), saisons spéciales (films, OAV, arcs) regroupées en saison 0
- **Lecteurs** : détection automatique des hébergeurs via le sélecteur de lecteurs de la page épisode (SibNet, SendVid, AnsEmbed… + extracteur maison pour `ansembed.net`, clone VidMoly/JWPlayer) et fallback générique (mp4/m3u8)
- **Cloudflare** : le site est protégé par Cloudflare — l'extension utilise `CloudflareKiller` (résolution du challenge via WebView au premier lancement)

## 📥 Installation

### Méthode 0 — Test immédiat (hébergement public temporaire)

Avant même de pousser ce dépôt sur GitHub, vous pouvez tester l'extension en ajoutant ce `repo.json` public dans CloudStream (**Paramètres → Extensions → Ajouter un dépôt**) :

```
https://x0.at/RluP.json
```

- `repo.json` : https://x0.at/RluP.json
- `plugins.json` : https://x0.at/ACMv.json
- `AnimoFlixProvider.cs3` (v1) : https://x0.at/VE76.cs3

> ⚠️ Hébergement temporaire (expiration sous quelques semaines/mois). Pour un lien pérenne, poussez le dépôt sur GitHub — le workflow publiera tout automatiquement sur la branche `builds`.

### Méthode 1 — Ajouter le dépôt (recommandé)

Dans CloudStream : **Paramètres → Extensions → Ajouter un dépôt** et coller l'URL `repo.json` de la branche `builds` de ce dépôt :

```
https://raw.githubusercontent.com/VOTRE-COMPTE/animoflix-cloudstream/builds/repo.json
```

> Remplacez `VOTRE-COMPTE` par le propriétaire GitHub du dépôt. Le workflow GitHub Actions construit et publie automatiquement `AnimoFlixProvider.cs3` + `plugins.json` + `repo.json` sur la branche `builds` à chaque push.

### Méthode 2 — Installer le fichier directement

Téléchargez [`releases/AnimoFlixProvider.cs3`](releases/AnimoFlixProvider.cs3) puis dans CloudStream : **Paramètres → Extensions → Installer un fichier** (ou placez le fichier dans le dossier `Cloudstream3/extensions`).

> **Note** : cette extension est compilée contre l'API `pre-release` de CloudStream. Utilisez une version récente de l'app ([releases pre-release](https://github.com/recloudstream/cloudstream/releases)).

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
├── releases/                     # dernier .cs3 compilé
├── .github/workflows/build.yml   # build automatique → branche builds
└── AnimoFlixProvider/
    ├── build.gradle.kts          # métadonnées du plugin (version, tvTypes, langue, icône…)
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/animoflix/
            ├── AnimoFlixProvider.kt   # provider (accueil, recherche, fiche, épisodes, liens vidéo)
            └── (plugin + extracteur AnsEmbed dans le même fichier)
```

## ⚠️ Notes techniques

- **`animoflix.to` est protégé par Cloudflare** : au premier chargement, CloudStream ouvre une WebView pour résoudre le challenge, puis les cookies sont réutilisés.
- Les épisodes **VOSTFR** sont mappés sur l'onglet *Subbed* et les **VF** sur l'onglet *Dubbed* de CloudStream.
- Les URLs du site suivent le schéma `/anime/{slug}/[saison-N|film|…]/{vf|vostfr}/episode-N/` — les saisons sans numéro (film, heroines, kai…) sont placées en **saison 0** (spéciaux).
- Si le site change de structure, ajustez les sélecteurs dans `AnimoFlixProvider.kt` et bumpsez `version` dans `AnimoFlixProvider/build.gradle.kts` (déclenche la mise à jour auto côté app).

## 📜 Licence / Avertissement

Projet à but éducatif. CloudStream et cette extension ne hébergent aucun contenu : elles indexent uniquement un site public. Utilisez-la conformément aux lois applicables dans votre pays.
