/**
 * In-process configuration reader for applications that do not want to call the config service.
 * It reads from a defaults datastore (such as an export of the live datastore) with an updates datastore attached
 * on top, whose values take precedence.
 */
package com.codeheadsystems.config.library;
