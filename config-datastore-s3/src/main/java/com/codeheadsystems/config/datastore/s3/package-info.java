/**
 * Amazon S3 implementation of the datastore SPI, registered under the name {@code s3}. Each set is
 * one JSON object, written with conditional requests so every write is atomic. Works with Amazon
 * S3 and with S3-compatible stores that support conditional writes and deletes, such as RustFS.
 */
package com.codeheadsystems.config.datastore.s3;
