-- #2401: a table whose DDL names the public schema, found by its bare name too.
CREATE TABLE public.pdocs (
    id BIGINT PRIMARY KEY,
    body JSONB
);
