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
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link DoFn} that runs an external command once per worker.
 */
public class WorkerDoFn<InputT> extends DoFn<InputT, InputT> {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerDoFn.class);

  private final String[] cmdArray;
  private final String[] envp;
  private final File dir;
  private final UUID uuid;

  private static final ConcurrentMap<UUID, Boolean> status = Maps.newConcurrentMap();

  /**
   * Create a new {@link WorkerDoFn} instance.
   * @param command the command to call.
   */
  public WorkerDoFn(String command) {
    this(ProcessUtil.tokenizeCommand(command));
  }

  /**
   * Create a new {@link WorkerDoFn} instance.
   * @param cmdArray array containing the command to call and its arguments.
   */
  public WorkerDoFn(String[] cmdArray) {
    this(cmdArray, null, null);
  }

  /**
   * Create a new {@link WorkerDoFn} instance.
   * @param command     the command to call.
   * @param environment environment variables, or <tt>null</tt> if the sub-process should inherit
   *                    the environment from the current process.
   * @param dir         the working directory of the sub-process, or <tt>null</tt> if the
   *                    sub-process should inherit the working directly of the current process.
   */
  public WorkerDoFn(String command, Map<String, String> environment, File dir) {
    this(ProcessUtil.tokenizeCommand(command), environment, dir);
  }

  /**
   * Create a new {@link WorkerDoFn} instance.
   * @param cmdArray    array containing the command to call and its arguments.
   * @param environment environment variables, or <tt>null</tt> if the subprocess should inherit
   *                    the environment from the current process.
   * @param dir         the working directory of the sub-process, or <tt>null</tt> if the
   *                    sub-process should inherit the working directly of the current process.
   */
  public WorkerDoFn(String[] cmdArray, Map<String, String> environment, File dir) {
    this.cmdArray = cmdArray;
    this.envp = ProcessUtil.createEnv(environment);
    this.dir = dir;
    this.uuid = UUID.randomUUID();
  }

  @DoFn.Setup
  public void setup() {
    status.computeIfAbsent(this.uuid, uuid -> this.exec());
  }

  private boolean exec() {
    try {
      Process p = Runtime.getRuntime().exec(cmdArray, envp, dir);
      int exitCode = p.waitFor();

      String stdOut = ProcessUtil.getStdOut(p);
      LOG.info("Process exited: {}{}",
          ProcessUtil.join(cmdArray), stdOut.isEmpty() ? "" : ", STDOUT:\n" + stdOut);
      String stdErr = ProcessUtil.getStdErr(p);
      if (!stdErr.isEmpty()) {
        LOG.error("Process exited: {}, STDERR:\n{}",
            ProcessUtil.join(cmdArray), stdErr);
      }
      Preconditions.checkState(exitCode == 0, "Non-zero exit code: " + exitCode);
      return true;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
        .add(DisplayData.item("Command", Joiner.on(' ').join(cmdArray)))
        .add(DisplayData.item("Environment", envp == null ? "null" : Joiner.on(' ').join(envp)))
        .add(DisplayData.item("Working Directory", dir == null ? "null" : dir.toString()));
  }

}
