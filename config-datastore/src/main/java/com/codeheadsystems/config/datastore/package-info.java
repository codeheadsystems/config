/**
 * The datastore service provider interface. A datastore stores sets of keys and values, one per
 * application, environment and {@link com.codeheadsystems.config.datastore.SetType}. Readers use
 * {@link com.codeheadsystems.config.datastore.ConfigReader}; writers use
 * {@link com.codeheadsystems.config.datastore.ConfigWriter}, whose writes are atomic and versioned.
 * Backends implement {@link com.codeheadsystems.config.datastore.ConfigDatastore} and register a
 * {@link com.codeheadsystems.config.datastore.ConfigDatastoreProvider} through
 * {@link java.util.ServiceLoader}.
 */
package com.codeheadsystems.config.datastore;
