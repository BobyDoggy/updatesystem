"""
setup-test.py — Prépare l'environnement de test local pour le System-Updater.

Ce script :
  1. Crée une fausse installation WinDev (v1.0.0) dans fake-install/
  2. Crée une archive ZIP représentant la v1.0.1 dans artifacts/
  3. Calcule le checksum SHA-256 du ZIP
  4. Génère manifest.json avec les URLs pointant vers localhost:8080
  5. Génère system-updater-version.txt avec la version courante du System-Updater

Usage :
  python setup-test.py [port]   (port par défaut : 8080)
"""

import hashlib
import json
import os
import shutil
import sys
import zipfile
from pathlib import Path

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
BASE_URL = f"http://localhost:{PORT}"

ROOT = Path(__file__).parent
FAKE_INSTALL = ROOT / "fake-install"
ARTIFACTS = ROOT / "artifacts"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


# ping.exe : vrai binaire PE Windows, petit (~17 Ko), toujours présent,
# s'exécute et se termine immédiatement sans argument valide — parfait pour simuler
# le restart d'une application dans un contexte de test.
PING_EXE = Path(r"C:\Windows\System32\ping.exe")

# ------------------------------------------------------------------
# 1. Fausse installation WinDev v1.0.0 (état "avant mise à jour")
# ------------------------------------------------------------------
print(">>> Création de la fausse installation WinDev (v1.0.0)...")
if FAKE_INSTALL.exists():
    shutil.rmtree(FAKE_INSTALL)
FAKE_INSTALL.mkdir()

shutil.copy(PING_EXE, FAKE_INSTALL / "MonAppli.exe")
(FAKE_INSTALL / "MonAppli.wdl").write_text("FAKE WDL v1.0.0")
(FAKE_INSTALL / "config.ini").write_text("[app]\nversion=1.0.0\n")
print(f"    {FAKE_INSTALL}")

# ------------------------------------------------------------------
# 2. Archive ZIP de la nouvelle version WinDev v1.0.1
# ------------------------------------------------------------------
print(">>> Création de l'artefact WinDev v1.0.1 (ZIP)...")
ARTIFACTS.mkdir(exist_ok=True)
zip_path = ARTIFACTS / "app-windev-1.0.1.zip"
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
    zf.write(PING_EXE, "MonAppli.exe")
    zf.writestr("MonAppli.wdl",  "FAKE WDL v1.0.1")
    zf.writestr("config.ini",    "[app]\nversion=1.0.1\n")
print(f"    {zip_path}")

checksum_windev = sha256(zip_path)
print(f"    checksum : {checksum_windev}")

# ------------------------------------------------------------------
# 3. Archive ZIP du System-Updater v1.0.1
#    (un JAR factice suffit pour tester le Self-Updater)
# ------------------------------------------------------------------
print(">>> Création de l'artefact System-Updater v1.0.1 (JAR factice)...")
jar_path = ARTIFACTS / "system-updater-1.0.1.jar"
with zipfile.ZipFile(jar_path, "w") as zf:
    zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: com.updatesystem.SystemUpdaterMain\n")
print(f"    {jar_path}")

checksum_updater = sha256(jar_path)
print(f"    checksum : {checksum_updater}")

# ------------------------------------------------------------------
# 4. manifest.json
# ------------------------------------------------------------------
print(">>> Génération de manifest.json...")
manifest = {
    "channel": "stable",
    "updater": {
        "version": "1.0.1",
        "url": f"{BASE_URL}/artifacts/system-updater-1.0.1.jar",
        "checksum": checksum_updater,
        "releaseNotes": "Version de test du System-Updater"
    },
    "targets": {
        "windev": {
            "version": "1.0.1",
            "url": f"{BASE_URL}/artifacts/app-windev-1.0.1.zip",
            "checksum": checksum_windev,
            "releaseNotes": "Version de test de l'application WinDev"
        }
    }
}
manifest_path = ROOT / "manifest.json"
manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
print(f"    {manifest_path}")

# ------------------------------------------------------------------
# 5. Fichier de version du System-Updater (pour le Self-Updater)
# ------------------------------------------------------------------
version_file = ROOT / "system-updater-version.txt"
version_file.write_text("1.0.0")
print(f">>> system-updater-version.txt créé : {version_file}")

# ------------------------------------------------------------------
# Résumé
# ------------------------------------------------------------------
print()
print("=" * 60)
print("Environnement de test prêt.")
print()
print("Démarrer le serveur HTTP dans ce dossier :")
print(f"    python -m http.server {PORT}")
print()
print("Puis lancer le System-Updater :")
print(f"    java -jar ../system-updater/target/system-updater-1.0.0-SNAPSHOT.jar update-config-test.yml")
print("=" * 60)
