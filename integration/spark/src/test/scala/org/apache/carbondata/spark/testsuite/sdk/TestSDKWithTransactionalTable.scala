/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.spark.testsuite.sdk

import java.io.{BufferedWriter, File, FileWriter}

import org.apache.spark.sql.{CarbonEnv, Row}
import org.apache.spark.sql.test.util.QueryTest
import org.scalatest.BeforeAndAfterAll

import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.util.CarbonProperties
import org.apache.carbondata.sdk.file.{ArrowCarbonReader, CarbonReader}

class TestSDKWithTransactionalTable extends QueryTest with BeforeAndAfterAll {
  var filePath: String = _

  private def buildTestData() = {
    filePath = s"${integrationPath}/spark/target/big.csv"
    val file = new File(filePath)
    val writer = new BufferedWriter(new FileWriter(file))
    writer.write("c1, c2, c3, c4, c5, c6, c7, c8, c9, c10")
    writer.newLine()
    for (i <- 0 until 10) {
      writer.write("a" + 1%1000 + "," +
                   "b" + 1%1000 + "," +
                   "c" + 1%1000 + "," +
                   "d" + 1%1000 + "," +
                   "e" + 1%1000 + "," +
                   "f" + 1%1000 + "," +
                   1%1000 + "," +
                   1%1000 + "," +
                   1%1000 + "," +
                   1%1000 + "\n")
      if ( i % 10000 == 0) {
        writer.flush()
      }
    }
    writer.close()
  }

  private def dropTable() = {
    sql("DROP TABLE IF EXISTS carbon_load1")
    sql("DROP TABLE IF EXISTS train")
    sql("DROP TABLE IF EXISTS test")
  }

  override def beforeAll {
    dropTable
    buildTestData
  }

  test("test sdk with transactional table, read as arrow") {

    sql(
      """
        | CREATE TABLE carbon_load1(
        |    c1 string, c2 string, c3 string, c4 string, c5 string,
        |    c6 string, c7 int, c8 int, c9 int, c10 int)
        | STORED AS carbondata
      """.stripMargin)

    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")

    val table = CarbonEnv.getCarbonTable(None, "carbon_load1")(sqlContext.sparkSession)

    val reader: ArrowCarbonReader[Array[Object]] =
      CarbonReader.builder(table.getTablePath, table.getTableName).buildArrowReader()

    var count = 0
    while(reader.hasNext) {
      reader.readNextRow()
      count += 1
    }
    reader.close()
    checkAnswer(sql("select count(*) from carbon_load1"), Seq(Row(count)))
    sql("DROP TABLE carbon_load1")
  }

  test("test sdk with transactional table, read as row") {

    sql(
      """
        | CREATE TABLE carbon_load1(
        |    c1 string, c2 string, c3 string, c4 string, c5 string,
        |    c6 string, c7 int, c8 int, c9 int, c10 int)
        | STORED AS carbondata
      """.stripMargin)

    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")
    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table carbon_load1")

    val table = CarbonEnv.getCarbonTable(None, "carbon_load1")(sqlContext.sparkSession)
    val reader = CarbonReader.builder(table.getTablePath, table.getTableName).build()

    var count = 0
    while ( { reader.hasNext }) {
      var row = reader.readNextRow.asInstanceOf[Array[AnyRef]]
      count += 1
    }
    reader.close()

    checkAnswer(sql("select count(*) from carbon_load1"), Seq(Row(count)))
    sql("DROP TABLE carbon_load1")
  }

  override def afterAll {
    new File(filePath).delete()
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.LOAD_SORT_SCOPE,
        CarbonCommonConstants.LOAD_SORT_SCOPE_DEFAULT)
  }

}
