package com.pingcap.tispark.utils

import com.pingcap.tikv.codec.{CodecDataOutput, TableCodec}
import com.pingcap.tikv.exception.{
  ConvertOverflowException,
  TiBatchWriteException,
  TiDBConvertException
}
import com.pingcap.tikv.key.{CommonHandle, Handle, IndexKey, IntHandle, RowKey}
import com.pingcap.tikv.meta.{TiIndexColumn, TiIndexInfo, TiTableInfo}
import com.pingcap.tikv.row.ObjectRowImpl
import com.pingcap.tikv.types.DataType
import com.pingcap.tispark.write.TiBatchWrite.{SparkRow, TiRow}
import com.pingcap.tispark.write.{SerializableKey, WrappedEncodedRow, WrappedRow}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

import scala.collection.JavaConverters._

object WriteUtil {

  /**
   * Convert sparkRow 2 TiKVRow
   * @param sparkRow
   * @param tiTableInfo
   * @param df
   * @return
   */
  def sparkRow2TiKVRow(
      sparkRow: SparkRow,
      tiTableInfo: TiTableInfo,
      colsInDf: List[String]): TiRow = {
    val colsMapInTiDB = tiTableInfo.getColumns.asScala.map(col => col.getName -> col).toMap

    val fieldCount = sparkRow.size
    val tiRow = ObjectRowImpl.create(fieldCount)
    for (i <- 0 until fieldCount) {
      // TODO: add tiDataType back
      try {
        tiRow.set(
          colsMapInTiDB(colsInDf(i)).getOffset,
          null,
          colsMapInTiDB(colsInDf(i)).getType.convertToTiDBType(sparkRow(i)))
      } catch {
        case e: ConvertOverflowException =>
          throw new ConvertOverflowException(
            e.getMessage,
            new TiDBConvertException(colsMapInTiDB(colsInDf(i)).getName, e))
        case e: Throwable =>
          throw new TiDBConvertException(colsMapInTiDB(colsInDf(i)).getName, e)
      }
    }
    tiRow
  }

  /**
   * ExtractHandle from isCommonHandle or isPkHandle
   * @param row
   * @param tiTableInfo
   * @return
   */
  def extractHandle(row: TiRow, tiTableInfo: TiTableInfo): Handle = {
    val handleCol = tiTableInfo.getPKIsHandleColumn

    if (tiTableInfo.isCommonHandle) {
      var dataTypeList: List[DataType] = Nil
      var dataList: List[Object] = Nil
      var indexColumnList: List[TiIndexColumn] = Nil
      tiTableInfo.getPrimaryKey.getIndexColumns.forEach { idx =>
        val col = tiTableInfo.getColumn(idx.getName)
        dataTypeList = col.getType :: dataTypeList
        dataList = row.get(col.getOffset, col.getType) :: dataList
        indexColumnList = idx :: indexColumnList
      }
      dataTypeList = dataTypeList.reverse
      dataList = dataList.reverse
      indexColumnList = indexColumnList.reverse
      CommonHandle.newCommonHandle(
        dataTypeList.toArray,
        dataList.toArray,
        indexColumnList.map(_.getLength).toArray)
    } else if (tiTableInfo.isPkHandle) {
      val id = row
        .get(handleCol.getOffset, handleCol.getType)
        .asInstanceOf[java.lang.Long]
      new IntHandle(id)
    } else {
      // TODO provide information
      throw new TiBatchWriteException("Only support extractHandle isCommonHandle or isPkHandle")
    }
  }

  /**
   * For delete only
   *
   * @param rdd
   * @param tableId
   * @return
   */
  def generateRecordKVToDelete(rdd: RDD[WrappedRow], tableId: Long): RDD[WrappedEncodedRow] = {
    rdd.map { wrappedRow =>
      {
        val (encodedKey, encodedValue) = (
          new SerializableKey(RowKey.toRowKey(tableId, wrappedRow.handle).getBytes),
          new Array[Byte](0))
        WrappedEncodedRow(
          wrappedRow.row,
          wrappedRow.handle,
          encodedKey,
          encodedValue,
          isIndex = false,
          -1,
          remove = true)
      }
    }
  }

