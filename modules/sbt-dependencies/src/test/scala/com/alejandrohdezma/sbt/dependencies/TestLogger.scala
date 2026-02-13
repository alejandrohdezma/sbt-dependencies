package com.alejandrohdezma.sbt.dependencies

import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Eq._

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
