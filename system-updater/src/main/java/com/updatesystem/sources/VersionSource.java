package com.updatesystem.sources;

import com.updatesystem.shared.Manifest;

public interface VersionSource {
    Manifest fetchManifest(String channel) throws SourceException;
}
