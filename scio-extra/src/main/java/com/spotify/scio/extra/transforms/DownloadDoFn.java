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

import com.google.common.base.Joiner;
import com.spotify.scio.util.RemoteFileUtil;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link DoFn} that downloads files once per worker.
 */
public class DownloadDoFn<InputT> extends DoFn<InputT, InputT> {

  private static final Logger LOG = LoggerFactory.getLogger(DownloadDoFn.class);

  private static final String lock = "";

  private final List<URI> uris;
  private final File destination;

  /**
   * Create a new {@link DownloadDoFn} instance.
   * @param uris        list of {@link URI}s to download.
   * @param destination destination directory to download to.
   */
  public DownloadDoFn(List<URI> uris, File destination) {
    this.uris = uris;
    this.destination = destination;
  }

  @StartBundle
  public void startBundle(Context c) {
    RemoteFileUtil rfu = RemoteFileUtil.create(c.getPipelineOptions());
    synchronized (lock) {
      try {
        Path dest = destination.toPath();
        Files.createDirectories(dest);
        List<Path> paths = rfu.download(uris);
        for (Path src : paths) {
          Path dst = dest.resolve(src.getFileName());
          if (Files.isSymbolicLink(dst) && Files.readSymbolicLink(dst).equals(src)) {
            continue;
          }
          Files.createSymbolicLink(dst, src);
          LOG.info("Symlink-ed {} to {}", src, dst);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    // wire input to output directly
    c.output(c.element());
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder
        .add(DisplayData.item("URIs", Joiner.on(", ").join(uris)))
        .add(DisplayData.item("Destination", destination.toString()));
  }
}
