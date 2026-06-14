package com.updatesystem;

import com.updatesystem.config.UpdateConfig;
import com.updatesystem.config.UpdateConfigLoader;
import com.updatesystem.core.UpdateOrchestrator;
import com.updatesystem.core.UpdateResult;
import com.updatesystem.shared.Version;
import com.updatesystem.sources.VersionSource;
import com.updatesystem.sources.http.HttpVersionSource;
import com.updatesystem.strategies.UpdaterStrategy;
import com.updatesystem.strategies.windev.WinDevUpdaterStrategy;

import java.nio.file.Path;
import java.util.logging.Logger;

public class SystemUpdaterMain {

    private static final Logger log = Logger.getLogger(SystemUpdaterMain.class.getName());

    public static void main(String[] args) {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("update-config.yml");
        log.info("Chargement de la configuration : " + configPath.toAbsolutePath());

        try {
            UpdateConfig config = UpdateConfigLoader.load(configPath);
            UpdateConfig.UpdaterProperties updater = config.updater;

            VersionSource source = buildSource(updater.source);
            UpdaterStrategy strategy = buildStrategy(updater.target);
            Version currentVersion = Version.parse(updater.target.currentVersion);

            UpdateOrchestrator orchestrator = new UpdateOrchestrator(source, strategy);
            UpdateResult result = orchestrator.run(updater.channel, updater.target.type, currentVersion);

            log.info("Terminé — résultat : " + result);
            System.exit(0);

        } catch (Exception e) {
            log.severe("Erreur fatale : " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static VersionSource buildSource(UpdateConfig.SourceProperties src) {
        return switch (src.type) {
            case "http" -> new HttpVersionSource(src.manifestUrl);
            default -> throw new IllegalArgumentException("Type de source non supporté : " + src.type);
        };
    }

    private static UpdaterStrategy buildStrategy(UpdateConfig.TargetProperties target) {
        return switch (target.type) {
            case "windev" -> new WinDevUpdaterStrategy(
                    Path.of(target.installDir),
                    target.processName
            );
            default -> throw new IllegalArgumentException("Type de cible non supporté : " + target.type);
        };
    }
}
