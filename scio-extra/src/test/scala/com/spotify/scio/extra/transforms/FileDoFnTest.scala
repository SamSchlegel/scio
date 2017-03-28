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

import java.nio.file.{Files, Path}

import com.google.common.base.Charsets
import com.google.common.io.{Files => GFiles}
import com.spotify.scio.testing._
import org.apache.beam.sdk.transforms.{ParDo, SerializableFunction}

import scala.collection.JavaConverters._

class FileDoFnTest extends PipelineSpec {

  "FileDoFn" should "work" in {
    val tmpDir = Files.createTempDirectory("filedofn")
    val files = createFiles(tmpDir, 100)
    runWithContext { sc =>
      val p = sc.parallelize(files.map(_.toUri))
        .applyTransform(ParDo.of(new FileDoFn(fn)))
        .flatMap(identity)
      p.keys should containInAnyOrder ((1 to 100).map(_.toString))
      p.values.distinct should forAll { f: Path =>
        !Files.exists(f)
      }
    }
    files.foreach(Files.delete)
    Files.delete(tmpDir)
  }

  it should "support batch" in {
    val tmpDir = Files.createTempDirectory("filedofn")
    val files = createFiles(tmpDir, 100)
    runWithContext { sc =>
      val p = sc.parallelize(files.map(_.toUri))
        .applyTransform(ParDo.of(new FileDoFn(fn, 10, false)))
        .flatMap(identity)
      p.keys should containInAnyOrder ((1 to 100).map(_.toString))
      p.values.distinct should forAll { f: Path =>
        !Files.exists(f)
      }
    }
    files.foreach(Files.delete)
    Files.delete(tmpDir)
  }

  it should "support keeping downloaded files" in {
    val tmpDir = Files.createTempDirectory("filedofn")
    val files = createFiles(tmpDir, 100)
    runWithContext { sc =>
      val p = sc.parallelize(files.map(_.toUri))
        .applyTransform(ParDo.of(new FileDoFn(fn, 10, true)))
        .flatMap(identity)
      p.keys should containInAnyOrder ((1 to 100).map(_.toString))
      p.values.distinct should forAll { f: Path =>
        val r = Files.exists(f)
        if (r) {
          Files.delete(f)
        }
        r
      }
    }
    files.foreach(Files.delete)
    Files.delete(tmpDir)
  }

  private def createFiles(dir: Path, n: Int): Seq[Path] =
    (1 to n).map { i =>
      val file = dir.resolve("part-%05d-of-%05d.txt".format(i, n))
      GFiles.write(i.toString, file.toFile, Charsets.UTF_8)
      file
    }

  private val fn = new SerializableFunction[Path, Seq[(String, Path)]] {
    override def apply(input: Path): Seq[(String, Path)] =
      Files.readAllLines(input).asScala.map((_, input))
  }

}
