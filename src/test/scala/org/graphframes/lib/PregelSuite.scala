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

package org.graphframes.lib

import org.scalactic.Tolerance._

import org.apache.spark.sql.functions._

import org.graphframes._

class PregelSuite extends SparkFunSuite with GraphFrameTestSparkContext {

  import testImplicits._

  test("page rank") {
    val edges = Seq(
      (0L, 1L),
      (1L, 2L),
      (2L, 4L),
      (2L, 0L),
      (3L, 4L),
      (4L, 3L),
      (4L, 0L),
      (4L, 2L)
    ).toDF("src", "dst").cache()
    val vertices = GraphFrame.fromEdges(edges).outDegrees.cache()
    val numVertices = vertices.count()
    val graph = GraphFrame(vertices, edges)

    val alpha = 0.15
    // NOTE: This version doesn't handle nodes with no out-links, and nodes with no in-links
    val ranks = graph.pregel
      .setMaxIter(5)
      .withVertexColumn("rank", lit(1.0 / numVertices),
        coalesce(Pregel.msg, lit(0.0)) * (1.0 - alpha) + alpha / numVertices)
      .sendMsgToDst(Pregel.src("rank") / Pregel.src("outDegree"))
      .aggMsgs(sum(Pregel.msg))
      .run()

    val result = ranks.sort(col("id"))
      .select("rank").as[Double].collect()
    assert(result.sum === 1.0 +- 1e-6)
    val expected = Seq(0.209, 0.200, 0.266, 0.089, 0.234)
    result.zip(expected).foreach { case (r, e) =>
      assert(r === e +- 1e-3)
    }
  }

  test("chain propagation") {
    val n = 5
    val verDF = (1 to n).toDF("id").repartition(3)
    val edgeDF = (1 until n).map(x => (x, x + 1))
      .toDF("src", "dst").repartition(3)

    val graph = GraphFrame(verDF, edgeDF)

    val resultDF = graph.pregel
      .setMaxIter(2*n)
      // The vertex with id 1 starts with value 1, rest starts with 0
      .withVertexColumn("value",
        when(col("id") === lit(1), lit(1)).otherwise(lit(0)),
        // Update when receiving a larger value
        when(Pregel.msg > col("value"), Pregel.msg).otherwise(col("value"))
      )
      .sendMsgToDst(
        // Only send message if the value is different
        when(Pregel.dst("value") =!= Pregel.src("value"), Pregel.src("value"))
      )
      // Propagate the value 1 to other vertices
      .aggMsgs(max(Pregel.msg))
      .run()

    assert(resultDF.sort("id").select("value").as[Int].collect() === Array.fill(n)(1))
  }

  test("reverse chain propagation") {
    val n = 5
    val verDF = (1 to n).toDF("id").repartition(3)
    val edgeDF = (1 until n).map(x => (x + 1, x))
      .toDF("src", "dst").repartition(3)

    val graph = GraphFrame(verDF, edgeDF)

    val resultDF = graph.pregel
      .setMaxIter(n - 1)
      .withVertexColumn("value",
        when(col("id") === lit(1), lit(1)).otherwise(lit(0)),
        when(Pregel.msg > col("value"), Pregel.msg).otherwise(col("value"))
      )
      .sendMsgToSrc(
        when(Pregel.dst("value") =!= Pregel.src("value"), Pregel.dst("value"))
      )
      .aggMsgs(max(Pregel.msg))
      .run()

    assert(resultDF.sort("id").select("value").as[Int].collect() === Array.fill(n)(1))
  }

  test("send messages only for active vertices") {
    val n = 5
    val verDF = (1 to n).toDF("id").repartition(3)
    val edgeDF = spark.sparkContext.parallelize(Seq(
      (1, 3),
      (2, 3),
      (3, 4),
      (4, 5)
    )).toDF("src", "dst").repartition(3)

    val graph = GraphFrame(verDF, edgeDF)

    val resultDF = graph.pregel
      .setMaxIter(n + 2)
      .withVertexColumn("value",
        // Only the leaves get a nonzero value
        when(col("id") < lit(3), lit(42)).otherwise(lit(0)),
        // Sum the values
        col("value") + when(Pregel.msg.isNotNull, Pregel.msg).otherwise(0)
      )
      .sendMsgToDst(
        // We don't have a way to tell if we have already sent a message, hence rely on Pregel only sending messages for active vertices (which have received a message)
        Pregel.src("value")
      )
      .aggMsgs(sum(Pregel.msg))
      .run()

    assert(resultDF.sort("id").select("value").collect().map(r => r.get(0)) === Array(42, 42, 84, 84, 84))
  }

  test("send messages only for active vertices, reversed") {
    val n = 5
    val verDF = (1 to n).toDF("id").repartition(3)
    val edgeDF = spark.sparkContext.parallelize(Seq(
      (3, 1),
      (3, 2),
      (4, 3),
      (5, 4)
    )).toDF("src", "dst").repartition(3)

    val graph = GraphFrame(verDF, edgeDF)

    val resultDF = graph.pregel
      .setMaxIter(n + 2)
      .withVertexColumn("value",
        when(col("id") < lit(3), lit(42)).otherwise(lit(0)),
        col("value") + when(Pregel.msg.isNotNull, Pregel.msg).otherwise(0)
      )
      .sendMsgToSrc(Pregel.dst("value"))
      .aggMsgs(sum(Pregel.msg))
      .run()

    assert(resultDF.sort("id").select("value").collect().map(r => r.get(0)) === Array(42, 42, 84, 84, 84))
  }

}
