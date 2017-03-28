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
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * A {@link DoFn} that pipes elements through an external command via StdIn & StdOut.
 */
public class PipeDoFn extends DoFn<String, String> {

  private static final Logger LOG = LoggerFactory.getLogger(PipeDoFn.class);

  private final String[] cmdArray;
  private final String[] envp;
  private final File dir;

  private transient Process p;
  private transient BufferedWriter stdIn;
  private transient CompletableFuture<Void> stdOut;

  /**
   * Create a new {@link PipeDoFn} instance.
   * @param command the command to call.
   */
  public PipeDoFn(String command) {
    this(ProcessUtil.tokenizeCommand(command));
  }

  /**
   * Create a new {@link PipeDoFn} instance.
   * @param cmdArray array containing the command to call and its arguments.
   */
  public PipeDoFn(String[] cmdArray) {
    this(cmdArray, null, null);
  }

  /**
   * Create a new {@link PipeDoFn} instance.
   * @param command     the command to call.
   * @param environment environment variables, or <tt>null</tt> if the subprocess should inherit
   *                    the environment from the current process.
   * @param dir         the working directory of the sub process, or <tt>null</tt> if the
   *                    subprocess should inherit the working directly of the current process.
   */
  public PipeDoFn(String command, Map<String, String> environment, File dir) {
    this(ProcessUtil.tokenizeCommand(command), environment, dir);
  }

  /**
   * Create a new {@link PipeDoFn} instance.
   * @param cmdArray    array containing the command to call and its arguments.
   * @param environment environment variables, or <tt>null</tt> if the subprocess should inherit
   *                    the environment from the current process.
   * @param dir         the working directory of the sub process, or <tt>null</tt> if the
   *                    subprocess should inherit the working directly of the current process.
   */
  public PipeDoFn(String[] cmdArray, Map<String, String> environment, File dir) {
    this.cmdArray = cmdArray;
    this.envp = ProcessUtil.createEnv(environment);
    this.dir = dir;
  }

  @StartBundle
  public void startBundle(Context c) {
    try {
      p = Runtime.getRuntime().exec(cmdArray, envp, dir);

      stdIn = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

      ExecutorService es = MoreExecutors.getExitingExecutorService(
          (ThreadPoolExecutor) Executors.newFixedThreadPool(2));
      BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
      stdOut = CompletableFuture.runAsync(() -> out.lines().forEach(c::output), es);
      LOG.info("Process started: {}", ProcessUtil.join(cmdArray));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @FinishBundle
  public void finishBundle(Context c) {
    try {
      stdIn.close();
      int exitCode = p.waitFor();
      stdOut.get();

      String stdErr = ProcessUtil.getStdErr(p);
      LOG.info("Process exited: {}{}",
          ProcessUtil.join(cmdArray), stdErr.isEmpty() ? "" : ", STDERR:\n" + stdErr);
      Preconditions.checkState(exitCode == 0, "Non-zero exit code: " + exitCode);
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    try {
      stdIn.write(c.element());
      stdIn.newLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
