package com.updatesystem.strategies;

import com.updatesystem.core.UpdaterException;
import com.updatesystem.shared.UpdateInfo;
import com.updatesystem.shared.Version;

import java.nio.file.Path;

public interface UpdaterStrategy {
    boolean needsUpdate(Version current, Version available);
    Path download(UpdateInfo info) throws UpdaterException;
    Path backup() throws UpdaterException;
    void install(Path artifact) throws UpdaterException;
    void restart() throws UpdaterException;
    void rollback(Path backup) throws UpdaterException;
}