  /**
   * generate encode index to Map[Long, RDD[WrappedEncodedRow].
   * The key of map is indexId
   * @param rdd
   * @param remove
   * @param tiTableInfo
   * @return
   */
  def generateIndexKVs(
      rdd: RDD[WrappedRow],
      tiTableInfo: TiTableInfo,
      remove: Boolean): Map[Long, RDD[WrappedEncodedRow]] = {
    tiTableInfo.getIndices.asScala.flatMap { index =>
      if (tiTableInfo.isCommonHandle && index.isPrimary) {
        None
      } else {
        Some((index.getId, generateIndexRDD(rdd, index, tiTableInfo, remove)))
      }
    }.toMap
  }

  private def generateIndexRDD(
      rdd: RDD[WrappedRow],
      index: TiIndexInfo,
      tiTableInfo: TiTableInfo,
      remove: Boolean): RDD[WrappedEncodedRow] = {
    if (index.isUnique) {
      rdd.map { row =>
        val (encodedKey, encodedValue) =
          generateUniqueIndexKey(row.row, row.handle, index, tiTableInfo, remove)
        WrappedEncodedRow(
          row.row,
          row.handle,
          encodedKey,
          encodedValue,
          isIndex = true,
          index.getId,
          remove)
      }
    } else {
      rdd.map { row =>
        val (encodedKey, encodedValue) =
          generateSecondaryIndexKey(row.row, row.handle, index, tiTableInfo, remove)
        WrappedEncodedRow(
          row.row,
          row.handle,
          encodedKey,
          encodedValue,
          isIndex = true,
          index.getId,
          remove)
      }
    }
  }

  // construct unique index and non-unique index and value to be inserted into TiKV
  // NOTE:
  //      pk is not handle case is equivalent to unique index.
  //      for non-unique index, handle will be encoded as part of index key. In contrast, unique
  //      index encoded handle to value.
  private def generateUniqueIndexKey(
      row: TiRow,
      handle: Handle,
      index: TiIndexInfo,
      tiTableInfo: TiTableInfo,
      remove: Boolean): (SerializableKey, Array[Byte]) = {

    // NULL is only allowed in unique key, primary key does not allow NULL value
    val encodeResult = IndexKey.encodeIndexDataValues(
      row,
      index.getIndexColumns,
      handle,
      index.isUnique && !index.isPrimary,
      tiTableInfo)
    val indexKey = IndexKey.toIndexKey(
      locatePhysicalTable(row, tiTableInfo),
      index.getId,
      encodeResult.keys: _*)

    val value = if (remove) {
      new Array[Byte](0)
    } else {
      if (encodeResult.appendHandle) {
        val value = new Array[Byte](1)
        value(0) = '0'
        value
      } else {
        if (handle.isInt) {
          val cdo = new CodecDataOutput()
          cdo.writeLong(handle.intValue())
          cdo.toBytes
        } else {
          TableCodec.genIndexValueForClusteredIndexVersion1(index, handle)
        }
      }
    }
    (new SerializableKey(indexKey.getBytes), value)
  }

  private def generateSecondaryIndexKey(
      row: TiRow,
      handle: Handle,
      index: TiIndexInfo,
      tiTableInfo: TiTableInfo,
      remove: Boolean): (SerializableKey, Array[Byte]) = {
    val keys =
      IndexKey.encodeIndexDataValues(row, index.getIndexColumns, handle, false, tiTableInfo).keys
    val cdo = new CodecDataOutput()
    cdo.write(
      IndexKey.toIndexKey(locatePhysicalTable(row, tiTableInfo), index.getId, keys: _*).getBytes)
    cdo.write(handle.encodedAsKey())

    val value: Array[Byte] = if (remove) {
      new Array[Byte](0)
    } else {
      val value = new Array[Byte](1)
      value(0) = '0'
      value
    }
    (new SerializableKey(cdo.toBytes), value)
  }

  // TODO: support physical table later. Need use partition info and row value to
  // calculate the real physical table.
  def locatePhysicalTable(row: TiRow, tiTableInfo: TiTableInfo): Long = {
    tiTableInfo.getId
  }
}
