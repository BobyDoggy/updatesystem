package com.updatesystem.core;

public class UpdaterException extends Exception {

    public UpdaterException(String message) {
        super(message);
    }

    public UpdaterException(String message, Throwable cause) {
        super(message, cause);
    }
}
