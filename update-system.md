# Update System — Documentation du projet

## Vue d'ensemble

Le système de mise à jour est composé de deux modules indépendants dont les responsabilités sont strictement séparées. Il est conçu pour s'adapter à différents types de cibles (client lourd, application web, service/daemon) via un pattern **Strategy**.

```
Serveur HTTP/HTTPS
      │
      ├──▶ Self-Updater       (met à jour le System-Updater)
      │
      └──▶ System-Updater     (met à jour la cible via un adaptateur)
                │
                └──▶ UpdaterStrategy
                          ├── DesktopUpdaterStrategy
                          ├── WebUpdaterStrategy
                          └── ServiceUpdaterStrategy
```

---

## Modules

### 1. Self-Updater

**Rôle** : télécharger et remplacer le `System-Updater`. Ce module est minimaliste et conçu pour ne jamais nécessiter une mise à jour de lui-même.

**Responsabilités** :
- Interroger le serveur pour vérifier si une nouvelle version du `System-Updater` est disponible
- Télécharger l'artefact et vérifier son intégrité (hash SHA-256)
- Remplacer le binaire ou JAR du `System-Updater` et le redémarrer

**Contraintes** :
- Aucune dépendance externe (stdlib uniquement autant que possible)
- Doit fonctionner en tant que service système ou processus en arrière-plan
- Aucun rollback nécessaire sur lui-même (il ne touche pas à la cible)

---

### 2. System-Updater

**Rôle** : orchestrer la mise à jour de la cible selon la stratégie configurée.

**Responsabilités** :
- Lire la configuration pour déterminer le type de cible et la stratégie à utiliser
- Appliquer le pipeline générique : vérification → téléchargement → sauvegarde → installation → redémarrage
- Déléguer les opérations spécifiques à l'implémentation de `UpdaterStrategy`
- Gérer le rollback en cas d'échec

---

## Architecture — Pattern Strategy

### Interface commune

```kotlin
interface UpdaterStrategy {
    fun checkForUpdate(currentVersion: Version): UpdateInfo?
    fun download(info: UpdateInfo): Path
    fun backup(): Path
    fun install(artifact: Path)
    fun restart()
    fun rollback(backup: Path)
}
```

### Modèles partagés

```kotlin
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version>

data class UpdateInfo(
    val version: Version,
    val downloadUrl: String,
    val checksum: String,       // SHA-256
    val releaseNotes: String?
)
```

### Implémentations

| Stratégie | Cible | Comportement spécifique |
|---|---|---|
| `DesktopUpdaterStrategy` | Client lourd (JAR, exe) | Arrêt du processus, remplacement du binaire, relance |
| `WebUpdaterStrategy` | App web (SPA, SSR) | Remplacement des fichiers statiques ou redéploiement |
| `ServiceUpdaterStrategy` | Daemon, API, microservice | Arrêt via systemd/SC, remplacement, redémarrage du service |

---

## Pipeline de mise à jour

```
[Démarrage]
     │
     ▼
[Lecture config]  ──▶  Sélection de la stratégie
     │
     ▼
[checkForUpdate]  ──▶  Aucune mise à jour ? ──▶  [Fin]
     │
     ▼
[download + vérification checksum]
     │
     ▼
[backup de l'état actuel]
     │
     ▼
[install]
     │
     ├── Succès ──▶ [restart]  ──▶  [Fin]
     │
     └── Échec  ──▶ [rollback] ──▶  [Notification d'erreur]
```

---

## Manifest serveur

Le serveur expose un fichier `manifest.json` par canal (stable, beta…) que les modules interrogent pour connaître la dernière version disponible.

