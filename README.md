# Update System

Système de mise à jour automatique pour applications cibles, conçu autour d'un double pattern **Strategy** :
- **VersionSource** — abstrait la source d'information (HTTP aujourd'hui, LDAP prévu)
- **UpdaterStrategy** — abstrait la cible à mettre à jour (WinDev, service daemon…)

---

## Architecture

```
[Self-Updater]
     │  vérifie et remplace le System-Updater JAR
     │  puis délègue
     ▼
[System-Updater]
     │  interroge la source (VersionSource)
     │  orchestre le pipeline de mise à jour
     ▼
[UpdaterStrategy]
     ├── WinDevUpdaterStrategy   (xcopy, taskkill, relance exe)
     └── ServiceUpdaterStrategy  (PowerShell + .NET — à venir)
```

### Pipeline de mise à jour

```
fetch manifest  →  comparaison de versions
                         │
                    mise à jour disponible ?
                         │
               téléchargement + vérification SHA-256
                         │
                    sauvegarde de l'état actuel
                         │
                       installation
                         │
               ┌─────────┴──────────┐
            succès               échec
               │                    │
            restart              rollback
```

---

## Prérequis

| Outil | Version minimale |
|---|---|
| Java (JDK) | 21 LTS |
| Maven | 3.8+ |
| Python *(tests uniquement)* | 3.x |

---

## Structure du projet

```
update-system/
├── shared/                  Modèles communs : Version, UpdateInfo, Manifest,
│                            ChecksumVerifier (SHA-256)
├── system-updater/          Module principal
│   ├── core/                UpdateOrchestrator, UpdaterException, UpdateResult
│   ├── sources/             Interface VersionSource + HttpVersionSource
│   ├── strategies/          Interface UpdaterStrategy + WinDevUpdaterStrategy
│   └── config/              Lecture de update-config.yml
├── self-updater/            Module autonome et minimaliste
│   └── ...                  Vérifie / remplace le System-Updater, puis le lance
└── test-env/                Environnement de test local (serveur HTTP Python)
    ├── setup-test.py        Génère les artefacts et le manifest de test
    └── update-config-test.yml
```

---

## Build

```bash
mvn package
```

Produit deux fat JARs autonomes :
- `system-updater/target/system-updater-1.0.0-SNAPSHOT.jar`
- `self-updater/target/self-updater-1.0.0-SNAPSHOT.jar`

---

## Configuration

### System-Updater — `update-config.yml`

```yaml
updater:
  channel: stable                   # stable | beta | ...
  check-interval-minutes: 60
  auto-install: false

  source:
    type: http                      # http | ldap (à venir)
    manifest-url: https://updates.exemple.com/manifest.json

  target:
    type: windev                    # windev | service (à venir)
    install-dir: C:\Apps\MonAppli
    process-name: MonAppli.exe
    current-version: 1.0.0
```

### Self-Updater — `self-updater-config.yml`

```yaml
self-updater:
  channel: stable

  source:
    type: http
    manifest-url: https://updates.exemple.com/manifest.json

  system-updater:
    jar-path: C:\Apps\updater\system-updater.jar
    version-file: C:\Apps\updater\system-updater-version.txt
    config-path: C:\Apps\updater\update-config.yml
    java-executable: java
```

### Format du manifest serveur

Le serveur expose un fichier `manifest.json` par canal :

```json
{
  "channel": "stable",
  "updater": {
    "version": "1.4.2",
    "url": "https://updates.exemple.com/artifacts/system-updater-1.4.2.jar",
    "checksum": "sha256:abc123...",
    "releaseNotes": "Correctifs de stabilité"
  },
  "targets": {
    "windev": {
      "version": "3.2.0",
      "url": "https://updates.exemple.com/artifacts/app-windev-3.2.0.zip",
      "checksum": "sha256:def456...",
      "releaseNotes": null
    }
  }
}
```

Le checksum doit être au format `sha256:<hex>`. Le serveur peut être un simple hébergement de fichiers statiques (IIS, Nginx, S3…).

---

## Lancement

### System-Updater seul

```bash
java -jar system-updater.jar [chemin/vers/update-config.yml]
```

Si le chemin n'est pas précisé, le fichier `update-config.yml` est cherché dans le répertoire courant.

### Chaîne complète via le Self-Updater

```bash
java -jar self-updater.jar [chemin/vers/self-updater-config.yml]
```

Le Self-Updater met à jour le System-Updater si nécessaire, puis le lance automatiquement.

### Planification Windows (recommandé)

Créer une tâche planifiée Windows qui exécute le Self-Updater à l'intervalle souhaité :

```
Programme : java
Arguments : -jar C:\Apps\updater\self-updater.jar C:\Apps\updater\self-updater-config.yml
Déclencheur : toutes les 60 minutes (ou au démarrage de session)
```

---

## Tests en local

### Prérequis

- Python 3.x installé
- Le projet buildé (`mvn package`)

### Procédure

**1. Générer l'environnement de test**

```bash
cd test-env
python setup-test.py
```

Cela crée :
- `fake-install/` — installation WinDev simulée en v1.0.0
- `artifacts/` — archive ZIP v1.0.1 + JAR System-Updater v1.0.1
- `manifest.json` — manifest pointant vers `localhost:8080`

**2. Démarrer le serveur HTTP** *(terminal 1, depuis `test-env/`)*

```bash
python -m http.server 8080
```

**3. Lancer le System-Updater** *(terminal 2, depuis `test-env/`)*

```bash
java -jar ..\system-updater\target\system-updater-1.0.0-SNAPSHOT.jar update-config-test.yml
```

**Résultat attendu :** les fichiers de `fake-install/` passent en v1.0.1.  
Relancer une seconde fois → "Déjà à jour", aucune action effectuée.

---

## Sécurité

- Communications **HTTPS uniquement** en production
- Chaque artefact est vérifié par **checksum SHA-256** avant installation
- Protection contre le **zip slip** (path traversal dans les archives)
- Rollback automatique en cas d'échec d'installation

---

## Feuille de route

| Fonctionnalité | Statut |
|---|---|
| HttpVersionSource | ✅ Disponible |
| WinDevUpdaterStrategy (xcopy) | ✅ Disponible |
| SelfUpdater | ✅ Disponible |
| ServiceUpdaterStrategy (PowerShell + .NET) | En cours |
| LdapVersionSource | Prévu |
