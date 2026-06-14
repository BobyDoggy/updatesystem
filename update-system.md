# Update System — Documentation du projet

## Vue d'ensemble

Le système de mise à jour est composé de deux modules indépendants dont les responsabilités sont strictement séparées. Il est conçu pour s'adapter à différents types de **sources** (d'où viennent les informations de version) et de **cibles** (ce qui est mis à jour), via deux axes de pattern **Strategy** indépendants.

```
Source (HTTP, LDAP, …)
      │
      ▼
VersionSource  ──▶  Self-Updater       (met à jour le System-Updater)
                │
                └──▶  System-Updater   (met à jour la cible via un adaptateur)
                            │
                            └──▶ UpdaterStrategy
                                      ├── WinDevUpdaterStrategy
                                      └── ServiceUpdaterStrategy
```

---

## Stack technique

| Élément | Choix |
|---|---|
| Langage | Java 21 LTS |
| Build | Maven (multi-modules) |
| Sérialisation JSON | Jackson |
| Configuration | SnakeYAML (fichier `update-config.yml`) |
| HTTP client | `java.net.http.HttpClient` (natif Java 11+) |

---

## Modules

### 1. Self-Updater

**Rôle** : télécharger et remplacer le `System-Updater`. Ce module est minimaliste et conçu pour ne jamais nécessiter une mise à jour de lui-même.

**Responsabilités** :
- Interroger la source pour vérifier si une nouvelle version du `System-Updater` est disponible
- Télécharger l'artefact et vérifier son intégrité (hash SHA-256)
- Remplacer le JAR du `System-Updater` et le redémarrer

**Contraintes** :
- Dépendances externes réduites au minimum (stdlib Java autant que possible)
- Doit fonctionner en tant que service système ou processus en arrière-plan
- Aucun rollback nécessaire sur lui-même (il ne touche pas à la cible)

---

### 2. System-Updater

**Rôle** : orchestrer la mise à jour de la cible selon la stratégie configurée.

**Responsabilités** :
- Lire la configuration pour déterminer la source et le type de cible
- Déléguer la récupération du manifest à l'implémentation de `VersionSource`
- Appliquer le pipeline générique : vérification → téléchargement → sauvegarde → installation → redémarrage
- Déléguer les opérations spécifiques à l'implémentation de `UpdaterStrategy`
- Gérer le rollback en cas d'échec

---

## Architecture — Double pattern Strategy

### Axe 1 : la Source (`VersionSource`)

Abstrait l'origine des informations de version. Le `System-Updater` ne connaît pas le protocole utilisé.

```java
public interface VersionSource {
    Manifest fetchManifest(String channel) throws SourceException;
}
```

| Implémentation | Protocole | Statut |
|---|---|---|
| `HttpVersionSource` | HTTPS + manifest JSON | **À implémenter en premier** |
| `LdapVersionSource` | Annuaire LDAP | Prévu (phase ultérieure) |

---

### Axe 2 : la Cible (`UpdaterStrategy`)

Abstrait les opérations spécifiques à chaque type de cible.

```java
public interface UpdaterStrategy {
    boolean checkForUpdate(Version currentVersion, UpdateInfo info);
    Path download(UpdateInfo info) throws IOException;
    Path backup() throws IOException;
    void install(Path artifact) throws IOException;
    void restart() throws IOException;
    void rollback(Path backup) throws IOException;
}
```

| Implémentation | Cible | Comportement |
|---|---|---|
| `WinDevUpdaterStrategy` | Modules WinDev (client lourd) | Arrêt via `taskkill`, remplacement xcopy des fichiers, relance de l'exe |
| `ServiceUpdaterStrategy` | Service daemon (PowerShell + .NET) | Exécution de scripts PowerShell via `ProcessBuilder` pour stop/deploy/start |

---

### Modèles partagés (module `shared`)

```java
public record Version(int major, int minor, int patch)
        implements Comparable<Version> { … }

public record UpdateInfo(
    Version version,
    String downloadUrl,
    String checksum,      // SHA-256, format "sha256:<hex>"
    String releaseNotes   // nullable
) { }

public record Manifest(
    String channel,
    UpdateInfo updater,
    Map<String, UpdateInfo> targets
) { }
```

---

## Pipeline de mise à jour

```
[Démarrage]
     │
     ▼
[Lecture config]  ──▶  Instanciation VersionSource + UpdaterStrategy
     │
     ▼
[VersionSource.fetchManifest()]  ──▶  Aucune MAJ ? ──▶  [Fin]
     │
     ▼
[download + vérification checksum SHA-256]
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

## Manifest serveur (HTTP)

Le serveur expose un fichier `manifest.json` par canal que les modules interrogent.

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
    "windev": {
      "version": "3.2.0",
      "url": "https://updates.exemple.com/app-windev-3.2.0.zip",
      "checksum": "sha256:def456...",
      "releaseNotes": null
    },
    "service": {
      "version": "1.0.8",
      "url": "https://updates.exemple.com/app-service-1.0.8.zip",
      "checksum": "sha256:jkl012...",
      "releaseNotes": null
    }
  }
}
```

