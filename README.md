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

### Pour builder le projet (dev/CI)

| Outil | Version minimale |
|---|---|
| Java (JDK) | 21 LTS |
| Maven | 3.8+ |
| Python *(tests uniquement)* | 3.x |

`mvnw`/`mvnw.cmd` (Maven Wrapper) sont fournis pour ne pas dépendre d'une installation Maven locale.

### Sur le poste client (exécution)

**Aucun prérequis** : `jvmw.cmd` (JVM Wrapper, voir plus bas) provisionne automatiquement un JRE 21 au premier lancement. Un `java` déjà installé et dans le PATH reste utilisable si préféré (voir `self-updater-config.sample.yml`).

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
├── jvmw.cmd + .jvm/wrapper/  JVM Wrapper — provisionne un JRE 21 côté client
├── mvnw(.cmd) + .mvn/        Maven Wrapper — provisionne Maven côté dev/CI
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

Les fichiers de config réels contiennent des chemins/URLs propres à chaque poste et ne sont donc **pas versionnés** (voir `.gitignore`). Deux fichiers d'exemple sont fournis comme point de départ :
- `system-updater/update-config.sample.yml`
- `self-updater/self-updater-config.sample.yml`

Copier le sample vers son nom sans `.sample` (même dossier ou ailleurs) et adapter les valeurs.

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

### Sans Java pré-installé sur le poste client — via le JVM Wrapper

```bash
jvmw.cmd -jar self-updater.jar C:\Apps\updater\self-updater-config.yml
```

`jvmw.cmd` télécharge et vérifie (SHA-256) un JRE 21 Temurin au premier lancement dans `.jvm\current\`, puis délègue à `java.exe`. Voir [JVM Wrapper](#jvm-wrapper) plus bas.

### Planification Windows (recommandé)

Créer une tâche planifiée Windows qui exécute le Self-Updater à l'intervalle souhaité :

```
Programme : C:\Apps\updater\jvmw.cmd
Arguments : -jar C:\Apps\updater\self-updater.jar C:\Apps\updater\self-updater-config.yml
Déclencheur : toutes les 60 minutes (ou au démarrage de session)
```

Le Task Scheduler Windows exécute nativement les scripts `.cmd` comme programme — aucune installation Java préalable sur le poste n'est donc requise. Si un `java` est déjà présent et dans le PATH, `Programme : java` reste utilisable directement.

---

## JVM Wrapper

Comme le Maven Wrapper (`mvnw`/`mvnw.cmd`) évite de dépendre d'une installation Maven locale côté dev/CI, le **JVM Wrapper** (`jvmw.cmd`) évite de dépendre d'une installation Java locale côté **poste client**.

Fonctionnement, au premier lancement :
1. Lit `.jvm\wrapper\jvm-wrapper.properties` (`distributionUrl`, `distributionSha256Sum`) — fichier versionné, sur le modèle de `.mvn\wrapper\maven-wrapper.properties`.
2. Télécharge le JRE 21 (Eclipse Temurin, build Windows x64) et vérifie son checksum SHA-256.
3. L'extrait dans `.jvm\current\` (non versionné, voir `.gitignore`).
4. Délègue l'exécution à `.jvm\current\bin\java.exe %*`.

Les lancements suivants réutilisent directement le runtime déjà provisionné, sans aucun accès réseau.

**Distribuer `jvmw.cmd` + `.jvm\wrapper\jvm-wrapper.properties`** avec le reste de l'installation cliente (dossier `C:\Apps\updater\`) suffit : le poste n'a besoin d'aucun prérequis Java.

**Chaînage avec le Self-Updater** : une fois `jvmw.cmd` exécuté (donc le JRE provisionné), configurer `java-executable` dans `self-updater-config.yml` pour pointer directement sur `.jvm\current\bin\java.exe` plutôt que sur `jvmw.cmd` — `ProcessBuilder` (utilisé par le Self-Updater pour lancer le System-Updater) n'exécute pas les scripts `.cmd` de façon fiable sur Windows, contrairement à un `.exe` réel. Voir `self-updater-config.sample.yml`.

**Mettre à jour la version du JRE** : régénérer `distributionUrl`/`distributionSha256Sum` depuis l'[API Adoptium](https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows&architecture=x64&image_type=jre&vendor=eclipse) (champs `package.link` et `package.checksum`), puis supprimer `.jvm\current\` sur les postes déjà provisionnés pour forcer le re-téléchargement.

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

**4. Lancer le Self-Updater** *(terminal 2, depuis `test-env/`)*

```bash
java -jar ..\self-updater\target\self-updater-1.0.0-SNAPSHOT.jar self-updater-config-test.yml
```

**Résultat attendu :** `system-updater-version.txt` passe de `1.0.0` à `1.0.1`, et `system-updater-runtime.jar` est créé/remplacé dans `test-env/`. Le lancement du System-Updater qui suit échouera (le JAR de test généré par `setup-test.py` est un JAR factice sans code réel) — c'est attendu, seul le mécanisme de remplacement est testé ici.

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
| JVM Wrapper (JRE 21 auto-provisionné côté client) | ✅ Disponible |
| ServiceUpdaterStrategy (PowerShell + .NET) | En cours |
| LdapVersionSource | Prévu |
