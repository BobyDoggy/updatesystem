# VPS — Paramétrage du serveur de mises à jour

## État d'avancement

| Étape | Statut |
|---|---|
| Accès SSH root | ✅ Fait |
| Utilisateur SFTP `updater-deploy` + chroot | ✅ Fait |
| Structure `/srv/updates/files/` | ✅ Fait |
| nginx — serveur HTTP (conteneur Docker) | ⬜ À faire |
| HTTPS — certificat Let's Encrypt | ⬜ À faire |
| Structure des artefacts sur le serveur | ⬜ À faire |
| Manifest JSON de test | ⬜ À faire |
| Test bout en bout avec `HttpVersionSource` | ⬜ À faire |

> **Choix retenu** : nginx tourne en conteneur Docker (le VPS a déjà Docker
> installé, voir `.claude/notes/securisation-vps_09082026.md` §8) plutôt
> qu'en paquet natif. Fichiers de déploiement : `docker/nginx-updates/`
> (`docker-compose.yml` + `nginx-updates.conf`) à la racine du repo.

---

## Architecture cible du serveur

```
/srv/updates/
├── .ssh/
│   └── authorized_keys          ← clé publique du poste dev
└── files/                       ← racine HTTP + destination SFTP
    ├── manifest.json             ← canal "stable" (interrogé par les clients)
    ├── manifest-beta.json        ← canal "beta" (optionnel)
    ├── system-updater-x.x.x.jar ← artefact self-updater
    └── app-windev-x.x.x.zip     ← artefact cible WinDev
```

**Flux :**
```
Dev (Windows) ──SFTP push──▶ /srv/updates/files/
Clients Java  ──HTTP GET──▶  nginx ──▶ /srv/updates/files/
```

---

## Étape 1 — Installer et configurer nginx (conteneur Docker)

