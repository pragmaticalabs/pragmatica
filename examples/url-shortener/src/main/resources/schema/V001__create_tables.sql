CREATE TABLE urls (
    short_code TEXT PRIMARY KEY,
    original_url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_urls_original_url ON urls(original_url);

CREATE TABLE clicks (
    id BIGSERIAL PRIMARY KEY,
    short_code TEXT NOT NULL REFERENCES urls(short_code),
    clicked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_clicks_short_code ON clicks(short_code);
