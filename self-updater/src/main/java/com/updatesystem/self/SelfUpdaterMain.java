package com.updatesystem.self;

import com.updatesystem.self.config.SelfUpdaterConfig;
import com.updatesystem.self.config.SelfUpdaterConfigLoader;

import java.nio.file.Path;
import java.util.logging.Logger;

public class SelfUpdaterMain {

    private static final Logger log = Logger.getLogger(SelfUpdaterMain.class.getName());

    public static void main(String[] args) {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("self-updater-config.yml");
        log.info("Chargement de la configuration : " + configPath.toAbsolutePath());

        try {
            SelfUpdaterConfig config = SelfUpdaterConfigLoader.load(configPath);
            new SelfUpdater(config).run();
            System.exit(0);
        } catch (Exception e) {
            log.severe("Erreur fatale : " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
