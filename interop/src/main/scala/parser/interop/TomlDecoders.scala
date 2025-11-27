package parser.interop

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}

import parser.core._
import parsers.toml.{TomlValue, given_CanEqual_TomlValue_TomlValue}

/**
 * Decoder instances for TOML values.
 *
 * These given instances provide automatic decoder derivation for primitive types
 * and common collections when decoding from TomlValue.
 *
 * TOML has richer type support than JSON, including:
 * - Integers (Long)
 * - Floats (Double)
 * - DateTime types (OffsetDateTime, LocalDateTime, LocalDate, LocalTime)
 * - Arrays (homogeneous)
 * - Inline Tables
 *
 * Example:
 * {{{
 * import parser.interop.TomlDecoders.given
 *
 * val tomlString = TomlValue.String("hello")
 * val result = Decoder[TomlValue, String].decode(tomlString)
 * // Success("hello", 0)
 * }}}
 */
object TomlDecoders {

  private val defaultLoc: Location = (line = 1, column = 1, offset = 0)

  /**
   * Decoder for String from TomlValue.
   */
  given Decoder[TomlValue, String] = new Decoder[TomlValue, String] {
    def decode(value: TomlValue): Result[DecodeError, String] = value match {
      case TomlValue.String(s) => Result.Success(s, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Int from TomlValue.
   */
  given Decoder[TomlValue, Int] = new Decoder[TomlValue, Int] {
    def decode(value: TomlValue): Result[DecodeError, Int] = value match {
      case TomlValue.Integer(n) if n >= Int.MinValue && n <= Int.MaxValue =>
        Result.Success(n.toInt, 0)
      case TomlValue.Integer(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", s"Integer($n) - out of range", defaultLoc)),
          defaultLoc
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Long from TomlValue.
   */
  given Decoder[TomlValue, Long] = new Decoder[TomlValue, Long] {
    def decode(value: TomlValue): Result[DecodeError, Long] = value match {
      case TomlValue.Integer(n) => Result.Success(n, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Long", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Double from TomlValue.
   */
  given Decoder[TomlValue, Double] = new Decoder[TomlValue, Double] {
    def decode(value: TomlValue): Result[DecodeError, Double] = value match {
      case TomlValue.Float(n)   => Result.Success(n, 0)
      case TomlValue.Integer(n) => Result.Success(n.toDouble, 0) // Allow integer promotion
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Double", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Float from TomlValue.
   */
  given Decoder[TomlValue, Float] = new Decoder[TomlValue, Float] {
    def decode(value: TomlValue): Result[DecodeError, Float] = value match {
      case TomlValue.Float(n)   => Result.Success(n.toFloat, 0)
      case TomlValue.Integer(n) => Result.Success(n.toFloat, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Float", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Boolean from TomlValue.
   */
  given Decoder[TomlValue, Boolean] = new Decoder[TomlValue, Boolean] {
    def decode(value: TomlValue): Result[DecodeError, Boolean] = value match {
      case TomlValue.Boolean(b) => Result.Success(b, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Boolean", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for OffsetDateTime from TomlValue.
   */
  given Decoder[TomlValue, OffsetDateTime] = new Decoder[TomlValue, OffsetDateTime] {
    def decode(value: TomlValue): Result[DecodeError, OffsetDateTime] = value match {
      case TomlValue.DateTime(dt) => Result.Success(dt, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("OffsetDateTime", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for LocalDateTime from TomlValue.
   */
  given Decoder[TomlValue, LocalDateTime] = new Decoder[TomlValue, LocalDateTime] {
    def decode(value: TomlValue): Result[DecodeError, LocalDateTime] = value match {
      case TomlValue.LocalDateTime(dt) => Result.Success(dt, 0)
      case TomlValue.DateTime(dt)      => Result.Success(dt.toLocalDateTime, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("LocalDateTime", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for LocalDate from TomlValue.
   */
  given Decoder[TomlValue, LocalDate] = new Decoder[TomlValue, LocalDate] {
    def decode(value: TomlValue): Result[DecodeError, LocalDate] = value match {
      case TomlValue.LocalDate(d)      => Result.Success(d, 0)
      case TomlValue.LocalDateTime(dt) => Result.Success(dt.toLocalDate, 0)
      case TomlValue.DateTime(dt)      => Result.Success(dt.toLocalDate, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("LocalDate", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for LocalTime from TomlValue.
   */
  given Decoder[TomlValue, LocalTime] = new Decoder[TomlValue, LocalTime] {
    def decode(value: TomlValue): Result[DecodeError, LocalTime] = value match {
      case TomlValue.LocalTime(t)      => Result.Success(t, 0)
      case TomlValue.LocalDateTime(dt) => Result.Success(dt.toLocalTime, 0)
      case TomlValue.DateTime(dt)      => Result.Success(dt.toLocalTime, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("LocalTime", tomlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Option[A] from TomlValue.
   *
   * Note: TOML doesn't have null, so Option is primarily used for
   * optional fields in case class derivation.
   */
  given [A](using decoder: Decoder[TomlValue, A]): Decoder[TomlValue, Option[A]] =
    new Decoder[TomlValue, Option[A]] {
      def decode(value: TomlValue): Result[DecodeError, Option[A]] =
        decoder.decode(value) match {
          case Result.Success(a, consumed)         => Result.Success(Some(a), consumed)
          case Result.Partial(a, errors, consumed) => Result.Partial(Some(a), errors, consumed)
          case Result.Failure(errors, furthest)    => Result.Failure(errors, furthest)
        }
    }

  /**
   * Decoder for List[A] from TomlValue.
   *
   * Expects a TomlValue.Array.
   */
  given [A](using decoder: Decoder[TomlValue, A]): Decoder[TomlValue, List[A]] =
    new Decoder[TomlValue, List[A]] {
      def decode(value: TomlValue): Result[DecodeError, List[A]] = value match {
        case TomlValue.Array(elements) =>
          val decoded                                      = scala.collection.mutable.ListBuffer[A]()
          val errors                                       = scala.collection.mutable.ListBuffer[DecodeError]()
          var failed: Option[Result[DecodeError, List[A]]] = None

          for (elem <- elements if failed.isEmpty)
            decoder.decode(elem) match {
              case Result.Success(a, _) =>
                decoded += a
              case Result.Partial(a, errs, _) =>
                decoded += a
                errors ++= errs
              case Result.Failure(errs, _) =>
                failed = Some(Result.Failure(errs, defaultLoc))
            }

          failed.getOrElse {
            if (errors.isEmpty) Result.Success(decoded.toList, 0)
            else Result.Partial(decoded.toList, errors.toList, 0)
          }

        case other =>
          Result.Failure(
            List(DecodeError.TypeMismatch("Array", tomlValueTypeName(other), defaultLoc)),
            defaultLoc
          )
      }
    }

  /**
   * Decoder for Map[String, A] from TomlValue.
   *
   * Expects a TomlValue.InlineTable.
   */
  given [A](using decoder: Decoder[TomlValue, A]): Decoder[TomlValue, Map[String, A]] =
    new Decoder[TomlValue, Map[String, A]] {
      def decode(value: TomlValue): Result[DecodeError, Map[String, A]] = value match {
        case TomlValue.InlineTable(pairs) =>
          val decoded                                             = scala.collection.mutable.Map[String, A]()
          val errors                                              = scala.collection.mutable.ListBuffer[DecodeError]()
          var failed: Option[Result[DecodeError, Map[String, A]]] = None

          for ((key, fieldValue) <- pairs if failed.isEmpty)
            decoder.decode(fieldValue) match {
              case Result.Success(a, _) =>
                decoded(key) = a
              case Result.Partial(a, errs, _) =>
                decoded(key) = a
                errors ++= errs
              case Result.Failure(errs, _) =>
                failed = Some(Result.Failure(errs, defaultLoc))
            }

          failed.getOrElse {
            if (errors.isEmpty) Result.Success(decoded.toMap, 0)
            else Result.Partial(decoded.toMap, errors.toList, 0)
          }

        case other =>
          Result.Failure(
            List(DecodeError.TypeMismatch("InlineTable", tomlValueTypeName(other), defaultLoc)),
            defaultLoc
          )
      }
    }

  private def tomlValueTypeName(value: TomlValue): String = value match {
    case TomlValue.String(_)        => "String"
    case TomlValue.Integer(_)       => "Integer"
    case TomlValue.Float(_)         => "Float"
    case TomlValue.Boolean(_)       => "Boolean"
    case TomlValue.DateTime(_)      => "DateTime"
    case TomlValue.LocalDateTime(_) => "LocalDateTime"
    case TomlValue.LocalDate(_)     => "LocalDate"
    case TomlValue.LocalTime(_)     => "LocalTime"
    case TomlValue.Array(_)         => "Array"
    case TomlValue.InlineTable(_)   => "InlineTable"
  }
}
