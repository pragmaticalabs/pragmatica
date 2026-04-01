package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.example.urlshortener.shortener.UrlPersistence.UrlRow;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;

/// In-memory UrlPersistence implementation for testing.
final class InMemoryUrlPersistence implements UrlPersistence {
    private final Map<String, String> urlsByCode = new ConcurrentHashMap<>();
    private final Map<String, String> codesByUrl = new ConcurrentHashMap<>();

    static InMemoryUrlPersistence inMemoryUrlPersistence() {
        return new InMemoryUrlPersistence();
    }

    @Override
    public Promise<Option<UrlRow>> findByOriginalUrl(String originalUrl) {
        return Promise.success(option(codesByUrl.get(originalUrl)).map(code -> new UrlRow(code, originalUrl)));
    }

    @Override
    public Promise<Option<UrlRow>> findByShortCode(String shortCode) {
        return Promise.success(option(urlsByCode.get(shortCode)).map(url -> new UrlRow(shortCode, url)));
    }

    @Override
    public Promise<Unit> insertUrl(String shortCode, String originalUrl) {
        urlsByCode.put(shortCode, originalUrl);
        codesByUrl.put(originalUrl, shortCode);
        return Promise.success(Unit.unit());
    }
}
