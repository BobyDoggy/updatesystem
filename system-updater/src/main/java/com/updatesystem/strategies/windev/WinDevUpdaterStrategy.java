package com.updatesystem.strategies.windev;

import com.updatesystem.core.UpdaterException;
import com.updatesystem.shared.UpdateInfo;
import com.updatesystem.shared.Version;
import com.updatesystem.strategies.UpdaterStrategy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WinDevUpdaterStrategy implements UpdaterStrategy {

    private static final Logger log = Logger.getLogger(WinDevUpdaterStrategy.class.getName());
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path installDir;
    private final String processName;
    private final HttpClient httpClient;

    public WinDevUpdaterStrategy(Path installDir, String processName) {
        this.installDir = installDir;
        this.processName = processName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean needsUpdate(Version current, Version available) {
        return available.compareTo(current) > 0;
    }

    @Override
    public Path download(UpdateInfo info) throws UpdaterException {
        log.info("Téléchargement : " + info.downloadUrl());
        Path temp;
        try {
            temp = Files.createTempFile("windev-update-", ".zip");
        } catch (IOException e) {
            throw new UpdaterException("Impossible de créer le fichier temporaire", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(info.downloadUrl()))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                throw new UpdaterException("Téléchargement échoué — HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            throw new UpdaterException("Erreur réseau lors du téléchargement", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdaterException("Téléchargement interrompu", e);
        }

        log.info("Téléchargement terminé : " + temp);
        return temp;
    }

    @Override
    public Path backup() throws UpdaterException {
        String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
        Path backupDir = installDir.getParent().resolve(installDir.getFileName() + "-backup-" + timestamp);
        log.info("Sauvegarde vers : " + backupDir);
        try {
            copyDirectory(installDir, backupDir);
        } catch (IOException e) {
            throw new UpdaterException("Échec de la sauvegarde vers " + backupDir, e);
        }
        return backupDir;
    }

    @Override
    public void install(Path artifact) throws UpdaterException {
        stopProcess();
        log.info("Extraction de l'archive vers : " + installDir);
        try {
            extractZip(artifact, installDir);
        } catch (IOException e) {
            throw new UpdaterException("Échec de l'extraction du ZIP", e);
        }
    }

    @Override
    public void restart() throws UpdaterException {
        Path exe = installDir.resolve(processName);
        log.info("Démarrage de : " + exe);
        try {
            new ProcessBuilder(exe.toString())
                    .directory(installDir.toFile())
                    .start();
        } catch (IOException e) {
            throw new UpdaterException("Impossible de démarrer l'application : " + exe, e);
        }
    }

    @Override
    public void rollback(Path backup) throws UpdaterException {
        log.warning("Rollback — restauration depuis : " + backup);
        stopProcess();
        try {
            deleteDirectory(installDir);
            Files.move(backup, installDir, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UpdaterException("Échec du rollback depuis " + backup, e);
        }
        log.info("Rollback terminé");
    }

    private void stopProcess() throws UpdaterException {
        log.info("Arrêt du processus : " + processName);
        try {
            Process p = new ProcessBuilder("taskkill", "/IM", processName, "/F").start();
            int exitCode = p.waitFor();
            // 128 = processus introuvable (déjà arrêté), ce n'est pas une erreur
            if (exitCode != 0 && exitCode != 128) {
                log.warning("taskkill a retourné le code " + exitCode + " pour " + processName);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UpdaterException("Impossible d'arrêter le processus : " + processName, e);
        }
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // protection zip slip : un chemin traversant hors de targetDir est rejeté
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Entrée ZIP suspecte (path traversal) : " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
