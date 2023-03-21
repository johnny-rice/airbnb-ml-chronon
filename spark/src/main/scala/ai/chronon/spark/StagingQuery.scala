package ai.chronon.spark

import ai.chronon.api
import ai.chronon.api.Constants
import ai.chronon.api.Extensions._
import ai.chronon.spark.Extensions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.ScalaVersionSpecificCollectionsConverter

class StagingQuery(stagingQueryConf: api.StagingQuery, endPartition: String, tableUtils: TableUtils) {
  assert(Option(stagingQueryConf.metaData.outputNamespace).nonEmpty, s"output namespace could not be empty or null")
  private val outputTable = stagingQueryConf.metaData.outputTable
  private val tableProps = Option(stagingQueryConf.metaData.tableProperties)
    .map(_.asScala.toMap)
    .orNull

  private val partitionCols: Seq[String] = Seq(Constants.PartitionColumn) ++
    ScalaVersionSpecificCollectionsConverter.convertJavaListToScala(
      Option(stagingQueryConf.metaData.customJsonLookUp(key = "additional_partition_cols"))
        .getOrElse(new java.util.ArrayList[String]())
        .asInstanceOf[java.util.ArrayList[String]])

  def computeStagingQuery(stepDays: Option[Int] = None): Unit = {
    Option(stagingQueryConf.setups).foreach(_.asScala.foreach(tableUtils.sql))
    // the input table is not partitioned, usually for data testing or for kaggle demos
    if (stagingQueryConf.startPartition == null) {
      tableUtils.sql(stagingQueryConf.query).save(outputTable)
      return
    }
    val unfilledRanges =
      tableUtils.unfilledRanges(outputTable, PartitionRange(stagingQueryConf.startPartition, endPartition))

    if (unfilledRanges.isEmpty) {
      println(s"""No unfilled range for $outputTable given
           |start partition of ${stagingQueryConf.startPartition}
           |end partition of $endPartition
           |""".stripMargin)
      return
    }
    val stagingQueryUnfilledRanges = unfilledRanges.get
    println(s"Staging Query unfilled ranges: $stagingQueryUnfilledRanges")
    val exceptions = mutable.Buffer.empty[String]
    stagingQueryUnfilledRanges.foreach { stagingQueryUnfilledRange =>
      try {
        val stepRanges = stepDays.map(stagingQueryUnfilledRange.steps).getOrElse(Seq(stagingQueryUnfilledRange))
        println(s"Staging query ranges to compute: ${stepRanges.map { _.toString }.pretty}")
        stepRanges.zipWithIndex.foreach {
          case (range, index) =>
            val progress = s"| [${index + 1}/${stepRanges.size}]"
            println(s"Computing staging query for range: $range  $progress")
            val renderedQuery = stagingQueryConf.query
              .replaceAll(StagingQuery.StartDateRegex, range.start)
              .replaceAll(StagingQuery.EndDateRegex, range.end)
              .replaceAll(StagingQuery.LatestDateRegex, endPartition)
            println(s"Rendered Staging Query to run is:\n$renderedQuery")
            val df = tableUtils.sql(renderedQuery)
            tableUtils.insertPartitions(df, outputTable, tableProps, partitionCols)
            println(s"Wrote to table $outputTable, into partitions: $range $progress")
        }
        println(s"Finished writing Staging Query data to $outputTable")
      } catch {
        case err: Throwable =>
          exceptions.append(s"Error handling range $stagingQueryUnfilledRange : ${err.getMessage}\n${err.traceString}")
      }
    }
    if (exceptions.nonEmpty) {
      val length = exceptions.length
      val fullMessage = exceptions.zipWithIndex
        .map {
          case (message, index) => s"[${index+1}/${length} exceptions]\n${message}"
        }
        .mkString("\n")
      throw new Exception(fullMessage)
    }
  }
}

object StagingQuery {

  final val StartDateRegex = replacementRegexFor("start_date")
  final val EndDateRegex = replacementRegexFor("end_date")
  // Useful for cumulative tables. So the split on step days always get the latest partition.
  final val LatestDateRegex = replacementRegexFor("latest_date")
  private def replacementRegexFor(literal: String): String = s"\\{\\{\\s*$literal\\s*\\}\\}"

  def main(args: Array[String]): Unit = {
    val parsedArgs = new Args(args)
    parsedArgs.verify()
    val stagingQueryConf = parsedArgs.parseConf[api.StagingQuery]
    val stagingQueryJob = new StagingQuery(
      stagingQueryConf,
      parsedArgs.endDate(),
      TableUtils(SparkSessionBuilder.build(s"staging_query_${stagingQueryConf.metaData.name}"))
    )
    stagingQueryJob.computeStagingQuery(parsedArgs.stepDays.toOption)
  }
}