---

## Structure du projet Maven

```
update-system/                              ← pom.xml parent
│
├── shared/                                 ← modèles communs (Version, UpdateInfo, Manifest)
│   ├── src/main/java/com/example/updater/shared/
│   │   ├── Version.java
│   │   ├── UpdateInfo.java
│   │   └── Manifest.java
│   └── pom.xml
│
├── system-updater/                         ← module principal
│   ├── src/main/java/com/example/updater/
│   │   ├── core/
│   │   │   ├── UpdateOrchestrator.java     ← pipeline générique
│   │   │   └── ChecksumVerifier.java       ← vérification SHA-256
│   │   ├── sources/
│   │   │   ├── VersionSource.java          ← interface source
│   │   │   ├── SourceException.java
│   │   │   └── http/
│   │   │       └── HttpVersionSource.java  ← implémentation HTTP
│   │   ├── strategies/
│   │   │   ├── UpdaterStrategy.java        ← interface cible
│   │   │   ├── windev/
│   │   │   │   └── WinDevUpdaterStrategy.java
│   │   │   └── service/
│   │   │       └── ServiceUpdaterStrategy.java   ← placeholder (à préciser)
│   │   └── config/
│   │       └── UpdateConfig.java           ← lecture update-config.yml
│   └── pom.xml
│
└── self-updater/                           ← à développer en dernier
    ├── src/main/java/com/example/updater/self/
    │   └── SelfUpdater.java
    └── pom.xml
```

---

## Configuration client

```yaml
# update-config.yml
updater:
  channel: stable
  check-interval-minutes: 60
  auto-install: false

  source:
    type: http                                        # http | ldap (futur)
    manifest-url: https://updates.exemple.com/manifest.json

  target:
    type: windev                                      # windev | service
    install-dir: C:\Apps\MonAppli
    process-name: MonAppli.exe
    # Pour le type "service" (à compléter avec le collègue) :
    # service-name: MonService
    # deploy-script: C:\deploy\update-service.ps1
```

---

## Points d'attention

### Sécurité
- Toutes les communications se font en **HTTPS** uniquement
- Chaque artefact est accompagné d'un **checksum SHA-256** vérifié avant installation
- Le manifest lui-même peut être signé (clé RSA/ED25519) pour prévenir les attaques MITM

### WinDev — spécificités xcopy
- L'application WinDev doit être **arrêtée** avant tout remplacement de fichiers (verrous Windows)
- L'arrêt se fait via `taskkill /IM <process-name> /F` (appel `ProcessBuilder` depuis Java)
- La sauvegarde consiste en une copie du dossier d'installation avant remplacement
- La relance s'effectue via `Runtime.exec()` ou `ProcessBuilder` sur l'exe principal
- Les **composants WinDev** (`.wdk`, `.wdl`, DLLs) sont inclus dans l'archive ZIP déployée

### Service daemon — spécificités PowerShell
- `ServiceUpdaterStrategy` exécute des scripts PowerShell via `ProcessBuilder`
- Le script de déploiement gère les redistribuables .NET (fourni par le collègue)
- Interface à finaliser après discussion avec le collègue

### Rollback
- Avant toute installation, le `System-Updater` copie le dossier courant dans un répertoire de backup horodaté
- En cas d'échec, la copie est restaurée et le processus/service redémarré
- Un journal d'événements (fichier log) est conservé pour le diagnostic

### Extensibilité
- Ajouter une nouvelle **source** : implémenter `VersionSource` + déclarer `type` dans la config
- Ajouter une nouvelle **cible** : implémenter `UpdaterStrategy` + déclarer `type` dans la config
- Aucune modification du `UpdateOrchestrator` n'est nécessaire dans les deux cas

---

## Ordre d'implémentation

1. Module `shared` — records `Version`, `UpdateInfo`, `Manifest`
2. `ChecksumVerifier` — utilitaire SHA-256
3. `VersionSource` + `HttpVersionSource` — récupération du manifest HTTP
4. `UpdateOrchestrator` — pipeline générique
5. `WinDevUpdaterStrategy` — cible WinDev xcopy (priorité)
6. `UpdateConfig` — lecture YAML + instanciation des stratégies
7. `ServiceUpdaterStrategy` — après retour du collègue
8. `SelfUpdater` — en dernier
9. `LdapVersionSource` — phase ultérieure
