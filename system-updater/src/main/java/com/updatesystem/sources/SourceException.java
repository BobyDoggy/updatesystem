package com.updatesystem.sources;

public class SourceException extends Exception {

    public SourceException(String message) {
        super(message);
    }

    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
