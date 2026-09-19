CREATE TABLE app_user (
    id           UUID PRIMARY KEY,
    email        TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    unit_cap     INTEGER NULL CHECK (unit_cap IS NULL OR unit_cap > 0),
    theme        TEXT NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);