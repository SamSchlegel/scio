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

package com.spotify.scio.extra.transforms

import java.util.concurrent.{Callable, CompletableFuture, Executors, ThreadPoolExecutor}
import java.util.function.Supplier

import com.google.common.util.concurrent.{ListenableFuture, MoreExecutors}
import com.spotify.scio.extra.transforms.DoFnWithResource.ResourceType
import com.spotify.scio.testing._
import org.apache.beam.sdk.Pipeline.PipelineExecutionException
import org.apache.beam.sdk.transforms.ParDo

class AsyncDoFnTest extends PipelineSpec {

  private def doFns = Seq(new GuavaDoFn(10), new JavaDoFn(10))

  "AsyncDoFn" should "work" in {
    val inputs = Seq(1, 10, 100).map(n => (1 to n).map(_ * 10))
    for (doFn <- doFns; input <- inputs) {
      runWithContext { sc =>
        val p = sc.parallelize(input).applyTransform(ParDo.of(doFn))
        p should containInAnyOrder (input.map("output-" + _))
      }
    }
  }

  it should "handle failures" in {
    for (doFn <- doFns) {
      // scalastyle:off no.whitespace.before.left.bracket
      val e = the [PipelineExecutionException] thrownBy {
        runWithContext {
          _.parallelize(Seq(1, 2, -1, -2)).applyTransform(ParDo.of(new GuavaDoFn(10)))
        }
      }
      // scalastyle:on no.whitespace.before.left.bracket
      // PipelineExecutionException -> RuntimeException -> ExecutionException -> RuntimeException
      e.getCause should have message "Failed to process futures"
      e.getCause.getCause.getCause should have message "Bad input"
    }
  }

}

private object Client {
  def process(input: Int): String = {
    if (input < 0) {
      throw new RuntimeException("Bad input")
    }
    Thread.sleep(input)
    "output-" + input
  }
}

private class GuavaClient(val numThreads: Int) {
  private val es = MoreExecutors.listeningDecorator(
    MoreExecutors.getExitingExecutorService(
      Executors.newFixedThreadPool(numThreads).asInstanceOf[ThreadPoolExecutor])
  )
  def request(input: Int): ListenableFuture[String] = {
    es.submit(new Callable[String] {
      override def call(): String = Client.process(input)
    })
  }
}

private class GuavaDoFn(val numThreads: Int) extends GuavaAsyncDoFn[Int, String, GuavaClient] {
  override def getResourceType: ResourceType = ResourceType.PER_CLASS
  override def createResource(): GuavaClient = new GuavaClient(numThreads)
  override def processElement(input: Int): ListenableFuture[String] = getResource.request(input)
}

private class JavaClient(val numThreads: Int) {
  private val es = Executors.newFixedThreadPool(numThreads)
  def request(input: Int): CompletableFuture[String] =
    CompletableFuture.supplyAsync(new Supplier[String] {
      override def get(): String = Client.process(input)
    }, es)
}

private class JavaDoFn(val numThreads: Int) extends JavaAsyncDoFn[Int, String, JavaClient] {
  override def getResourceType: ResourceType = ResourceType.PER_CLASS
  override def createResource(): JavaClient = new JavaClient(numThreads)
  override def processElement(input: Int): CompletableFuture[String] = getResource.request(input)
}