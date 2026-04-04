CREATE TABLE measurements (
    id bigserial NOT NULL,
    sensor_id integer NOT NULL,
    value numeric(10,4) NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (recorded_at, id)
) PARTITION BY RANGE (recorded_at);
