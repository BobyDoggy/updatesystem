package com.updatesystem.self.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Path;

public class SelfUpdaterConfigLoader {

    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static SelfUpdaterConfig load(Path configFile) throws IOException {
        return YAML_MAPPER.readValue(configFile.toFile(), SelfUpdaterConfig.class);
    }
}
