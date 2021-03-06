/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.io.FileBasedSink.FileResult;
import org.apache.beam.sdk.io.FileBasedSink.FileResultCoder;
import org.apache.beam.sdk.io.HadoopFileBasedSink.WriteOperation;
import org.apache.beam.sdk.io.HadoopFileBasedSink.Writer;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Experimental(Experimental.Kind.SOURCE_SINK)
public class HadoopWriteFiles<T> extends PTransform<PCollection<T>, PDone> {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopWriteFiles.class);

  // The maximum number of file writers to keep open in a single bundle at a time, since file
  // writers default to 64mb buffers. This comes into play when writing per-window files.
  // The first 20 files from a single WriteFiles transform will write files inline in the
  // transform. Anything beyond that might be shuffled.
  // Keep in mind that specific runners may decide to run multiple bundles in parallel, based on
  // their own policy.
  private static final int DEFAULT_MAX_NUM_WRITERS_PER_BUNDLE = 20;

  // When we spill records, shard the output keys to prevent hotspots.
  // We could consider making this a parameter.
  private static final int SPILLED_RECORD_SHARDING_FACTOR = 10;

  static final int UNKNOWN_SHARDNUM = -1;
  private HadoopFileBasedSink<T> sink;
  private WriteOperation<T> writeOperation;
  // This allows the number of shards to be dynamically computed based on the input
  // PCollection.
  @Nullable
  private final PTransform<PCollection<T>, PCollectionView<Integer>> computeNumShards;
  // We don't use a side input for static sharding, as we want this value to be updatable
  // when a pipeline is updated.
  @Nullable
  private final ValueProvider<Integer> numShardsProvider;
  private final boolean windowedWrites;
  private int maxNumWritersPerBundle;

  public static <T> HadoopWriteFiles<T> to(HadoopFileBasedSink<T> sink) {
    checkNotNull(sink, "sink");
    return new HadoopWriteFiles<>(sink, null /* runner-determined sharding */, null,
        false, DEFAULT_MAX_NUM_WRITERS_PER_BUNDLE);
  }

  private HadoopWriteFiles(
      HadoopFileBasedSink<T> sink,
      @Nullable PTransform<PCollection<T>, PCollectionView<Integer>> computeNumShards,
      @Nullable ValueProvider<Integer> numShardsProvider,
      boolean windowedWrites,
      int maxNumWritersPerBundle) {
    this.sink = sink;
    this.computeNumShards = computeNumShards;
    this.numShardsProvider = numShardsProvider;
    this.windowedWrites = windowedWrites;
    this.maxNumWritersPerBundle = maxNumWritersPerBundle;
  }

  @Override
  public PDone expand(PCollection<T> input) {
    if (input.isBounded() == IsBounded.UNBOUNDED) {
      checkArgument(windowedWrites,
          "Must use windowed writes when applying %s to an unbounded PCollection",
          HadoopWriteFiles.class.getSimpleName());
      // The reason for this is https://issues.apache.org/jira/browse/BEAM-1438
      // and similar behavior in other runners.
      checkArgument(
          computeNumShards != null || numShardsProvider != null,
          "When applying %s to an unbounded PCollection, "
              + "must specify number of output shards explicitly",
          HadoopWriteFiles.class.getSimpleName());
    }
    this.writeOperation = sink.createWriteOperation();
    this.writeOperation.setWindowedWrites(windowedWrites);
    return createWrite(input);
  }

  @Override
  public void validate(PipelineOptions options) {
    sink.validate(options);
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder
        .add(DisplayData.item("sink", sink.getClass()).withLabel("WriteFiles Sink"))
        .include("sink", sink);
    if (getSharding() != null) {
      builder.include("sharding", getSharding());
    } else if (getNumShards() != null) {
      String numShards = getNumShards().isAccessible()
          ? getNumShards().get().toString() : getNumShards().toString();
      builder.add(DisplayData.item("numShards", numShards)
          .withLabel("Fixed Number of Shards"));
    }
  }

  /**
   * Returns the {@link FileBasedSink} associated with this PTransform.
   */
  public HadoopFileBasedSink<T> getSink() {
    return sink;
  }

  /**
   * Returns whether or not to perform windowed writes.
   */
  public boolean isWindowedWrites() {
    return windowedWrites;
  }

  /**
   * Gets the {@link PTransform} that will be used to determine sharding. This can be either a
   * static number of shards (as following a call to {@link #withNumShards(int)}), dynamic (by
   * {@link #withSharding(PTransform)}), or runner-determined (by {@link
   * #withRunnerDeterminedSharding()}.
   */
  @Nullable
  public PTransform<PCollection<T>, PCollectionView<Integer>> getSharding() {
    return computeNumShards;
  }

  public ValueProvider<Integer> getNumShards() {
    return numShardsProvider;
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   *
   * <p>A value less than or equal to 0 will be equivalent to the default behavior of
   * runner-determined sharding.
   */
  public HadoopWriteFiles<T> withNumShards(int numShards) {
    if (numShards > 0) {
      return withNumShards(StaticValueProvider.of(numShards));
    }
    return withRunnerDeterminedSharding();
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * {@link ValueProvider} specified number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   */
  public HadoopWriteFiles<T> withNumShards(ValueProvider<Integer> numShardsProvider) {
    return new HadoopWriteFiles<>(sink, null, numShardsProvider, windowedWrites,
        maxNumWritersPerBundle);
  }

  /**
   * Set the maximum number of writers created in a bundle before spilling to shuffle.
   */
  public HadoopWriteFiles<T> withMaxNumWritersPerBundle(int maxNumWritersPerBundle) {
    return new HadoopWriteFiles<>(sink, null, numShardsProvider, windowedWrites,
        maxNumWritersPerBundle);
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} using the
   * specified {@link PTransform} to compute the number of shards.
   *
   * <p>This option should be used sparingly as it can hurt performance. See {@link WriteFiles} for
   * more information.
   */
  public HadoopWriteFiles<T> withSharding(PTransform<PCollection<T>, PCollectionView<Integer>> sharding) {
    checkNotNull(
        sharding, "Cannot provide null sharding. Use withRunnerDeterminedSharding() instead");
    return new HadoopWriteFiles<>(sink, sharding, null, windowedWrites, maxNumWritersPerBundle);
  }

  /**
   * Returns a new {@link WriteFiles} that will write to the current {@link FileBasedSink} with
   * runner-determined sharding.
   */
  public HadoopWriteFiles<T> withRunnerDeterminedSharding() {
    return new HadoopWriteFiles<>(sink, null, null, windowedWrites, maxNumWritersPerBundle);
  }

  /**
   * Returns a new {@link WriteFiles} that writes preserves windowing on it's input.
   *
   * <p>If this option is not specified, windowing and triggering are replaced by
   * {@link GlobalWindows} and {@link DefaultTrigger}.
   *
   * <p>If there is no data for a window, no output shards will be generated for that window.
   * If a window triggers multiple times, then more than a single output shard might be
   * generated multiple times; it's up to the sink implementation to keep these output shards
   * unique.
   *
   * <p>This option can only be used if {@link #withNumShards(int)} is also set to a
   * positive value.
   */
  public HadoopWriteFiles<T> withWindowedWrites() {
    return new HadoopWriteFiles<>(sink, computeNumShards, numShardsProvider, true,
        maxNumWritersPerBundle);
  }

  /**
   * Writes all the elements in a bundle using a {@link Writer} produced by the
   * {@link WriteOperation} associated with the {@link FileBasedSink} with windowed writes enabled.
   */
  private class WriteWindowedBundles extends DoFn<T, FileResult> {
    private final TupleTag<KV<Integer, T>> unwrittedRecordsTag;
    private Map<KV<BoundedWindow, PaneInfo>, Writer<T>> windowedWriters;
    int spilledShardNum = UNKNOWN_SHARDNUM;

    WriteWindowedBundles(TupleTag<KV<Integer, T>> unwrittedRecordsTag) {
      this.unwrittedRecordsTag = unwrittedRecordsTag;
    }

    @StartBundle
    public void startBundle(StartBundleContext c) {
      // Reset state in case of reuse. We need to make sure that each bundle gets unique writers.
      windowedWriters = Maps.newHashMap();
    }

    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      PaneInfo paneInfo = c.pane();
      Writer<T> writer;
      // If we are doing windowed writes, we need to ensure that we have separate files for
      // data in different windows/panes.
      KV<BoundedWindow, PaneInfo> key = KV.of(window, paneInfo);
      writer = windowedWriters.get(key);
      if (writer == null) {
        if (windowedWriters.size() <= maxNumWritersPerBundle) {
          String uuid = UUID.randomUUID().toString();
          LOG.info(
              "Opening writer {} for write operation {}, window {} pane {}",
              uuid,
              writeOperation,
              window,
              paneInfo);
          writer = writeOperation.createWriter();
          writer.openWindowed(uuid, window, paneInfo, UNKNOWN_SHARDNUM);
          windowedWriters.put(key, writer);
          LOG.debug("Done opening writer");
        } else {
          if (spilledShardNum == UNKNOWN_SHARDNUM) {
            spilledShardNum = ThreadLocalRandom.current().nextInt(SPILLED_RECORD_SHARDING_FACTOR);
          } else {
            spilledShardNum = (spilledShardNum + 1) % SPILLED_RECORD_SHARDING_FACTOR;
          }
          c.output(unwrittedRecordsTag, KV.of(spilledShardNum, c.element()));
          return;
        }
      }
      writeOrClose(writer, c.element());
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      for (Map.Entry<KV<BoundedWindow, PaneInfo>, Writer<T>> entry : windowedWriters.entrySet()) {
        FileResult result = entry.getValue().close();
        BoundedWindow window = entry.getKey().getKey();
        c.output(result, window.maxTimestamp(), window);
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.delegate(HadoopWriteFiles.this);
    }
  }

  /**
   * Writes all the elements in a bundle using a {@link Writer} produced by the
   * {@link WriteOperation} associated with the {@link FileBasedSink} with windowed writes disabled.
   */
  private class WriteUnwindowedBundles extends DoFn<T, FileResult> {
    // Writer that will write the records in this bundle. Lazily
    // initialized in processElement.
    private Writer<T> writer = null;
    private BoundedWindow window = null;

    @StartBundle
    public void startBundle(StartBundleContext c) {
      // Reset state in case of reuse. We need to make sure that each bundle gets unique writers.
      writer = null;
    }

    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      // Cache a single writer for the bundle.
      if (writer == null) {
        LOG.info("Opening writer for write operation {}", writeOperation);
        writer = writeOperation.createWriter();
        writer.openUnwindowed(UUID.randomUUID().toString(), UNKNOWN_SHARDNUM);
        LOG.debug("Done opening writer");
      }
      this.window = window;
      writeOrClose(this.writer, c.element());
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      if (writer == null) {
        return;
      }
      FileResult result = writer.close();
      c.output(result, window.maxTimestamp(), window);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.delegate(HadoopWriteFiles.this);
    }
  }

  enum ShardAssignment { ASSIGN_IN_FINALIZE, ASSIGN_WHEN_WRITING };

  /**
   * Like {@link WriteWindowedBundles} and {@link WriteUnwindowedBundles}, but where the elements
   * for each shard have been collected into a single iterable.
   */
  private class WriteShardedBundles extends DoFn<KV<Integer, Iterable<T>>, FileResult> {
    ShardAssignment shardNumberAssignment;
    WriteShardedBundles(ShardAssignment shardNumberAssignment) {
      this.shardNumberAssignment = shardNumberAssignment;
    }
    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      // In a sharded write, single input element represents one shard. We can open and close
      // the writer in each call to processElement.
      LOG.info("Opening writer for write operation {}", writeOperation);
      Writer<T> writer = writeOperation.createWriter();
      if (windowedWrites) {
        int shardNumber = shardNumberAssignment == ShardAssignment.ASSIGN_WHEN_WRITING
            ? c.element().getKey() : UNKNOWN_SHARDNUM;
        writer.openWindowed(UUID.randomUUID().toString(), window, c.pane(), shardNumber);
      } else {
        writer.openUnwindowed(UUID.randomUUID().toString(), UNKNOWN_SHARDNUM);
      }
      LOG.debug("Done opening writer");

      try {
        for (T t : c.element().getValue()) {
          writeOrClose(writer, t);
        }

        // Close the writer; if this throws let the error propagate.
        FileResult result = writer.close();
        c.output(result);
      } catch (Exception e) {
        // If anything goes wrong, make sure to delete the temporary file.
        writer.cleanup();
        throw e;
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.delegate(HadoopWriteFiles.this);
    }
  }

  private static <T> void writeOrClose(Writer<T> writer, T t) throws Exception {
    try {
      writer.write(t);
    } catch (Exception e) {
      try {
        writer.close();
      } catch (Exception closeException) {
        if (closeException instanceof InterruptedException) {
          // Do not silently ignore interrupted state.
          Thread.currentThread().interrupt();
        }
        // Do not mask the exception that caused the write to fail.
        e.addSuppressed(closeException);
      }
      throw e;
    }
  }

  private static class ApplyShardingKey<T> extends DoFn<T, KV<Integer, T>> {
    private final PCollectionView<Integer> numShardsView;
    private final ValueProvider<Integer> numShardsProvider;
    private int shardNumber;

    ApplyShardingKey(PCollectionView<Integer> numShardsView,
                     ValueProvider<Integer> numShardsProvider) {
      this.numShardsView = numShardsView;
      this.numShardsProvider = numShardsProvider;
      shardNumber = UNKNOWN_SHARDNUM;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      final int shardCount;
      if (numShardsView != null) {
        shardCount = context.sideInput(numShardsView);
      } else {
        checkNotNull(numShardsProvider);
        shardCount = numShardsProvider.get();
      }
      checkArgument(
          shardCount > 0,
          "Must have a positive number of shards specified for non-runner-determined sharding."
              + " Got %s",
          shardCount);
      if (shardNumber == UNKNOWN_SHARDNUM) {
        // We want to desynchronize the first record sharding key for each instance of
        // ApplyShardingKey, so records in a small PCollection will be statistically balanced.
        shardNumber = ThreadLocalRandom.current().nextInt(shardCount);
      } else {
        shardNumber = (shardNumber + 1) % shardCount;
      }
      context.output(KV.of(shardNumber, context.element()));
    }
  }

  /**
   * A write is performed as sequence of three {@link ParDo}'s.
   *
   * <p>This singleton collection containing the WriteOperation is then used as a side
   * input to a ParDo over the PCollection of elements to write. In this bundle-writing phase,
   * {@link WriteOperation#createWriter} is called to obtain a {@link Writer}.
   * {@link Writer#open} and {@link Writer#close} are called in
   * {@link DoFn.StartBundle} and {@link DoFn.FinishBundle}, respectively, and
   * {@link Writer#write} method is called for every element in the bundle. The output
   * of this ParDo is a PCollection of <i>writer result</i> objects (see {@link FileBasedSink}
   * for a description of writer results)-one for each bundle.
   *
   * <p>The final do-once ParDo uses a singleton collection asinput and the collection of writer
   * results as a side-input. In this ParDo, {@link WriteOperation#finalize} is called
   * to finalize the write.
   *
   * <p>If the write of any element in the PCollection fails, {@link Writer#close} will be
   * called before the exception that caused the write to fail is propagated and the write result
   * will be discarded.
   *
   * <p>Since the {@link WriteOperation} is serialized after the initialization ParDo and
   * deserialized in the bundle-writing and finalization phases, any state change to the
   * WriteOperation object that occurs during initialization is visible in the latter
   * phases. However, the WriteOperation is not serialized after the bundle-writing
   * phase. This is why implementations should guarantee that
   * {@link WriteOperation#createWriter} does not mutate WriteOperation).
   */
  private PDone createWrite(PCollection<T> input) {
    Pipeline p = input.getPipeline();

    if (!windowedWrites) {
      // Re-window the data into the global window and remove any existing triggers.
      input =
          input.apply(
              Window.<T>into(new GlobalWindows())
                  .triggering(DefaultTrigger.of())
                  .discardingFiredPanes());
    }


    // Perform the per-bundle writes as a ParDo on the input PCollection (with the
    // WriteOperation as a side input) and collect the results of the writes in a
    // PCollection. There is a dependency between this ParDo and the first (the
    // WriteOperation PCollection as a side input), so this will happen after the
    // initial ParDo.
    PCollection<FileResult> results;
    final PCollectionView<Integer> numShardsView;
    @SuppressWarnings("unchecked")
    Coder<BoundedWindow> shardedWindowCoder =
        (Coder<BoundedWindow>) input.getWindowingStrategy().getWindowFn().windowCoder();
    if (computeNumShards == null && numShardsProvider == null) {
      numShardsView = null;
      if (windowedWrites) {
        TupleTag<FileResult> writtenRecordsTag = new TupleTag<>("writtenRecordsTag");
        TupleTag<KV<Integer, T>> unwrittedRecordsTag = new TupleTag<>("unwrittenRecordsTag");
        PCollectionTuple writeTuple = input.apply("WriteWindowedBundles", ParDo.of(
            new WriteWindowedBundles(unwrittedRecordsTag))
            .withOutputTags(writtenRecordsTag, TupleTagList.of(unwrittedRecordsTag)));
        PCollection<FileResult> writtenBundleFiles = writeTuple.get(writtenRecordsTag)
            .setCoder(FileResultCoder.of(shardedWindowCoder));
        // Any "spilled" elements are written using WriteShardedBundles. Assign shard numbers in
        // finalize to stay consistent with what WriteWindowedBundles does.
        PCollection<FileResult> writtenGroupedFiles =
            writeTuple
                .get(unwrittedRecordsTag)
                .setCoder(KvCoder.of(VarIntCoder.of(), input.getCoder()))
                .apply("GroupUnwritten", GroupByKey.<Integer, T>create())
                .apply("WriteUnwritten", ParDo.of(
                    new WriteShardedBundles(ShardAssignment.ASSIGN_IN_FINALIZE)))
                .setCoder(FileResultCoder.of(shardedWindowCoder));
        results = PCollectionList.of(writtenBundleFiles).and(writtenGroupedFiles)
            .apply(Flatten.<FileResult>pCollections());
      } else {
        results =
            input.apply("WriteUnwindowedBundles", ParDo.of(new WriteUnwindowedBundles()));
      }
    } else {
      List<PCollectionView<?>> sideInputs = Lists.newArrayList();
      if (computeNumShards != null) {
        numShardsView = input.apply(computeNumShards);
        sideInputs.add(numShardsView);
      } else {
        numShardsView = null;
      }

      PCollection<KV<Integer, Iterable<T>>> sharded =
          input
              .apply("ApplyShardLabel", ParDo.of(
                  new ApplyShardingKey<T>(numShardsView,
                      (numShardsView != null) ? null : numShardsProvider))
                  .withSideInputs(sideInputs))
              .apply("GroupIntoShards", GroupByKey.<Integer, T>create());
      // Since this path might be used by streaming runners processing triggers, it's important
      // to assign shard numbers here so that they are deterministic. The ASSIGN_IN_FINALIZE
      // strategy works by sorting all FileResult objects and assigning them numbers, which is not
      // guaranteed to work well when processing triggers - if the finalize step retries it might
      // see a different Iterable of FileResult objects, and it will assign different shard numbers.
      results = sharded.apply("WriteShardedBundles",
          ParDo.of(new WriteShardedBundles(ShardAssignment.ASSIGN_WHEN_WRITING)));
    }
    results.setCoder(FileResultCoder.of(shardedWindowCoder));

    if (windowedWrites) {
      // When processing streaming windowed writes, results will arrive multiple times. This
      // means we can't share the below implementation that turns the results into a side input,
      // as new data arriving into a side input does not trigger the listening DoFn. Instead
      // we aggregate the result set using a singleton GroupByKey, so the DoFn will be triggered
      // whenever new data arrives.
      PCollection<KV<Void, FileResult>> keyedResults =
          results.apply("AttachSingletonKey", WithKeys.<Void, FileResult>of((Void) null));
      keyedResults.setCoder(KvCoder.of(VoidCoder.of(),
          FileResultCoder.of(shardedWindowCoder)));

      // Is the continuation trigger sufficient?
      keyedResults
          .apply("FinalizeGroupByKey", GroupByKey.<Void, FileResult>create())
          .apply("Finalize", ParDo.of(new DoFn<KV<Void, Iterable<FileResult>>, Integer>() {
            @ProcessElement
            public void processElement(ProcessContext c) throws Exception {
              LOG.info("Finalizing write operation {}.", writeOperation);
              List<FileResult> results = Lists.newArrayList(c.element().getValue());
              writeOperation.finalize(results);
              LOG.debug("Done finalizing write operation");
            }
          }));
    } else {
      final PCollectionView<Iterable<FileResult>> resultsView =
          results.apply(View.<FileResult>asIterable());
      ImmutableList.Builder<PCollectionView<?>> sideInputs =
          ImmutableList.<PCollectionView<?>>builder().add(resultsView);
      if (numShardsView != null) {
        sideInputs.add(numShardsView);
      }

      // Finalize the write in another do-once ParDo on the singleton collection containing the
      // Writer. The results from the per-bundle writes are given as an Iterable side input.
      // The WriteOperation's state is the same as after its initialization in the first
      // do-once ParDo. There is a dependency between this ParDo and the parallel write (the writer
      // results collection as a side input), so it will happen after the parallel write.
      // For the non-windowed case, we guarantee that  if no data is written but the user has
      // set numShards, then all shards will be written out as empty files. For this reason we
      // use a side input here.
      PCollection<Void> singletonCollection = p.apply(Create.of((Void) null));
      singletonCollection
          .apply("Finalize", ParDo.of(new DoFn<Void, Integer>() {
            @ProcessElement
            public void processElement(ProcessContext c) throws Exception {
              LOG.info("Finalizing write operation {}.", writeOperation);
              List<FileResult> results = Lists.newArrayList(c.sideInput(resultsView));
              LOG.debug("Side input initialized to finalize write operation {}.", writeOperation);

              // We must always output at least 1 shard, and honor user-specified numShards if
              // set.
              int minShardsNeeded;
              if (numShardsView != null) {
                minShardsNeeded = c.sideInput(numShardsView);
              } else if (numShardsProvider != null) {
                minShardsNeeded = numShardsProvider.get();
              } else {
                minShardsNeeded = 1;
              }
              int extraShardsNeeded = minShardsNeeded - results.size();
              if (extraShardsNeeded > 0) {
                LOG.info(
                    "Creating {} empty output shards in addition to {} written for a total of {}.",
                    extraShardsNeeded, results.size(), minShardsNeeded);
                for (int i = 0; i < extraShardsNeeded; ++i) {
                  Writer<T> writer = writeOperation.createWriter();
                  writer.openUnwindowed(UUID.randomUUID().toString(), UNKNOWN_SHARDNUM);
                  FileResult emptyWrite = writer.close();
                  results.add(emptyWrite);
                }
                LOG.debug("Done creating extra shards.");
              }
              writeOperation.finalize(results);
              LOG.debug("Done finalizing write operation {}", writeOperation);
            }
          }).withSideInputs(sideInputs.build()));
    }
    return PDone.in(input.getPipeline());
  }
}
