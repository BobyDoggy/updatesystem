package com.updatesystem.shared;

public class ChecksumException extends Exception {

    public ChecksumException(String message) {
        super(message);
    }

    public ChecksumException(String message, Throwable cause) {
        super(message, cause);
    }
}
