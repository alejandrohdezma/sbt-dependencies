/*
 * Copyright 2025-2026 Alejandro Hernández <https://github.com/alejandrohdezma>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alejandrohdezma.sbt.dependencies

import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** A `Logger` for tests that records all log messages, traces, and successes so they can be inspected via assertions.
  */
trait TestLogger extends Logger {

  /** Clears all recorded logs, traces, and successes. */
  def cleanLogs(): Unit

  /** Returns all recorded log messages for the given level. */
  def getLogs(level: Level.Value): List[String]

  /** Returns all recorded throwables passed to `trace`. */
  def getTraces(): List[Throwable]

  /** Returns all recorded messages passed to `success`. */
  def getSuccesses(): List[String]

}

object TestLogger {

  /** Creates a new `TestLogger` backed by mutable buffers. */
  def apply(): TestLogger = new TestLogger {

    private val traces = scala.collection.mutable.ListBuffer[Throwable]()

    private val successes = scala.collection.mutable.ListBuffer[String]()

    private val logs = scala.collection.mutable.ListBuffer[(Level.Value, String)]()

    override def trace(t: => Throwable): Unit = traces.append(t)

    override def success(message: => String): Unit = successes.append(message)

    override def log(level: Level.Value, message: => String): Unit = logs.append((level, message))

    override def cleanLogs(): Unit = {
      traces.clear()
      successes.clear()
      logs.clear()
    }

    override def getLogs(level: Level.Value): List[String] =
      logs.filter(_._1.id === level.id).map(_._2).toList

    override def getTraces(): List[Throwable] = traces.toList

    override def getSuccesses(): List[String] = successes.toList

  }

}
