package com.updatesystem.sources.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.updatesystem.shared.Manifest;
import com.updatesystem.sources.SourceException;
import com.updatesystem.sources.VersionSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpVersionSource implements VersionSource {

    private final String manifestUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpVersionSource(String manifestUrl) {
        this.manifestUrl = manifestUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Manifest fetchManifest(String channel) throws SourceException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SourceException("Impossible de joindre le serveur de manifest : " + manifestUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Requête interrompue vers : " + manifestUrl, e);
        }

        if (response.statusCode() != 200) {
            throw new SourceException(
                "Le serveur a répondu HTTP " + response.statusCode() + " pour : " + manifestUrl
            );
        }

        try {
            Manifest manifest = objectMapper.readValue(response.body(), Manifest.class);
            if (!channel.equals(manifest.channel())) {
                throw new SourceException(
                    "Canal inattendu dans le manifest : attendu '" + channel +
                    "', reçu '" + manifest.channel() + "'"
                );
            }
            return manifest;
        } catch (IOException e) {
            throw new SourceException("Impossible de désérialiser le manifest JSON", e);
        }
    }
}
