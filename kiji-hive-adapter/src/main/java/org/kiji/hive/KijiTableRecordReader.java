/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.hive;

import java.io.IOException;
import java.util.Iterator;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.mapred.RecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.hive.utils.KijiDataRequestSerializer;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiDataRequestException;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiRowScanner;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiTableReader.KijiScannerOptions;
import org.kiji.schema.KijiURI;
import org.kiji.schema.impl.HBaseKijiRowData;
import org.kiji.schema.util.ResourceUtils;

/**
 * Reads key-value records from a KijiTableInputSplit (usually 1 region in an HTable).
 */
public class KijiTableRecordReader implements RecordReader<ImmutableBytesWritable, Result> {
  private static final Logger LOG = LoggerFactory.getLogger(KijiTableRecordReader.class);

  private final Kiji mKiji;
  private final KijiTable mKijiTable;
  private final KijiTableReader mKijiTableReader;
  private final KijiRowScanner mScanner;
  private final Iterator<KijiRowData> mIterator;

  /**
   * Constructor.
   *
   * @param inputSplit The input split to read records from.
   * @param conf The job configuration.
   * @throws IOException If the input split cannot be opened.
   */
  public KijiTableRecordReader(KijiTableInputSplit inputSplit, Configuration conf)
      throws IOException {
    KijiURI kijiURI = inputSplit.getKijiTableURI();
    mKiji = Kiji.Factory.open(kijiURI);
    mKijiTable = mKiji.openTable(kijiURI.getTable());
    mKijiTableReader = mKijiTable.openTableReader();

    try {
      String dataRequestString = conf.get(KijiTableInputFormat.CONF_KIJI_DATA_REQUEST);
      if (null == dataRequestString) {
        throw new RuntimeException("KijiTableInputFormat was not configured. "
            + "Please set " + KijiTableInputFormat.CONF_KIJI_DATA_REQUEST + " in configuration.");
      }
      KijiDataRequest dataRequest = KijiDataRequestSerializer.deserialize(
          dataRequestString);

      KijiScannerOptions scannerOptions = new KijiScannerOptions();
      if (inputSplit.getRegionStartKey().length > 0) {
        scannerOptions.setStartRow(mKijiTable.getEntityId(inputSplit.getRegionStartKey()));
      }
      if (inputSplit.getRegionEndKey().length > 0) {
        scannerOptions.setStopRow(mKijiTable.getEntityId(inputSplit.getRegionEndKey()));
      }
      mScanner = mKijiTableReader.getScanner(dataRequest, scannerOptions);
      mIterator = mScanner.iterator();
    } catch (KijiDataRequestException e) {
      throw new RuntimeException("Invalid KijiDataRequest.", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void close() throws IOException {
    IOUtils.closeQuietly(mScanner);
    ResourceUtils.closeOrLog(mKijiTableReader);
    ResourceUtils.releaseOrLog(mKijiTable);
    ResourceUtils.releaseOrLog(mKiji);
  }

  /** {@inheritDoc} */
  @Override
  public ImmutableBytesWritable createKey() {
    return new ImmutableBytesWritable();
  }

  /** {@inheritDoc} */
  @Override
  public Result createValue() {
    return new Result();
  }

  /** {@inheritDoc} */
  @Override
  public long getPos() throws IOException {
    // There isn't a way to know how many bytes into the region we are.
    return 0L;
  }

  /** {@inheritDoc} */
  @Override
  public float getProgress() throws IOException {
    // TODO: Estimate progress based on the region row keys (if row key hashing is enabled).
    return 0.0f;
  }

  /** {@inheritDoc} */
  @Override
  public boolean next(ImmutableBytesWritable key, Result value) throws IOException {
    if (!mIterator.hasNext()) {
      return false;
    }
    final HBaseKijiRowData rowData = (HBaseKijiRowData) mIterator.next();

    final Result result = rowData.getHBaseResult();
    key.set(result.getRow());
    Writables.copyWritable(result, value);
    return true;
  }
}