```json
{
  "channel": "stable",
  "updater": {
    "version": "1.4.2",
    "url": "https://updates.exemple.com/system-updater-1.4.2.jar",
    "checksum": "sha256:abc123...",
    "releaseNotes": "Correctifs de stabilité"
  },
  "targets": {
    "desktop": {
      "version": "3.2.0",
      "url": "https://updates.exemple.com/app-desktop-3.2.0.jar",
      "checksum": "sha256:def456..."
    },
    "web": {
      "version": "2.1.5",
      "url": "https://updates.exemple.com/app-web-2.1.5.zip",
      "checksum": "sha256:ghi789..."
    },
    "service": {
      "version": "1.0.8",
      "url": "https://updates.exemple.com/app-service-1.0.8.jar",
      "checksum": "sha256:jkl012..."
    }
  }
}
```

---

## Structure du projet

```
update-system/
│
├── self-updater/                  # Module autonome, minimal
│   ├── src/main/kotlin/
│   │   └── SelfUpdater.kt
│   └── build.gradle.kts
│
├── system-updater/                # Module principal
│   ├── src/main/kotlin/
│   │   ├── core/
│   │   │   ├── UpdateOrchestrator.kt    # Pipeline générique
│   │   │   ├── HttpClient.kt            # Téléchargement + manifest
│   │   │   └── ChecksumVerifier.kt      # Vérification SHA-256
│   │   ├── strategies/
│   │   │   ├── UpdaterStrategy.kt       # Interface
│   │   │   ├── desktop/
│   │   │   │   └── DesktopUpdaterStrategy.kt
│   │   │   ├── web/
│   │   │   │   └── WebUpdaterStrategy.kt
│   │   │   └── service/
│   │   │       └── ServiceUpdaterStrategy.kt
│   │   └── config/
│   │       └── UpdateConfig.kt          # Lecture YAML/JSON config
│   └── build.gradle.kts
│
└── shared/                        # Modèles communs
    ├── src/main/kotlin/
    │   ├── Version.kt
    │   └── UpdateInfo.kt
    └── build.gradle.kts
```

---

## Configuration client

Chaque application intègre un fichier de configuration qui indique au `System-Updater` quel adaptateur utiliser et où chercher le manifest.

```yaml
# update-config.yml
updater:
  manifest-url: https://updates.exemple.com/manifest.json
  channel: stable
  target-type: desktop          # desktop | web | service
  check-interval-minutes: 60
  auto-install: false           # true = sans confirmation utilisateur
```

---

## Points d'attention

### Sécurité
- Toutes les communications se font en **HTTPS** uniquement
- Chaque artefact est signé ou accompagné d'un **checksum SHA-256** vérifié avant installation
- Le manifest lui-même peut être signé (ex. avec une clé RSA) pour éviter les attaques de type MITM

### Rollback
- Avant toute installation, le `System-Updater` crée une sauvegarde de l'état courant
- En cas d'échec, la sauvegarde est restaurée automatiquement
- Un journal d'événements est conservé pour faciliter le diagnostic

### Redémarrage selon la cible
- **Desktop** : le processus est arrêté, le binaire remplacé, puis relancé (éventuellement via un launcher externe)
- **Web** : rechargement à chaud si possible (ex. swap de dossier avec lien symbolique), sinon redéploiement
- **Service** : appel à `systemctl restart` (Linux) ou `sc stop/start` (Windows)

### Évolutivité
- Ajouter un nouveau type de cible revient à implémenter `UpdaterStrategy` et l'enregistrer dans la configuration — aucune modification du cœur du système n'est nécessaire
- Les canaux (stable, beta, nightly) permettent de gérer plusieurs flux de distribution depuis un seul serveur

---

## Prochaines étapes suggérées

1. Implémenter le `shared` module (modèles `Version`, `UpdateInfo`)
2. Développer le `core` du `System-Updater` (pipeline générique + client HTTP)
3. Implémenter la première stratégie selon la priorité du projet
4. Développer le `Self-Updater` en dernier (il dépend de l'interface finale du `System-Updater`)
5. Mettre en place le serveur de manifest (fichier statique suffisant pour commencer)
