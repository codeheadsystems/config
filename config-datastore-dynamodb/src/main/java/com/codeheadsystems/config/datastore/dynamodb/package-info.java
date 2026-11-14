/**
 * Amazon DynamoDB implementation of the datastore SPI. Each set is one item in one table; see
 * {@link com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable} for the layout.
 *
 * <p>{@link com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigDatastore} carries standard
 * {@code jakarta.inject} annotations, so any JSR-330 container (Dagger, Spring, Guice) can build it
 * from a {@link software.amazon.awssdk.services.dynamodb.DynamoDbClient} and a table name the
 * application provides.
 */
package com.codeheadsystems.config.datastore.dynamodb;
