package com.pingcap.tispark.utils

import com.pingcap.tikv._
import com.pingcap.tikv.exception.TiBatchWriteException
import com.pingcap.tikv.util.ConcreteBackOffer
import com.pingcap.tispark.write.{SerializableKey, TiDBOptions}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
 * it is not a good 2PCHelper for it involves too many dependencies.
 * not support table lock
 * @param startTs
 * @param options
 */
case class TwoPhaseCommitHepler(startTs: Long, options: TiDBOptions) extends AutoCloseable {

  // 2PC relies too much on TiDBOptions. However, TiDBOptions requires db information which 2PC needn't.
  // So we just fake db information temporarily,
  // TODO change TIDBOptions to TwoPhaseCommitOption
  def this(startTs: Long) {
    this(
      startTs,
      new TiDBOptions(
        Map(
          TiDBOptions.TIDB_ADDRESS -> "",
          TiDBOptions.TIDB_PORT -> "",
          TiDBOptions.TIDB_USER -> "",
          TiDBOptions.TIDB_PASSWORD -> "",
          TiDBOptions.TIDB_DATABASE -> "",
          TiDBOptions.TIDB_TABLE -> "")))
  }

  private final val logger = LoggerFactory.getLogger(getClass.getName)

  // Init tiConf and tiSession
  // PdAddress get from spark config
  private val tiConf = TwoPhaseCommitHepler.generateTiConf(options)
  @transient private lazy val tiSession = TiSession.getInstance(tiConf)

  // Init lockTTLSeconds and ttlManager
  private val tikvSupportUpdateTTL: Boolean =
    StoreVersion.minTiKVVersion("3.0.5", tiSession.getPDClient)
  private val isTTLUpdate = options.isTTLUpdate(tikvSupportUpdateTTL)
  private val lockTTLSeconds: Long = options.getLockTTLSeconds(tikvSupportUpdateTTL)
  @transient private var ttlManager: TTLManager = _

  // Driver primary pre-write
  def prewritePrimaryKeyByDriver(primaryKey: SerializableKey, primaryRow: Array[Byte]): Unit = {
    logger.info("start to prewritePrimaryKey")

    val ti2PCClient =
      new TwoPhaseCommitter(
        tiConf,
        startTs,
        lockTTLSeconds * 1000 + TTLManager.calculateUptime(tiSession.createTxnClient(), startTs),
        options.txnPrewriteBatchSize,
        options.txnCommitBatchSize,
        options.writeBufferSize,
        options.writeThreadPerTask,
        options.retryCommitSecondaryKey,
        options.prewriteMaxRetryTimes)
    val prewritePrimaryBackoff =
      ConcreteBackOffer.newCustomBackOff(options.prewriteBackOfferMS)
    ti2PCClient.prewritePrimaryKey(prewritePrimaryBackoff, primaryKey.bytes, primaryRow)

    logger.info("prewritePrimaryKey success")

    startPrimaryKeyTTLUpdate(primaryKey)
  }

  // Executors secondary pre-write
  def prewriteSecondaryKeyByExecutors(
      secondaryKeysRDD: RDD[(SerializableKey, Array[Byte])],
      primaryKey: SerializableKey): Unit = {
    logger.info("start to prewriteSecondaryKeys")

    secondaryKeysRDD.foreachPartition { iterator =>
      val ti2PCClientOnExecutor =
        new TwoPhaseCommitter(
          tiConf,
          startTs,
          lockTTLSeconds * 1000,
          options.txnPrewriteBatchSize,
          options.txnCommitBatchSize,
          options.writeBufferSize,
          options.writeThreadPerTask,
          options.retryCommitSecondaryKey,
          options.prewriteMaxRetryTimes)

      val pairs = iterator.map { keyValue =>
        new BytePairWrapper(keyValue._1.bytes, keyValue._2)
      }.asJava

      ti2PCClientOnExecutor.prewriteSecondaryKeys(
        primaryKey.bytes,
        pairs,
        options.prewriteBackOfferMS)

      try {
        ti2PCClientOnExecutor.close()
      } catch {
        case _: Throwable =>
      }
    }

    logger.info("prewriteSecondaryKeys success")
  }

  // Driver primary commit
  def commitPrimaryKeyWithRetryByDriver(
      primaryKey: SerializableKey,
      schemaUpdateTimes: List[SchemaUpdateTime]): Long = {
    val ti2PCClient =
      new TwoPhaseCommitter(
        tiConf,
        startTs,
        lockTTLSeconds * 1000 + TTLManager.calculateUptime(tiSession.createTxnClient(), startTs),
        options.txnPrewriteBatchSize,
        options.txnCommitBatchSize,
        options.writeBufferSize,
        options.writeThreadPerTask,
        options.retryCommitSecondaryKey,
        options.prewriteMaxRetryTimes)

    var tryCount = 1
    var error: Throwable = null
    var break = false
    while (!break && tryCount <= options.commitPrimaryKeyRetryNumber) {
      tryCount += 1
      try {
        return commitPrimaryKey(startTs, primaryKey, ti2PCClient, schemaUpdateTimes)
      } catch {
        case e: TiBatchWriteException =>
          error = e
          break = true
        case e: Throwable =>
          error = e
      }
    }
    stopPrimaryKeyTTLUpdate()
    throw error
  }

  // CheckSchema here may not suitable, we just copy from TiBatchWrite. Consider whether checkSchema can move out of the TwoPhaseCommitHepler
  private def commitPrimaryKey(
      startTs: Long,
      primaryKey: SerializableKey,
      ti2PCClient: TwoPhaseCommitter,
      schemaUpdateTimes: List[SchemaUpdateTime]): Long = {
    val commitTsAttempt = tiSession.getTimestamp.getVersion
    // check commitTS
    if (commitTsAttempt <= startTs) {
      throw new TiBatchWriteException(
        s"invalid transaction tso with startTs=$startTs, commitTsAttempt=$commitTsAttempt")
    }

    // for test
    if (options.sleepAfterPrewriteSecondaryKey > 0) {
      logger.info(s"sleep ${options.sleepAfterPrewriteSecondaryKey} ms for test")
      Thread.sleep(options.sleepAfterPrewriteSecondaryKey)
    }

    // check schema change
    for (schemaUpdateTime <- schemaUpdateTimes) {
      val newTableInfo =
        tiSession.getCatalog.getTable(schemaUpdateTime.databaseName, schemaUpdateTime.tableName)
      if (schemaUpdateTime.updateTime < newTableInfo.getUpdateTimestamp) {
        throw new TiBatchWriteException("schema has changed during prewrite!")
      }
    }

    // for test
    if (options.sleepAfterGetCommitTS > 0) {
      logger.info(s"sleep ${options.sleepAfterGetCommitTS} ms for test")
      Thread.sleep(options.sleepAfterGetCommitTS)
    }

    val commitPrimaryBackoff =
      ConcreteBackOffer.newCustomBackOff(TwoPhaseCommitHepler.PRIMARY_KEY_COMMIT_BACKOFF)

    logger.info(s"start to commitPrimaryKey, commitTsAttempt=$commitTsAttempt")
    ti2PCClient.commitPrimaryKey(commitPrimaryBackoff, primaryKey.bytes, commitTsAttempt)
    try {
      ti2PCClient.close()
    } catch {
      case _: Throwable =>
    }
    logger.info("commitPrimaryKey success")
    commitTsAttempt
  }

  // Executors secondary commit
  def commitSecondaryKeyByExecutors(
      secondaryKeysRDD: RDD[(SerializableKey, Array[Byte])],
      commitTs: Long): Unit = {
    if (!options.skipCommitSecondaryKey) {
      logger.info("start to commitSecondaryKeys")
      secondaryKeysRDD.foreachPartition { iterator =>
        val ti2PCClientOnExecutor = new TwoPhaseCommitter(
          tiConf,
          startTs,
          lockTTLSeconds * 1000,
          options.txnPrewriteBatchSize,
          options.txnCommitBatchSize,
          options.writeBufferSize,
          options.writeThreadPerTask,
          options.retryCommitSecondaryKey,
          options.prewriteMaxRetryTimes)

        val keys = iterator.map { keyValue =>
          new ByteWrapper(keyValue._1.bytes)
        }.asJava

        try {
          ti2PCClientOnExecutor.commitSecondaryKeys(keys, commitTs, options.commitBackOfferMS)
        } catch {
          case e: TiBatchWriteException =>
            // ignored
            logger.warn(s"commit secondary key error", e)
        }

        try {
          ti2PCClientOnExecutor.close()
        } catch {
          case _: Throwable =>
        }
      }
      logger.info("commitSecondaryKeys finish")
    } else {
      logger.info("skipping commit secondary key")
    }
  }

  // Start primary key ttl update
  private def startPrimaryKeyTTLUpdate(primaryKey: SerializableKey) {
    if (isTTLUpdate) {
      if (ttlManager != null) {
        ttlManager.close()
      }
      ttlManager = new TTLManager(tiConf, startTs, primaryKey.bytes)
      ttlManager.keepAlive()
    }
  }

  // Stop primary key ttl update
  private def stopPrimaryKeyTTLUpdate(): Unit = {
    if (isTTLUpdate) {
      ttlManager.close()
    }
  }

  override def close(): Unit = {
    if (ttlManager != null) {
      try {
        ttlManager.close()
      } catch {
        case e: Throwable =>
          logger.warn("Close ttlManager failed", e)
      }
    }
  }

}

object TwoPhaseCommitHepler {
  // Milliseconds
  // copy from TiBatchWrite
  private val MIN_DELAY_CLEAN_TABLE_LOCK = 60000
  private val DELAY_CLEAN_TABLE_LOCK_AND_COMMIT_BACKOFF_DELTA = 30000
  private val PRIMARY_KEY_COMMIT_BACKOFF =
    MIN_DELAY_CLEAN_TABLE_LOCK - DELAY_CLEAN_TABLE_LOCK_AND_COMMIT_BACKOFF_DELTA

  // TODO check if we just use TiDBOptions, because TiDBOptions has merged With SparkConf
  // priority: TiDBOptions config > spark config > TiConfiguration default value
  private def generateTiConf(options: TiDBOptions): TiConfiguration = {
    val clonedConf = SparkContext.getOrCreate().getConf
    clonedConf.setAll(options.parameters)
    TiUtil.sparkConfToTiConf(clonedConf, Option.empty)
  }
}

case class SchemaUpdateTime(databaseName: String, tableName: String, updateTime: Long)
