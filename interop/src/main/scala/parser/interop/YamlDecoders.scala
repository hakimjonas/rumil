package parser.interop

import parser.core._
import parsers.yaml.{YamlValue, given_CanEqual_YamlValue_YamlValue}

/**
 * Decoder instances for YAML values.
 *
 * These given instances provide automatic decoder derivation for primitive types
 * and common collections when decoding from YamlValue.
 *
 * YAML supports:
 * - Null
 * - Booleans (with multiple spellings: true/false, yes/no, on/off)
 * - Integers (Long)
 * - Floats (Double)
 * - Strings
 * - Sequences (Lists)
 * - Mappings (Maps)
 *
 * Example:
 * {{{
 * import parser.interop.YamlDecoders.given
 *
 * val yamlString = YamlValue.String("hello")
 * val result = Decoder[YamlValue, String].decode(yamlString)
 * // Success("hello", 0)
 * }}}
 */
object YamlDecoders {

  private val defaultLoc: Location = (line = 1, column = 1, offset = 0)

  /**
   * Decoder for String from YamlValue.
   */
  given Decoder[YamlValue, String] = new Decoder[YamlValue, String] {
    def decode(value: YamlValue): Result[DecodeError, String] = value match {
      case YamlValue.String(s) => Result.Success(s, 0)
      // YAML often represents other types as strings when unquoted
      case YamlValue.Integer(n) => Result.Success(n.toString, 0)
      case YamlValue.Float(n)   => Result.Success(n.toString, 0)
      case YamlValue.Boolean(b) => Result.Success(b.toString, 0)
      case YamlValue.Null       => Result.Success("null", 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Int from YamlValue.
   */
  given Decoder[YamlValue, Int] = new Decoder[YamlValue, Int] {
    def decode(value: YamlValue): Result[DecodeError, Int] = value match {
      case YamlValue.Integer(n) if n >= Int.MinValue && n <= Int.MaxValue =>
        Result.Success(n.toInt, 0)
      case YamlValue.Integer(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", s"Integer($n) - out of range", defaultLoc)),
          defaultLoc
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Long from YamlValue.
   */
  given Decoder[YamlValue, Long] = new Decoder[YamlValue, Long] {
    def decode(value: YamlValue): Result[DecodeError, Long] = value match {
      case YamlValue.Integer(n) => Result.Success(n, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Long", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Double from YamlValue.
   */
  given Decoder[YamlValue, Double] = new Decoder[YamlValue, Double] {
    def decode(value: YamlValue): Result[DecodeError, Double] = value match {
      case YamlValue.Float(n)   => Result.Success(n, 0)
      case YamlValue.Integer(n) => Result.Success(n.toDouble, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Double", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Float from YamlValue.
   */
  given Decoder[YamlValue, Float] = new Decoder[YamlValue, Float] {
    def decode(value: YamlValue): Result[DecodeError, Float] = value match {
      case YamlValue.Float(n)   => Result.Success(n.toFloat, 0)
      case YamlValue.Integer(n) => Result.Success(n.toFloat, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Float", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Boolean from YamlValue.
   */
  given Decoder[YamlValue, Boolean] = new Decoder[YamlValue, Boolean] {
    def decode(value: YamlValue): Result[DecodeError, Boolean] = value match {
      case YamlValue.Boolean(b) => Result.Success(b, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Boolean", yamlValueTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Option[A] from YamlValue.
   *
   * YamlValue.Null decodes to None.
   */
  given [A](using decoder: Decoder[YamlValue, A]): Decoder[YamlValue, Option[A]] =
    new Decoder[YamlValue, Option[A]] {
      def decode(value: YamlValue): Result[DecodeError, Option[A]] = value match {
        case YamlValue.Null => Result.Success(None, 0)
        case other =>
          decoder.decode(other) match {
            case Result.Success(a, consumed)         => Result.Success(Some(a), consumed)
            case Result.Partial(a, errors, consumed) => Result.Partial(Some(a), errors, consumed)
            case Result.Failure(errors, furthest)    => Result.Failure(errors, furthest)
          }
      }
    }

  /**
   * Decoder for List[A] from YamlValue.
   *
   * Expects a YamlValue.Sequence.
   */
  given [A](using decoder: Decoder[YamlValue, A]): Decoder[YamlValue, List[A]] =
    new Decoder[YamlValue, List[A]] {
      def decode(value: YamlValue): Result[DecodeError, List[A]] = value match {
        case YamlValue.Sequence(elements) =>
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
            List(DecodeError.TypeMismatch("Sequence", yamlValueTypeName(other), defaultLoc)),
            defaultLoc
          )
      }
    }

  /**
   * Decoder for Seq[A] from YamlValue.
   */
  given [A](using decoder: Decoder[YamlValue, A]): Decoder[YamlValue, Seq[A]] =
    new Decoder[YamlValue, Seq[A]] {
      def decode(value: YamlValue): Result[DecodeError, Seq[A]] = {
        val listDecoder = summon[Decoder[YamlValue, List[A]]]
        listDecoder.decode(value) match {
          case Result.Success(list, consumed) => Result.Success(list.toSeq, consumed)
          case Result.Partial(list, errors, consumed) =>
            Result.Partial(list.toSeq, errors, consumed)
          case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        }
      }
    }

  /**
   * Decoder for Vector[A] from YamlValue.
   */
  given [A](using decoder: Decoder[YamlValue, A]): Decoder[YamlValue, Vector[A]] =
    new Decoder[YamlValue, Vector[A]] {
      def decode(value: YamlValue): Result[DecodeError, Vector[A]] = {
        val listDecoder = summon[Decoder[YamlValue, List[A]]]
        listDecoder.decode(value) match {
          case Result.Success(list, consumed) => Result.Success(list.toVector, consumed)
          case Result.Partial(list, errors, consumed) =>
            Result.Partial(list.toVector, errors, consumed)
          case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        }
      }
    }

  /**
   * Decoder for Map[String, A] from YamlValue.
   *
   * Expects a YamlValue.Mapping.
   */
  given [A](using decoder: Decoder[YamlValue, A]): Decoder[YamlValue, Map[String, A]] =
    new Decoder[YamlValue, Map[String, A]] {
      def decode(value: YamlValue): Result[DecodeError, Map[String, A]] = value match {
        case YamlValue.Mapping(pairs) =>
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
            List(DecodeError.TypeMismatch("Mapping", yamlValueTypeName(other), defaultLoc)),
            defaultLoc
          )
      }
    }

  private def yamlValueTypeName(value: YamlValue): String = value match {
    case YamlValue.Null        => "Null"
    case YamlValue.Boolean(_)  => "Boolean"
    case YamlValue.Integer(_)  => "Integer"
    case YamlValue.Float(_)    => "Float"
    case YamlValue.String(_)   => "String"
    case YamlValue.Sequence(_) => "Sequence"
    case YamlValue.Mapping(_)  => "Mapping"
  }
}
