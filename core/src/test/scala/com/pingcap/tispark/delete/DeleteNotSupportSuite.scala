package com.pingcap.tispark.delete

import com.pingcap.tispark.datasource.BaseBatchWriteTest
import org.apache.spark.sql.AnalysisException
import org.scalatest.Matchers.{contain, convertToAnyShouldWrapper, have, include, the}

/**
 * Delete not support
 * 1.Delete without WHERE clause or with alwaysTrue WHERE clause
 * 2.Delete with subquery
 * 3.Delete from partition table
 * 4.Delete with Pessimistic Transaction Mode (no test)
 */
class DeleteNotSupportSuite extends BaseBatchWriteTest("test_delete_not_support") {

  test("Delete without WHERE clause") {
    jdbcUpdate(s"create table $dbtable(i int, s int,PRIMARY KEY (i))")

    the[IllegalArgumentException] thrownBy {
      spark.sql(s"delete from $dbtable")
    } should have message "requirement failed: Delete without WHERE clause is not supported"
  }

  test("Delete with alwaysTrue WHERE clause") {
    jdbcUpdate(s"create table $dbtable(i int, s int,PRIMARY KEY (i))")

    the[IllegalArgumentException] thrownBy {
      spark.sql(s"delete from $dbtable where 1=1")
    } should have message "requirement failed: Delete with alwaysTrue WHERE clause is not supported"
  }

  test("Delete with subquery") {
    jdbcUpdate(s"create table $dbtable(i int, s int,PRIMARY KEY (i))")

    intercept[AnalysisException] {
      spark.sql(s"delete from $dbtable where i in (select i from $dbtable)")
    }.getMessage() should include("Delete by condition with subquery is not supported")
  }

  test("Delete from partition table") {
    jdbcUpdate(
      s"create table $dbtable(i int, s int,PRIMARY KEY (i)) PARTITION BY RANGE (i) ( PARTITION p0 VALUES LESS THAN (2), PARTITION p1 VALUES LESS THAN (4), PARTITION p2 VALUES LESS THAN (6))")

    the[IllegalArgumentException] thrownBy {
      spark.sql(s"delete from $dbtable where i=1")
    } should have message "TiSpark currently does not support delete data from partition table!"
  }

}
