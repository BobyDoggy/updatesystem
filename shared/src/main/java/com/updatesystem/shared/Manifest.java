package com.updatesystem.shared;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record Manifest(
    @JsonProperty("channel") String channel,
    @JsonProperty("updater") UpdateInfo updater,
    @JsonProperty("targets") Map<String, UpdateInfo> targets
) {}
