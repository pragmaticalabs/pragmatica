-- URL Shortener Schema
-- Initialization script for H2 database

CREATE TABLE IF NOT EXISTS urls (
    id IDENTITY PRIMARY KEY,
    short_code VARCHAR(10) UNIQUE NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls(short_code);
CREATE INDEX IF NOT EXISTS idx_urls_original_url ON urls(original_url);

CREATE TABLE IF NOT EXISTS clicks (
    id IDENTITY PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL,
    clicked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (short_code) REFERENCES urls(short_code)
);

CREATE INDEX IF NOT EXISTS idx_clicks_short_code ON clicks(short_code);