nginx tourne en conteneur plutôt qu'en paquet natif — cohérent avec l'objectif
d'isoler chaque service dans son propre conteneur (voir notes de sécu §8).
La conf (whitelist d'extensions, `autoindex off`) reste identique à une
install native, elle est juste montée en volume dans le conteneur.

**Note sécurité** : `ufw-docker` est déjà installé sur ce VPS (mis en place
pour HFSQL, notes de sécu §9) — c'est un patch **global** de la chaîne
`DOCKER-USER`, pas un correctif scopé à un seul conteneur. Une fois installé,
il force tout le trafic Docker forwardé à repasser par le filtrage UFW, donc
**chaque nouveau conteneur qui publie un port a besoin de ses propres règles
UFW**, sinon le port reste injoignable de l'extérieur (constaté : timeout tant
que les règles n'étaient pas ajoutées).

Règles nécessaires pour ce conteneur :
```bash
sudo ufw allow 80/tcp                # ouverture du port côté hôte
sudo ufw-docker allow nginx-updates 80   # autorise le forward vers le conteneur (via son nom)
sudo ufw reload
```
`ufw-docker allow <container> <port>` résout lui-même l'IP interne du
conteneur — plus simple et plus robuste qu'un `ufw route allow ... to <IP>`
manuel (l'IP change si le conteneur est recréé). À refaire pour le port 443
une fois HTTPS activé (Étape 2).

Vérifier les règles en place : `sudo ufw status numbered`. Pour retirer une
règle : `sudo ufw delete allow 80/tcp` (ou `delete` + numéro de ligne indiqué
par `status numbered`), puis `sudo ufw reload`.

**Préparer le dossier de logs sur l'hôte** (persisté hors du conteneur, pour
rester visible dans le checkup de routine §13.5 des notes de sécu) :
```bash
sudo mkdir -p /var/log/nginx-updates
```

**Déployer les fichiers de conf** — depuis le poste dev, copier
`docker/nginx-updates/` (du repo) vers le VPS, par ex. via l'accès SSH admin
(pas le compte SFTP cloisonné, qui n'a accès qu'à `/srv/updates/files`) :
```powershell
scp -r docker\nginx-updates ubuntu@<IP_VPS>:~/nginx-updates
```

**Lancer le conteneur** sur le VPS :
```bash
cd ~/nginx-updates
sudo docker compose up -d
```

Vérification :
```bash
sudo docker ps                       # nginx-updates doit être "Up"
curl -I http://<IP_VPS>/manifest.json
# Doit retourner 404 (fichier pas encore présent) — pas 403 ni 502
```

Après toute modification de `nginx-updates.conf` :
```bash
sudo docker compose restart nginx-updates
```

---

## Étape 2 — HTTPS avec Let's Encrypt (si domaine disponible)

> Si tu utilises uniquement l'IP du VPS, passer cette étape et rester en HTTP pour les tests.
> Un nom de domaine est requis pour Let's Encrypt.

```bash
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d updates.ton-domaine.com
# Suivre les instructions, accepter la redirection HTTP→HTTPS
```

Renouvellement automatique (déjà configuré par certbot) :
```bash
sudo systemctl status certbot.timer
```

Après activation HTTPS, mettre à jour le virtualhost nginx si besoin et ajuster l'URL dans `update-config.yml` des clients :
```yaml
manifest-url: https://updates.ton-domaine.com/manifest.json
```

---

## Étape 3 — Déposer un manifest de test via SFTP

### Format du manifest

```json
{
  "channel": "stable",
  "updater": {
    "version": "1.0.0",
    "url": "http://<IP_VPS>/system-updater-1.0.0.jar",
    "checksum": "sha256:<hash_sha256_du_jar>",
    "releaseNotes": "Version initiale"
  },
  "targets": {
    "windev": {
      "version": "1.0.0",
      "url": "http://<IP_VPS>/app-windev-1.0.0.zip",
      "checksum": "sha256:<hash_sha256_du_zip>",
      "releaseNotes": null
    }
  }
}
```

### Calculer le checksum SHA-256 sur Windows

```powershell
Get-FileHash .\target\system-updater-1.0.0.jar -Algorithm SHA256
# Copier la valeur "Hash" dans le manifest
```

### Pousser le manifest via SFTP

```powershell
sftp -i $env:USERPROFILE\.ssh\id_ed25519 updater-deploy@<IP_VPS>
```

```sftp
cd /files
put manifest.json manifest.json.tmp
rename manifest.json.tmp manifest.json
ls -la
exit
```

Le passage par un `.tmp` + `rename` évite qu'un client interroge nginx pendant
l'upload et reçoive un JSON tronqué (le `rename` est atomique côté
filesystem, `put` seul ne l'est pas).

Vérification depuis le navigateur ou curl :
```bash
curl http://<IP_VPS>/manifest.json
```

---

## Étape 4 — Configurer le client Java

Modifier `system-updater/src/main/resources/update-config.yml` :

```yaml
updater:
  channel: stable
  check-interval-minutes: 60
  auto-install: false

  source:
    type: http
    manifest-url: http://<IP_VPS>/manifest.json   # ou https:// si certbot configuré

  target:
    type: windev
    install-dir: C:\Apps\MonAppli
    process-name: MonAppli.exe
```

---

## Étape 5 — Test bout en bout

1. Pousser un manifest JSON de test via SFTP
2. Vérifier que `curl http://<IP_VPS>/manifest.json` retourne le bon contenu
3. Lancer `SystemUpdaterMain` depuis IntelliJ ou en ligne de commande :
   ```powershell
   java -jar target\system-updater-1.0.0-SNAPSHOT.jar
   ```
4. Vérifier les logs — le manifest doit être récupéré et parsé sans erreur

---

## Sécurité — points à valider avant mise en production

- [ ] nginx : `autoindex off` (ne pas lister les fichiers)
- [ ] nginx : restreindre les extensions servies (`.json`, `.jar`, `.zip` uniquement)
- [ ] HTTPS activé (certificat valide, pas d'avertissement navigateur)
- [ ] Pare-feu VPS : seuls les ports 22 (SSH), 80 et 443 sont ouverts
  ```bash
  sudo ufw allow OpenSSH
  sudo ufw allow 80/tcp
  sudo ufw-docker allow nginx-updates 80
  sudo ufw enable
  sudo ufw reload
  sudo ufw status numbered
  ```
  (`Nginx Full` — profil UFW du paquet nginx natif — n'existe pas ici, nginx
  tournant en conteneur ; voir Étape 1 pour le détail des deux règles requises,
  l'une côté hôte, l'autre via `ufw-docker` pour le forward vers le conteneur.)
- [ ] L'utilisateur `updater-deploy` ne peut pas se connecter en SSH (seulement SFTP)
- [ ] Les checksums SHA-256 sont vérifiés côté client avant toute installation

---

## Commandes utiles — rappel

| Action | Commande |
|---|---|
| Déposer un fichier via SFTP | `sftp -i ~/.ssh/id_ed25519 updater-deploy@<IP>` puis `put fichier` |
| Publier un manifest de façon atomique | en SFTP : `put manifest.json manifest.json.tmp` puis `rename manifest.json.tmp manifest.json` |
| Voir l'état du conteneur nginx | `sudo docker ps` |
| Vérifier les logs nginx | `sudo tail -f /var/log/nginx-updates/error.log` |
| Recharger nginx (après modif conf) | `sudo docker compose -f ~/nginx-updates/docker-compose.yml restart nginx-updates` |
| Vérifier la config nginx | `sudo docker exec nginx-updates nginx -t` |
| Voir les ports ouverts | `sudo ufw status numbered` |
| Autoriser un port pour un conteneur | `sudo ufw allow <port>/tcp && sudo ufw-docker allow <conteneur> <port> && sudo ufw reload` |
| Retirer une règle UFW | `sudo ufw delete allow <port>/tcp && sudo ufw reload` |
| Calculer SHA-256 (Windows) | `Get-FileHash fichier -Algorithm SHA256` |
| Calculer SHA-256 (Linux) | `sha256sum fichier` |
