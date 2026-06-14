package com.updatesystem.shared;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateInfo(
    @JsonProperty("version")      Version version,
    @JsonProperty("url")          String downloadUrl,
    @JsonProperty("checksum")     String checksum,
    @JsonProperty("releaseNotes") String releaseNotes
) {}
