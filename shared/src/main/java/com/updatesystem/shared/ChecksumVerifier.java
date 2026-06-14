package com.updatesystem.shared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ChecksumVerifier {

    private static final String SHA256_PREFIX = "sha256:";

    public void verify(Path file, String expectedChecksum) throws ChecksumException {
        if (!expectedChecksum.startsWith(SHA256_PREFIX)) {
            throw new ChecksumException("Format de checksum non supporté : " + expectedChecksum);
        }
        String expectedHex = expectedChecksum.substring(SHA256_PREFIX.length());
        String actualHex = computeSha256(file);
        if (!actualHex.equalsIgnoreCase(expectedHex)) {
            throw new ChecksumException(
                "Checksum invalide pour " + file.getFileName() +
                " — attendu : " + expectedHex + ", obtenu : " + actualHex
            );
        }
    }

    private String computeSha256(Path file) throws ChecksumException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible sur cette JVM", e);
        } catch (IOException e) {
            throw new ChecksumException("Impossible de lire le fichier : " + file, e);
        }
    }
}
