/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.extra.transforms;

import com.google.common.collect.Lists;
import com.spotify.scio.util.RemoteFileUtil;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link DoFn} that downloads {@link URI} elements and processes them as local {@link Path}s.
 */
public class FileDoFn<OutputT> extends DoFn<URI, OutputT> {

  private static final Logger LOG = LoggerFactory.getLogger(FileDoFn.class);

  private final List<URI> batch;
  private final SerializableFunction<Path, OutputT> fn;
  private final int batchSize;
  private final boolean keep;

  private transient RemoteFileUtil remoteFileUtil;

  /**
   * Create a new {@link FileDoFn} instance.
   * @param fn function to process downloaded files.
   */
  public FileDoFn(SerializableFunction<Path, OutputT> fn) {
    this(fn, 1, false);
  }

  /**
   * Create a new {@link FileDoFn} instance.
   * @param fn        function to process downloaded files.
   * @param batchSize batch size when downloading files.
   * @param keep      keep downloaded files after processing.
   */
  public FileDoFn(SerializableFunction<Path, OutputT> fn, int batchSize, boolean keep) {
    this.fn = fn;
    this.batch = Lists.newArrayList();
    this.batchSize = batchSize;
    this.keep = keep;
  }

  @StartBundle
  public void startBundle(Context c) {
    this.remoteFileUtil = RemoteFileUtil.create(c.getPipelineOptions());
    this.batch.clear();
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    batch.add(c.element());
    if (batch.size() >= batchSize) {
      processBatch(c);
    }
  }

  @FinishBundle
  public void finishBundle(Context c) {
    processBatch(c);
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder
        .add(DisplayData.item("Batch Size", batchSize))
        .add(DisplayData.item("Keep Downloaded Files", keep));
  }

  private void processBatch(Context c) {
    if (batch.isEmpty()) {
      return;
    }
    LOG.info("Processing batch of {}", batch.size());
    remoteFileUtil.download(batch).stream()
        .map(fn::apply)
        .forEach(c::output);
    if (!keep) {
      LOG.info("Deleting batch of {}", batch.size());
      remoteFileUtil.delete(batch);
    }
    batch.clear();
  }

}
