/**
 * A SQL implementation of the datastore SPI, written for PostgreSQL. Queries are JDBI SqlObjects
 * ({@link com.codeheadsystems.config.datastore.sql.ConfigSetDao}); the schema is created by Flyway
 * migrations ({@link com.codeheadsystems.config.datastore.sql.SqlConfigSchema}).
 *
 * <p>{@link com.codeheadsystems.config.datastore.sql.SqlConfigDatastore} carries standard
 * {@code jakarta.inject} annotations, so any JSR-330 container (Dagger, Spring, Guice) can build it
 * from a {@link org.jdbi.v3.core.Jdbi} the application provides. Without a container, use the
 * constructors directly or {@link com.codeheadsystems.config.datastore.sql.SqlConfigDatastoreProvider}.
 */
package com.codeheadsystems.config.datastore.sql;
