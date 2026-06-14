package com.updatesystem.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UpdateConfig {

    @JsonProperty("updater")
    public UpdaterProperties updater;

    public static class UpdaterProperties {

        @JsonProperty("channel")
        public String channel = "stable";

        @JsonProperty("check-interval-minutes")
        public int checkIntervalMinutes = 60;

        @JsonProperty("auto-install")
        public boolean autoInstall = false;

        @JsonProperty("source")
        public SourceProperties source;

        @JsonProperty("target")
        public TargetProperties target;
    }

    public static class SourceProperties {

        @JsonProperty("type")
        public String type;

        @JsonProperty("manifest-url")
        public String manifestUrl;
    }

    public static class TargetProperties {

        @JsonProperty("type")
        public String type;

        @JsonProperty("install-dir")
        public String installDir;

        @JsonProperty("process-name")
        public String processName;

        /** Version actuellement installée. Mise à jour automatiquement par le System-Updater. */
        @JsonProperty("current-version")
        public String currentVersion = "0.0.0";
    }
}
