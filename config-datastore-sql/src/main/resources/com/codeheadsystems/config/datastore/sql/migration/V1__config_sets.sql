-- One row per set ever written. A deleted set keeps its row, with live = false, so its version is
-- never reused: a set created again continues from it.
CREATE TABLE config_set (
    application TEXT    NOT NULL,
    environment TEXT    NOT NULL,
    set_type    TEXT    NOT NULL,
    version     BIGINT  NOT NULL,
    live        BOOLEAN NOT NULL,
    PRIMARY KEY (application, environment, set_type),
    CONSTRAINT config_set_version_not_negative CHECK (version >= 0)
);

-- The keys and values of live sets. Deleting a set removes its values.
CREATE TABLE config_value (
    application TEXT NOT NULL,
    environment TEXT NOT NULL,
    set_type    TEXT NOT NULL,
    name        TEXT NOT NULL,
    value       TEXT NOT NULL,
    PRIMARY KEY (application, environment, set_type, name),
    FOREIGN KEY (application, environment, set_type)
        REFERENCES config_set (application, environment, set_type) ON DELETE CASCADE
);
