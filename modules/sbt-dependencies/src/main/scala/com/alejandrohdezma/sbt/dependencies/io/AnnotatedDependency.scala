package com.alejandrohdezma.sbt.dependencies.io

import scala.jdk.CollectionConverters._

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValueType

/** A dependency entry that may optionally carry a note explaining why it is configured a certain way. */
final case class AnnotatedDependency(line: String, note: Option[String] = None) {

  /** Formats a single dependency entry as HOCON. */
  def format: String = note match {
    case None    => s""""$line""""
    case Some(n) => s"""{ dependency = "$line", note = "$n" }"""
  }

}

object AnnotatedDependency {

  final case class NoteKey(organization: String, name: String, configuration: String)

  /** Parses a dependency list that may contain both plain strings and annotated objects. */
  def parse(config: Config, path: String): Either[String, List[AnnotatedDependency]] =
    config
      .getList(path)
      .asScala
      .toList
      .foldLeft(Right(List.empty[AnnotatedDependency]): Either[String, List[AnnotatedDependency]]) {
        // If the accumulator already contains an error, return it immediately
        case (Left(err), _) => Left(err)

        // Otherwise, try to parse the value as a string or object
        case (Right(acc), value) =>
          value.valueType() match {
            // If the value is a string, create a new annotated dependency with no note
            case ConfigValueType.STRING =>
              Right(acc :+ AnnotatedDependency(value.unwrapped().asInstanceOf[String]))

            // If the value is an object, check if it has a 'dependency' and 'note' field
            case ConfigValueType.OBJECT =>
              val obj = value.asInstanceOf[ConfigObject].toConfig
              if (!obj.hasPath("dependency")) Left("object entry must have a 'dependency' field")
              else if (!obj.hasPath("note")) Left("object entry must have a 'note' field")
              else Right(acc :+ AnnotatedDependency(obj.getString("dependency"), Some(obj.getString("note"))))

            // If the value is anything else, return an error
            case other =>
              Left(s"expected string or object in dependency list, got $other")
          }
      }

  /** Creates an annotated dependency from a dependency and a map of existing notes. */
  def from(notes: Map[NoteKey, String])(dep: Dependency): AnnotatedDependency = {
    val noteKey = NoteKey(dep.organization, dep.name, dep.configuration)

    AnnotatedDependency(dep.toLine, notes.get(noteKey))
  }

}
