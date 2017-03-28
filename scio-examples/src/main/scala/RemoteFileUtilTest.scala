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

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.{Executors, ThreadFactory}

import com.spotify.scio.util.RemoteFileUtil
import org.apache.beam.sdk.options.{GcsOptions, PipelineOptionsFactory}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

// scalastyle:off
object RemoteFileUtilTest {
  def main(args: Array[String]): Unit = {
    val opts = PipelineOptionsFactory
      .fromArgs("--project=deep-learning-1216")
      .as(classOf[GcsOptions])

//    val f = RemoteFileUtil.create(opts)
//    f.download(new URI("gs://tf-learn-001/kinglear.txt"))
//    f.download(new URI("file:///Users/neville/.zshrc"))
//    f.download(new URI("README.md"))

    val f = RemoteFileUtil.create(opts)
    f.upload(Paths.get("README.md"), new URI("gs://scio-playground/fdtest"))
//    testSingleInstance(opts)
  }

  def testFile(i: Int) = new URI("gs://scio-playground/downloader/file-%05d".format(i))

  def testDl(f: RemoteFileUtil, uri: URI) = {
    println("START " + Thread.currentThread() + " " + uri)
    Thread.sleep(1000)
    val r = f.download(uri)
    println("STOP " + Thread.currentThread() + " " + uri)
    r
  }

  def testParallel(opts: GcsOptions) = {
    val f = RemoteFileUtil.create(opts)
    val srcs = (0 to 3).map(testFile).asJava
    f.download(srcs)
  }

  def testMultiInstances(opts: GcsOptions) = {
    val f0 = Future(testDl(RemoteFileUtil.create(opts), testFile(0)))
    val f0a = Future(testDl(RemoteFileUtil.create(opts), testFile(0)))
    val f0b = Future(testDl(RemoteFileUtil.create(opts), testFile(0)))
    val f0c = Future(testDl(RemoteFileUtil.create(opts), testFile(0)))
    val f0d = Future(testDl(RemoteFileUtil.create(opts), testFile(0)))
    val f1 = Future(testDl(RemoteFileUtil.create(opts), testFile(1)))
    val f2 = Future(testDl(RemoteFileUtil.create(opts), testFile(2)))
    val f3 = Future(testDl(RemoteFileUtil.create(opts), testFile(3)))
    println(Await.result(Future.sequence(Seq(f0, f1, f2, f3, f0a, f0b, f0c, f0d)), Duration.Inf))
  }

  def testSingleInstance(opts: GcsOptions) = {
    val f = RemoteFileUtil.create(opts)
    val f0 = Future(testDl(f, testFile(0)))
    val f0a = Future(testDl(f, testFile(0)))
    val f0b = Future(testDl(f, testFile(0)))
    val f0c = Future(testDl(f, testFile(0)))
    val f0d = Future(testDl(f, testFile(0)))
    val f1 = Future(testDl(f, testFile(1)))
    val f2 = Future(testDl(f, testFile(2)))
    val f3 = Future(testDl(f, testFile(3)))
    println(Await.result(Future.sequence(Seq(f0, f1, f2, f3, f0a, f0b, f0c, f0d)), Duration.Inf))
  }

  implicit val ec = {
    val tf = new ThreadFactory {
      override def newThread(r: Runnable) = {
        val t = new Thread(r)
        t.setDaemon(true)
        t
      }
    }
    val es = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors() * 2, tf)
    ExecutionContext.fromExecutorService(es)
  }

}
