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

package com.spotify.scio.extra

import java.io.File
import java.net.URI
import java.nio.file.Path

import com.spotify.scio.extra.transforms.{DownloadDoFn, FileDoFn, PipeDoFn, WorkerDoFn}
import com.spotify.scio.util.Functions
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.transforms.ParDo

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * Main package for worker operation APIs. Import all.
 */
package object worker {

  /**
   * Enhanced version of [[SCollection]] with worker methods.
   */
  implicit class WorkerSCollection[T: ClassTag](val self: SCollection[T]) {

    /**
     * Call a command once per worker.
     * @param command the command to call
     * @param environment environment variables
     * @param dir the working directory of the sub-process
     */
    def runOnWorkers(command: String,
                     environment: Map[String, String] = Map.empty,
                     dir: File = null): SCollection[T] =
      self.applyTransform(ParDo.of(new WorkerDoFn[T](command, environment.asJava, dir)))

    /**
     * Call a command once per worker.
     * @param cmdArray array containing the command to call and its arguments
     * @param environment environment variables
     * @param dir the working directory of the sub-process
     */
    def runOnWorkers(cmdArray: Array[String],
                     environment: Map[String, String],
                     dir: File): SCollection[T] =
      self.applyTransform(ParDo.of(new WorkerDoFn[T](cmdArray, environment.asJava, dir)))

    /**
     * Download a single URI once per worker.
     * @param uri URI to download
     * @param destination destination directory to download to
     * @return
     */
    def downloadToWorkers(uri: String, destination: String = "."): SCollection[T] =
      downloadToWorkers(Seq(uri), destination)

    /**
     * Download a list of URIs once per worker.
     * @param uris list of URIs to download
     * @param destination destination directory to download to
     * @return
     */
    def downloadToWorkers(uris: Seq[String], destination: String): SCollection[T] =
      self.applyTransform(ParDo.of(
        new DownloadDoFn[T](uris.map(new URI(_)).asJava, new File(destination))))

  }

  /**
   * Enhanced version of [[SCollection]] with [[URI]] methods.
   */
  implicit class URISCollection(val self: SCollection[URI]) extends AnyVal {

    /**
     * Download [[URI]] elements and process as local [[Path]]s.
     */
    def processFile[T: ClassTag](f: Path => T): SCollection[T] =
      self.applyTransform(ParDo.of(new FileDoFn[T](Functions.serializableFn(f))))

  }

  /**
   * Enhanced version of [[SCollection]] with pipe methods.
   */
  implicit class PipeSCollection(val self: SCollection[String]) extends AnyVal {

    /**
     * Pipe elements through an external command via StdIn & StdOut.
     * @param command the command to call
     * @param environment environment variables
     * @param dir the working directory of the sub-process
     */
    def pipe(command: String,
             environment: Map[String, String] = Map.empty,
             dir: File = null): SCollection[String] =
      self.applyTransform(ParDo.of(new PipeDoFn(command, environment.asJava, dir)))

    /**
     * Pipe elements through an external command via StdIn & StdOut.
     * @param cmdArray array containing the command to call and its arguments
     * @param environment environment variables
     * @param dir the working directory of the sub-process
     */
    def pipe(cmdArray: Array[String],
             environment: Map[String, String],
             dir: File): SCollection[String] =
      self.applyTransform(ParDo.of(new PipeDoFn(cmdArray, environment.asJava, dir)))

  }

}
