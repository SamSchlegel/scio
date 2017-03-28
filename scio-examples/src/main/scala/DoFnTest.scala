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
import java.nio.file.Path
import java.util.UUID

import com.spotify.scio._
import com.spotify.scio.extra.transforms.PipeDoFn
import com.spotify.scio.extra.transforms.FileDoFn
import org.apache.beam.sdk.transforms.ParDo

import scala.io.Source

// scalastyle:off
object DoFnTest {

  private val testPath = "gs://scio-playground/random-files"

  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, _) = ContextAndArgs(cmdlineArgs)
//    doFnWithFiles(sc)
    doFnWithPipe(sc)
  }

  def writeFiles(sc: ScioContext): Unit = {
    sc.parallelize(0 to 99)
      .flatMap { _ =>
        (0 to 9999).map(_ => UUID.randomUUID().toString)
      }
      .saveAsTextFile(testPath, numShards = 1000)
    sc.close()
  }

  /*
  def doFnWithFiles(sc: ScioContext): Unit = {
    val f = sc.parallelize(0 to 99)
      .map(i => new URI(testPath + "/part-%05d-of-01000.txt".format(i)))
      .applyTransform(ParDo.of(new FileDoFn[Int](16) {
        override def processFile(path: Path): Int =
          Source.fromFile(path.toUri).getLines().size
      }))
      .materialize
    sc.close().waitUntilFinish()
    f.waitForResult().value.foreach(println)
  }
  */

  def doFnWithPipe(sc: ScioContext): Unit = {
    val f = sc.parallelize(0 to 99)
      .map(_.toString)
      .applyTransform(ParDo.of(new PipeDoFn("wc -l ")))
      .materialize
    sc.close().waitUntilFinish()
    f.waitForResult().value.foreach(println)
  }

}
