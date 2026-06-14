package com.updatesystem.core;

import com.updatesystem.shared.ChecksumException;
import com.updatesystem.shared.ChecksumVerifier;
import com.updatesystem.shared.Manifest;
import com.updatesystem.shared.UpdateInfo;
import com.updatesystem.shared.Version;
import com.updatesystem.sources.SourceException;
import com.updatesystem.sources.VersionSource;
import com.updatesystem.strategies.UpdaterStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class UpdateOrchestrator {

    private static final Logger log = Logger.getLogger(UpdateOrchestrator.class.getName());

    private final VersionSource source;
    private final UpdaterStrategy strategy;
    private final ChecksumVerifier checksumVerifier;

    public UpdateOrchestrator(VersionSource source, UpdaterStrategy strategy) {
        this.source = source;
        this.strategy = strategy;
        this.checksumVerifier = new ChecksumVerifier();
    }

    public UpdateResult run(String channel, String targetType, Version currentVersion)
            throws SourceException, UpdaterException {

        log.info("Vérification des mises à jour — canal : " + channel + ", cible : " + targetType);

        Manifest manifest = source.fetchManifest(channel);
        UpdateInfo info = manifest.targets().get(targetType);

        if (info == null) {
            throw new UpdaterException("Type de cible inconnu dans le manifest : " + targetType);
        }

        if (!strategy.needsUpdate(currentVersion, info.version())) {
            log.info("Déjà à jour : " + currentVersion);
            return UpdateResult.UP_TO_DATE;
        }

        log.info("Mise à jour disponible : " + currentVersion + " → " + info.version());

        Path artifact = null;
        Path backup = null;

        try {
            artifact = strategy.download(info);
            checksumVerifier.verify(artifact, info.checksum());

            backup = strategy.backup();
            strategy.install(artifact);
            strategy.restart();

            log.info("Mise à jour réussie vers " + info.version());
            return UpdateResult.UPDATED;

        } catch (ChecksumException e) {
            log.severe("Checksum invalide — mise à jour annulée : " + e.getMessage());
            throw new UpdaterException("Checksum invalide", e);

        } catch (UpdaterException e) {
            log.severe("Échec de la mise à jour : " + e.getMessage());
            if (backup != null) {
                attemptRollback(backup);
            }
            throw e;

        } finally {
            if (artifact != null) {
                try { Files.deleteIfExists(artifact); } catch (IOException ignored) {}
            }
        }
    }

    private void attemptRollback(Path backup) {
        try {
            log.warning("Tentative de rollback depuis : " + backup);
            strategy.rollback(backup);
            log.info("Rollback effectué avec succès");
        } catch (UpdaterException e) {
            log.severe("Échec du rollback : " + e.getMessage());
        }
    }
}
