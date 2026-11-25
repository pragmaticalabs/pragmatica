-- #2795: the forms in which PostgreSQL makes a column NOT NULL although its definition does not say NOT NULL (table-level and
-- added primary keys, serial types), each beside a nullable `note` (NotNullFormsCrudValidationTest).
CREATE TABLE pk_table (
    left_id BIGINT,
    right_id BIGINT,
    note TEXT,
    PRIMARY KEY (left_id, right_id)
);

CREATE TABLE pk_first (
    CONSTRAINT pk_first_pkey PRIMARY KEY (left_id, right_id) INCLUDE (note),
    left_id BIGINT,
    right_id BIGINT,
    note TEXT
);

CREATE TABLE pk_added (
    left_id BIGINT,
    right_id BIGINT,
    note TEXT
);
ALTER TABLE pk_added ADD PRIMARY KEY (left_id, right_id);

CREATE TABLE pk_named (
    left_id BIGINT,
    right_id BIGINT,
    note TEXT
);
ALTER TABLE ONLY pk_named ADD CONSTRAINT pk_named_pkey PRIMARY KEY (left_id, right_id);

CREATE TABLE pk_dropped (
    left_id BIGINT,
    right_id BIGINT,
    note TEXT,
    CONSTRAINT pk_dropped_pkey PRIMARY KEY (left_id, right_id)
);
ALTER TABLE pk_dropped DROP CONSTRAINT pk_dropped_pkey;

CREATE TABLE pk_relaxed (
    left_id BIGINT,
    right_id BIGINT,
    note TEXT,
    CONSTRAINT pk_relaxed_pkey PRIMARY KEY (left_id, right_id)
);
ALTER TABLE pk_relaxed DROP CONSTRAINT pk_relaxed_pkey;
ALTER TABLE pk_relaxed ALTER COLUMN left_id DROP NOT NULL;

CREATE TABLE serial_forms (
    small_n SMALLSERIAL,
    small_two SERIAL2,
    plain_n SERIAL,
    plain_four SERIAL4,
    big_n BIGSERIAL,
    big_eight SERIAL8,
    note TEXT
);
ALTER TABLE serial_forms ADD COLUMN late_n BIGSERIAL;

CREATE TABLE stored_forms (
    id BIGINT PRIMARY KEY,
    amount NUMERIC,
    doubled NUMERIC GENERATED ALWAYS AS (amount * 2) STORED,
    tripled NUMERIC GENERATED ALWAYS AS (amount * 3) STORED NOT NULL
);
