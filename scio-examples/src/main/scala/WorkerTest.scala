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

import com.spotify.scio._
import com.spotify.scio.extra.worker._

import scala.io.Source

// scalastyle:off

// fixed 2 workers, 2 cores each
// runMain WorkerTest --runner=DataflowRunner --project=scio-playground \
// --numWorkers=2 --maxNumWorkers=2 --workerMachineType=n1-standard-2
object WorkerTest {

  private val testPath = "gs://scio-playground/random-files"

  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, _) = ContextAndArgs(cmdlineArgs)

    val f = sc.parallelize(0 to 9)
      .map(i => new URI(testPath + "/part-%05d-of-01000.txt".format(i)))

      // download input URIs and process as local Paths
      .processFile(p => Source.fromFile(p.toFile).getLines().toList)
      .flatMap(identity)

      // run once per worker
      .runOnWorkers("apt-get update")
      .runOnWorkers("apt-get install -y php5")

      // download once per worker
      .downloadToWorkers("gs://scio-playground/pipe.php")

      // pipe elements through a process via STDIN & STDOUT
      .pipe("php pipe.php")

      .materialize

    sc.close().waitUntilFinish()
    f.waitForResult().value.take(10).foreach(println)
  }
}
