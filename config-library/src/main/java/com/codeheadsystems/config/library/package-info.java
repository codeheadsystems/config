/**
 * In-process configuration reader for applications that do not want to call the config service.
 * {@link com.codeheadsystems.config.library.LibraryConfigurationFactory} reads the application's
 * settings from a defaults datastore (such as an export of the live datastore), read once, with an
 * updates datastore on top whose values take precedence and which is polled for changes.
 */
package com.codeheadsystems.config.library;
