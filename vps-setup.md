# VPS — Paramétrage du serveur de mises à jour

## État d'avancement

| Étape | Statut |
|---|---|
| Accès SSH root | ✅ Fait |
| Utilisateur SFTP `updater-deploy` + chroot | ✅ Fait |
| Structure `/srv/updates/files/` | ✅ Fait |
| nginx — serveur HTTP | ⬜ À faire |
| HTTPS — certificat Let's Encrypt | ⬜ À faire |
| Structure des artefacts sur le serveur | ⬜ À faire |
| Manifest JSON de test | ⬜ À faire |
| Test bout en bout avec `HttpVersionSource` | ⬜ À faire |

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

## Étape 1 — Installer et configurer nginx

```bash
sudo apt update && sudo apt install nginx -y
```

Créer le virtualhost :
```bash
sudo nano /etc/nginx/sites-available/updates
```

```nginx
server {
    listen 80;
    server_name <IP_ou_domaine_VPS>;

    root /srv/updates/files;
    autoindex off;

    # Seuls les fichiers connus sont servis
    location ~* \.(json|jar|zip)$ {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
        try_files $uri =404;
    }

    # Bloquer tout accès aux autres extensions
    location / {
        return 403;
    }
}
```

Activer le site :
```bash
sudo ln -s /etc/nginx/sites-available/updates /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

Vérification :
```bash
curl -I http://<IP_VPS>/manifest.json
# Doit retourner 404 (fichier pas encore présent) — pas 403 ni 502
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
put manifest.json
ls -la
exit
```

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
  sudo ufw allow 'Nginx Full'
  sudo ufw enable
  sudo ufw status
  ```
- [ ] L'utilisateur `updater-deploy` ne peut pas se connecter en SSH (seulement SFTP)
- [ ] Les checksums SHA-256 sont vérifiés côté client avant toute installation

---

## Commandes utiles — rappel

| Action | Commande |
|---|---|
| Déposer un fichier via SFTP | `sftp -i ~/.ssh/id_ed25519 updater-deploy@<IP>` puis `put fichier` |
| Vérifier les logs nginx | `sudo tail -f /var/log/nginx/error.log` |
| Recharger nginx | `sudo systemctl reload nginx` |
| Vérifier la config nginx | `sudo nginx -t` |
| Voir les ports ouverts | `sudo ufw status` |
| Calculer SHA-256 (Windows) | `Get-FileHash fichier -Algorithm SHA256` |
| Calculer SHA-256 (Linux) | `sha256sum fichier` |
