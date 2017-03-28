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

import java.nio.file.Files

import com.google.common.base.Charsets
import com.google.common.io.{Files => GFiles}
import com.spotify.scio.testing._
import org.apache.beam.sdk.transforms.ParDo

import scala.collection.JavaConverters._

class PipeDoFnTest extends PipelineSpec {

  private val input = Seq("a", "b", "c")

  "PipeDoFn" should "work" in {
    runWithContext { sc =>
      val d1 = new PipeDoFn("tr '[:lower:]' '[:upper:]'")
      val d2 = new PipeDoFn(Array("tr", "[:lower:]", "[:upper:]"))
      val p1 =  sc.parallelize(input).applyTransform(ParDo.of(d1))
      val p2 =  sc.parallelize(input).applyTransform(ParDo.of(d2))
      p1 should containInAnyOrder (input.map(_.toUpperCase))
      p2 should containInAnyOrder (input.map(_.toUpperCase))
    }
  }

  it should "support environment" in {
    val tmpDir = Files.createTempDirectory("pipedofn")
    val file = tmpDir.resolve("tr.sh")
    GFiles.write("tr $PARG1 $PARG2", file.toFile, Charsets.UTF_8)
    val env = Map("PARG1" -> "[:lower:]", "PARG2" -> "[:upper:]")

    runWithContext { sc =>
      val d1 = new PipeDoFn(s"bash $file", env.asJava, null)
      val d2 = new PipeDoFn(Array("bash", file.toString), env.asJava, null)
      val p1 =  sc.parallelize(input).applyTransform(ParDo.of(d1))
      val p2 =  sc.parallelize(input).applyTransform(ParDo.of(d2))
      p1 should containInAnyOrder (input.map(_.toUpperCase))
      p2 should containInAnyOrder (input.map(_.toUpperCase))
    }

    Files.delete(file)
    Files.delete(tmpDir)
  }

  it should "support working directory" in {
    val tmpDir = Files.createTempDirectory("pipedofn")
    val file = tmpDir.resolve("tr.sh")
    GFiles.write("tr '[:lower:]' '[:upper:]'", file.toFile, Charsets.UTF_8)

    runWithContext { sc =>
      val d1 = new PipeDoFn("bash tr.sh", null, tmpDir.toFile)
      val d2 = new PipeDoFn(Array("bash", "tr.sh"), null, tmpDir.toFile)
      val p1 =  sc.parallelize(input).applyTransform(ParDo.of(d1))
      val p2 =  sc.parallelize(input).applyTransform(ParDo.of(d2))
      p1 should containInAnyOrder (input.map(_.toUpperCase))
      p2 should containInAnyOrder (input.map(_.toUpperCase))
    }

    Files.delete(file)
    Files.delete(tmpDir)
  }

}
