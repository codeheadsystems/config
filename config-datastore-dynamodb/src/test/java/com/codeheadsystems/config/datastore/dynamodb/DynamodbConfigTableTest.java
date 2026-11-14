package com.codeheadsystems.config.datastore.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.SortKey;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import org.junit.jupiter.api.Test;

class DynamodbConfigTableTest {

  @Test
  void sortKeysRoundTrip() {
    final SetId id = SetId.metadata(Application.from("billing"), Environment.from("prod-eu"));

    assertThat(DynamodbConfigTable.sortKey(id)).isEqualTo("prod-eu#METADATA");
    assertThat(DynamodbConfigTable.parseSortKey("prod-eu#METADATA")).contains(new SortKey("prod-eu", SetType.METADATA));
  }

  @Test
  void foreignSortKeysAreIgnored() {
    assertThat(DynamodbConfigTable.parseSortKey("prod")).isEmpty();
    assertThat(DynamodbConfigTable.parseSortKey("#SETTINGS")).isEmpty();
    assertThat(DynamodbConfigTable.parseSortKey("prod#OTHER")).isEmpty();
  }
}
