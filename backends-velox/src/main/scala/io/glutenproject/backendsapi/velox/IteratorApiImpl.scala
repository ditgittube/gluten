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
package io.glutenproject.backendsapi.velox

import io.glutenproject.GlutenNumaBindingInfo
import io.glutenproject.backendsapi.IteratorApi
import io.glutenproject.execution._
import io.glutenproject.metrics.IMetrics
import io.glutenproject.substrait.plan.PlanNode
import io.glutenproject.substrait.rel.{LocalFilesBuilder, LocalFilesNode, SplitInfo}
import io.glutenproject.substrait.rel.LocalFilesNode.ReadFileFormat
import io.glutenproject.utils._
import io.glutenproject.vectorized._

import org.apache.spark.{SparkConf, SparkContext, TaskContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.softaffinity.SoftAffinity
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils
import org.apache.spark.sql.catalyst.util.{DateFormatter, TimestampFormatter}
import org.apache.spark.sql.connector.read.InputPartition
import org.apache.spark.sql.execution.datasources.{FilePartition, PartitionedFile}
import org.apache.spark.sql.execution.joins.BuildSideRelation
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.{BinaryType, DateType, StructType, TimestampType}
import org.apache.spark.sql.utils.OASPackageBridge.InputMetricsWrapper
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.ExecutorManager

import java.lang.{Long => JLong}
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.util.{ArrayList => JArrayList, HashMap => JHashMap, Map => JMap}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

class IteratorApiImpl extends IteratorApi with Logging {

  override def genSplitInfo(
      partition: InputPartition,
      partitionSchema: StructType,
      fileFormat: ReadFileFormat): SplitInfo = {
    partition match {
      case f: FilePartition =>
        val (paths, starts, lengths, partitionColumns) =
          constructSplitInfo(partitionSchema, f.files)
        val preferredLocations =
          SoftAffinity.getFilePartitionLocations(paths.asScala.toArray, f.preferredLocations())
        LocalFilesBuilder.makeLocalFiles(
          f.index,
          paths,
          starts,
          lengths,
          partitionColumns,
          fileFormat,
          preferredLocations.toList.asJava)
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported input partition.")
    }
  }

  /** Generate native row partition. */
  override def genPartitions(
      wsCtx: WholeStageTransformContext,
      splitInfos: Seq[Seq[SplitInfo]]): Seq[BaseGlutenPartition] = {
    // Only serialize plan once, save lots time when plan is complex.
    val planByteArray = wsCtx.root.toProtobuf.toByteArray

    splitInfos.zipWithIndex.map {
      case (splitInfos, index) =>
        GlutenPartition(
          index,
          planByteArray,
          splitInfos.map(_.asInstanceOf[LocalFilesNode].toProtobuf.toByteArray).toArray,
          splitInfos.flatMap(_.preferredLocations().asScala).toArray
        )
    }
  }

  private def constructSplitInfo(schema: StructType, files: Array[PartitionedFile]) = {
    val paths = new JArrayList[String]()
    val starts = new JArrayList[JLong]
    val lengths = new JArrayList[JLong]()
    val partitionColumns = new JArrayList[JMap[String, String]]
    files.foreach {
      file =>
        // The "file.filePath" in PartitionedFile is not the original encoded path, so the decoded
        // path is incorrect in some cases and here fix the case of ' ' by using GlutenURLDecoder
        paths.add(
          GlutenURLDecoder
            .decode(file.filePath.toString, StandardCharsets.UTF_8.name()))
        starts.add(JLong.valueOf(file.start))
        lengths.add(JLong.valueOf(file.length))

        val partitionColumn = new JHashMap[String, String]()
        for (i <- 0 until file.partitionValues.numFields) {
          val partitionColumnValue = if (file.partitionValues.isNullAt(i)) {
            ExternalCatalogUtils.DEFAULT_PARTITION_NAME
          } else {
            val pn = file.partitionValues.get(i, schema.fields(i).dataType)
            schema.fields(i).dataType match {
              case _: BinaryType =>
                new String(pn.asInstanceOf[Array[Byte]], StandardCharsets.UTF_8)
              case _: DateType =>
                DateFormatter.apply().format(pn.asInstanceOf[Integer])
              case _: TimestampType =>
                TimestampFormatter
                  .getFractionFormatter(ZoneOffset.UTC)
                  .format(pn.asInstanceOf[java.lang.Long])
              case _ => pn.toString
            }
          }
          partitionColumn.put(schema.names(i), partitionColumnValue)
        }
        partitionColumns.add(partitionColumn)
    }
    (paths, starts, lengths, partitionColumns)
  }

  override def injectWriteFilesTempPath(path: String): Unit = {
    val transKernel = NativePlanEvaluator.create()
    transKernel.injectWriteFilesTempPath(path)
  }

  /** Generate Iterator[ColumnarBatch] for first stage. */
  override def genFirstStageIterator(
      inputPartition: BaseGlutenPartition,
      context: TaskContext,
      pipelineTime: SQLMetric,
      updateInputMetrics: (InputMetricsWrapper) => Unit,
      updateNativeMetrics: IMetrics => Unit,
      inputIterators: Seq[Iterator[ColumnarBatch]] = Seq()): Iterator[ColumnarBatch] = {
    assert(
      inputPartition.isInstanceOf[GlutenPartition],
      "Velox backend only accept GlutenPartition.")

    val beforeBuild = System.nanoTime()
    val columnarNativeIterators =
      new JArrayList[GeneralInIterator](inputIterators.map {
        iter => new ColumnarBatchInIterator(iter.asJava)
      }.asJava)
    val transKernel = NativePlanEvaluator.create()

    val splitInfoByteArray = inputPartition
      .asInstanceOf[GlutenPartition]
      .splitInfosByteArray
    val resIter: GeneralOutIterator =
      transKernel.createKernelWithBatchIterator(
        inputPartition.plan,
        splitInfoByteArray,
        columnarNativeIterators)
    pipelineTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeBuild)

    Iterators
      .wrap(resIter.asScala)
      .protectInvocationFlow()
      .recycleIterator {
        updateNativeMetrics(resIter.getMetrics)
        updateInputMetrics(TaskContext.get().taskMetrics().inputMetrics)
        resIter.close()
      }
      .recyclePayload(batch => batch.close())
      .addToPipelineTime(pipelineTime)
      .asInterruptible(context)
      .create()
  }

  // scalastyle:off argcount

  /** Generate Iterator[ColumnarBatch] for final stage. */
  override def genFinalStageIterator(
      context: TaskContext,
      inputIterators: Seq[Iterator[ColumnarBatch]],
      numaBindingInfo: GlutenNumaBindingInfo,
      sparkConf: SparkConf,
      rootNode: PlanNode,
      pipelineTime: SQLMetric,
      updateNativeMetrics: IMetrics => Unit,
      materializeInput: Boolean): Iterator[ColumnarBatch] = {

    ExecutorManager.tryTaskSet(numaBindingInfo)

    val transKernel = NativePlanEvaluator.create()
    val columnarNativeIterator =
      new JArrayList[GeneralInIterator](inputIterators.map {
        iter => new ColumnarBatchInIterator(iter.asJava)
      }.asJava)
    val nativeResultIterator =
      transKernel.createKernelWithBatchIterator(
        rootNode.toProtobuf.toByteArray,
        // Final iterator does not contain scan split, so pass empty split info to native here.
        new Array[Array[Byte]](0),
        columnarNativeIterator
      )

    Iterators
      .wrap(nativeResultIterator.asScala)
      .recycleIterator {
        updateNativeMetrics(nativeResultIterator.getMetrics)
        nativeResultIterator.close()
      }
      .recyclePayload(batch => batch.close())
      .addToPipelineTime(pipelineTime)
      .create()
  }
  // scalastyle:on argcount

  /** Generate Native FileScanRDD, currently only for ClickHouse Backend. */
  override def genNativeFileScanRDD(
      sparkContext: SparkContext,
      wsCxt: WholeStageTransformContext,
      splitInfos: Seq[SplitInfo],
      numOutputRows: SQLMetric,
      numOutputBatches: SQLMetric,
      scanTime: SQLMetric): RDD[ColumnarBatch] = {
    throw new UnsupportedOperationException("Cannot support to generate Native FileScanRDD.")
  }

  /** Compute for BroadcastBuildSideRDD */
  override def genBroadcastBuildSideIterator(
      broadcasted: Broadcast[BuildSideRelation],
      broadCastContext: BroadCastHashJoinContext): Iterator[ColumnarBatch] = {
    val relation = broadcasted.value.asReadOnlyCopy(broadCastContext)
    Iterators
      .wrap(relation.deserialized)
      .recyclePayload(batch => batch.close())
      .create()
  }
}
