package com.updatesystem.self.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SelfUpdaterConfig {

    @JsonProperty("self-updater")
    public SelfUpdaterProperties selfUpdater;

    public static class SelfUpdaterProperties {

        @JsonProperty("channel")
        public String channel = "stable";

        @JsonProperty("source")
        public SourceProperties source;

        @JsonProperty("system-updater")
        public SystemUpdaterProperties systemUpdater;
    }

    public static class SourceProperties {

        @JsonProperty("type")
        public String type;

        @JsonProperty("manifest-url")
        public String manifestUrl;
    }

    public static class SystemUpdaterProperties {

        @JsonProperty("jar-path")
        public String jarPath;

        /** Fichier texte contenant la version actuellement installée du System-Updater. */
        @JsonProperty("version-file")
        public String versionFile;

        @JsonProperty("config-path")
        public String configPath;

        @JsonProperty("java-executable")
        public String javaExecutable = "java";
    }
}
