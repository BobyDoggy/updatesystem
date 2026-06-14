package com.updatesystem.self;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.updatesystem.self.config.SelfUpdaterConfig;
import com.updatesystem.shared.ChecksumVerifier;
import com.updatesystem.shared.Manifest;
import com.updatesystem.shared.UpdateInfo;
import com.updatesystem.shared.Version;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

public class SelfUpdater {

    private static final Logger log = Logger.getLogger(SelfUpdater.class.getName());

    private final SelfUpdaterConfig.SelfUpdaterProperties config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ChecksumVerifier checksumVerifier;

    public SelfUpdater(SelfUpdaterConfig config) {
        this.config = config.selfUpdater;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.checksumVerifier = new ChecksumVerifier();
    }

    /**
     * Vérifie si le System-Updater doit être mis à jour, l'update si nécessaire,
     * puis délègue l'exécution au System-Updater.
     */
    public void run() throws Exception {
        Manifest manifest = fetchManifest();
        UpdateInfo updaterInfo = manifest.updater();

        Path versionFile = Path.of(config.systemUpdater.versionFile);
        Version current = readCurrentVersion(versionFile);

        if (updaterInfo.version().compareTo(current) > 0) {
            log.info("Mise à jour du System-Updater : " + current + " → " + updaterInfo.version());
            updateSystemUpdater(updaterInfo, versionFile);
        } else {
            log.info("System-Updater déjà à jour : " + current);
        }

        launchSystemUpdater();
    }

    private Manifest fetchManifest() throws Exception {
        log.info("Récupération du manifest : " + config.source.manifestUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.source.manifestUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête manifest interrompue", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Manifest HTTP " + response.statusCode() + " : " + config.source.manifestUrl);
        }

        return objectMapper.readValue(response.body(), Manifest.class);
    }

    private void updateSystemUpdater(UpdateInfo info, Path versionFile) throws Exception {
        Path temp = Files.createTempFile("self-updater-download-", ".jar");
        try {
            download(info.downloadUrl(), temp);
            checksumVerifier.verify(temp, info.checksum());

            Path jarPath = Path.of(config.systemUpdater.jarPath);
            Files.createDirectories(jarPath.getParent());
            Files.move(temp, jarPath, StandardCopyOption.REPLACE_EXISTING);

            Files.writeString(versionFile, info.version().toString());
            log.info("System-Updater mis à jour vers " + info.version());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void download(String url, Path destination) throws Exception {
        log.info("Téléchargement : " + url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        try {
            HttpResponse<Path> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() != 200) {
                throw new IOException("Téléchargement échoué HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Téléchargement interrompu", e);
        }
    }

    private void launchSystemUpdater() throws IOException, InterruptedException {
        Path jarPath = Path.of(config.systemUpdater.jarPath);
        Path configPath = Path.of(config.systemUpdater.configPath);

        List<String> command = List.of(
                config.systemUpdater.javaExecutable,
                "-jar",
                jarPath.toString(),
                configPath.toString()
        );

        log.info("Lancement du System-Updater : " + String.join(" ", command));
        int exitCode = new ProcessBuilder(command)
                .inheritIO()
                .start()
                .waitFor();

        if (exitCode != 0) {
            log.severe("Le System-Updater s'est terminé avec le code " + exitCode);
        }
    }

    private Version readCurrentVersion(Path versionFile) {
        try {
            if (Files.exists(versionFile)) {
                return Version.parse(Files.readString(versionFile).strip());
            }
        } catch (Exception e) {
            log.warning("Impossible de lire " + versionFile + " : " + e.getMessage());
        }
        return Version.parse("0.0.0");
    }
}
