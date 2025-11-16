package parser.interop

import parsers.json.JsonValue
import parser.core._

/**
 * Primitive decoder instances for JSON values.
 *
 * These given instances provide automatic decoder derivation for primitive types
 * and common collections when decoding from JsonValue.
 *
 * Example:
 * {{{
 * import parser.interop.JsonDecoders.given
 *
 * val jsonString = JsonValue.Str("hello")
 * val result = Decoder[JsonValue, String].decode(jsonString)
 * // Success("hello", 0)
 * }}}
 */
object JsonDecoders {

  // ============================================================================
  // Primitive Type Decoders
  // ============================================================================

  /**
   * Decoder for String from JsonValue.
   *
   * Expects a JsonValue.Str, fails with TypeMismatch otherwise.
   */
  given Decoder[JsonValue, String] = new Decoder[JsonValue, String] {
    def decode(value: JsonValue): Result[DecodeError, String] = value match {
      case JsonValue.Str(s) => Result.Success(s, 0)
      case JsonValue.Null =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", "Null", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Bool(b) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", s"Boolean($b)", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", s"Number($n)", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Array(_) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", "Array", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Object(_) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", "Object", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Int from JsonValue.
   *
   * Expects a JsonValue.Number with a whole number value.
   */
  given Decoder[JsonValue, Int] = new Decoder[JsonValue, Int] {
    def decode(value: JsonValue): Result[DecodeError, Int] = value match {
      case JsonValue.Number(n) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue =>
        Result.Success(n.toInt, 0)
      case JsonValue.Number(n) if !n.isWhole =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", s"Number($n) - not a whole number", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", s"Number($n) - out of range", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Int", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Long from JsonValue.
   *
   * Expects a JsonValue.Number with a whole number value.
   */
  given Decoder[JsonValue, Long] = new Decoder[JsonValue, Long] {
    def decode(value: JsonValue): Result[DecodeError, Long] = value match {
      case JsonValue.Number(n) if n.isWhole && n >= Long.MinValue && n <= Long.MaxValue =>
        Result.Success(n.toLong, 0)
      case JsonValue.Number(n) if !n.isWhole =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Long", s"Number($n) - not a whole number", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Long", s"Number($n) - out of range", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Long", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Double from JsonValue.
   *
   * Expects a JsonValue.Number.
   */
  given Decoder[JsonValue, Double] = new Decoder[JsonValue, Double] {
    def decode(value: JsonValue): Result[DecodeError, Double] = value match {
      case JsonValue.Number(n) => Result.Success(n, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Double", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Boolean from JsonValue.
   *
   * Expects a JsonValue.Bool.
   */
  given Decoder[JsonValue, Boolean] = new Decoder[JsonValue, Boolean] {
    def decode(value: JsonValue): Result[DecodeError, Boolean] = value match {
      case JsonValue.Bool(b) => Result.Success(b, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Boolean", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Byte from JsonValue.
   *
   * Expects a JsonValue.Number with a whole number in Byte range.
   */
  given Decoder[JsonValue, Byte] = new Decoder[JsonValue, Byte] {
    def decode(value: JsonValue): Result[DecodeError, Byte] = value match {
      case JsonValue.Number(n) if n.isWhole && n >= Byte.MinValue && n <= Byte.MaxValue =>
        Result.Success(n.toByte, 0)
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Byte", s"Number($n) - out of range or not whole", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Byte", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Short from JsonValue.
   *
   * Expects a JsonValue.Number with a whole number in Short range.
   */
  given Decoder[JsonValue, Short] = new Decoder[JsonValue, Short] {
    def decode(value: JsonValue): Result[DecodeError, Short] = value match {
      case JsonValue.Number(n) if n.isWhole && n >= Short.MinValue && n <= Short.MaxValue =>
        Result.Success(n.toShort, 0)
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Short", s"Number($n) - out of range or not whole", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Short", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for Float from JsonValue.
   *
   * Expects a JsonValue.Number.
   */
  given Decoder[JsonValue, Float] = new Decoder[JsonValue, Float] {
    def decode(value: JsonValue): Result[DecodeError, Float] = value match {
      case JsonValue.Number(n) => Result.Success(n.toFloat, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Float", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for BigInt from JsonValue.
   *
   * Expects a JsonValue.Number with a whole number value.
   */
  given Decoder[JsonValue, BigInt] = new Decoder[JsonValue, BigInt] {
    def decode(value: JsonValue): Result[DecodeError, BigInt] = value match {
      case JsonValue.Number(n) if n.isWhole =>
        Result.Success(BigInt(n.toLong), 0)
      case JsonValue.Number(n) =>
        Result.Failure(
          List(DecodeError.TypeMismatch("BigInt", s"Number($n) - not a whole number", (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("BigInt", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  /**
   * Decoder for BigDecimal from JsonValue.
   *
   * Expects a JsonValue.Number.
   */
  given Decoder[JsonValue, BigDecimal] = new Decoder[JsonValue, BigDecimal] {
    def decode(value: JsonValue): Result[DecodeError, BigDecimal] = value match {
      case JsonValue.Number(n) => Result.Success(BigDecimal(n), 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("BigDecimal", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
          (line = 1, column = 1, offset = 0)
        )
    }
  }

  // ============================================================================
  // Generic Type Decoders
  // ============================================================================

  /**
   * Decoder for Option[A] from JsonValue.
   *
   * - JsonValue.Null decodes to None
   * - Any other value decodes using the decoder for A, wrapped in Some
   */
  given [A](using decoder: Decoder[JsonValue, A]): Decoder[JsonValue, Option[A]] =
    new Decoder[JsonValue, Option[A]] {
      def decode(value: JsonValue): Result[DecodeError, Option[A]] = value match {
        case JsonValue.Null => Result.Success(None, 0)
        case other =>
          decoder.decode(other) match {
            case Result.Success(a, consumed)        => Result.Success(Some(a), consumed)
            case Result.Partial(a, errors, consumed) => Result.Partial(Some(a), errors, consumed)
            case Result.Failure(errors, furthest)   => Result.Failure(errors, furthest)
          }
      }
    }

  /**
   * Decoder for List[A] from JsonValue.
   *
   * Expects a JsonValue.Array and decodes each element using the decoder for A.
   */
  given [A](using decoder: Decoder[JsonValue, A]): Decoder[JsonValue, List[A]] =
    new Decoder[JsonValue, List[A]] {
      def decode(value: JsonValue): Result[DecodeError, List[A]] = value match {
        case JsonValue.Array(elements) =>
          // Decode each element
          val decoded = scala.collection.mutable.ListBuffer[A]()
          val errors  = scala.collection.mutable.ListBuffer[DecodeError]()

          for ((elem, idx) <- elements.zipWithIndex) {
            decoder.decode(elem) match {
              case Result.Success(a, _) =>
                decoded += a
              case Result.Partial(a, errs, _) =>
                decoded += a
                errors ++= errs
              case Result.Failure(errs, _) =>
                // On failure, we can't continue decoding
                return Result.Failure(
                  errs.map {
                    case DecodeError.TypeMismatch(exp, act, loc) =>
                      DecodeError.TypeMismatch(exp, act, loc)
                    case other => other
                  },
                  (line = 1, column = 1, offset = 0)
                )
            }
          }

          if (errors.isEmpty) {
            Result.Success(decoded.toList, 0)
          } else {
            Result.Partial(decoded.toList, errors.toList, 0)
          }

        case other =>
          Result.Failure(
            List(DecodeError.TypeMismatch("Array", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
    }

  /**
   * Decoder for Seq[A] from JsonValue.
   *
   * Expects a JsonValue.Array and decodes each element using the decoder for A.
   */
  given [A](using decoder: Decoder[JsonValue, A]): Decoder[JsonValue, Seq[A]] =
    new Decoder[JsonValue, Seq[A]] {
      def decode(value: JsonValue): Result[DecodeError, Seq[A]] = {
        given listDecoder: Decoder[JsonValue, List[A]] = JsonDecoders.given_Decoder_List[A]
        listDecoder.decode(value).map(_.toSeq)
      }
    }

  /**
   * Decoder for Vector[A] from JsonValue.
   *
   * Expects a JsonValue.Array and decodes each element using the decoder for A.
   */
  given [A](using decoder: Decoder[JsonValue, A]): Decoder[JsonValue, Vector[A]] =
    new Decoder[JsonValue, Vector[A]] {
      def decode(value: JsonValue): Result[DecodeError, Vector[A]] = {
        given listDecoder: Decoder[JsonValue, List[A]] = JsonDecoders.given_Decoder_List[A]
        listDecoder.decode(value).map(_.toVector)
      }
    }

  /**
   * Decoder for Map[String, A] from JsonValue.
   *
   * Expects a JsonValue.Object and decodes each value using the decoder for A.
   */
  given [A](using decoder: Decoder[JsonValue, A]): Decoder[JsonValue, Map[String, A]] =
    new Decoder[JsonValue, Map[String, A]] {
      def decode(value: JsonValue): Result[DecodeError, Map[String, A]] = value match {
        case JsonValue.Object(fields) =>
          // Decode each field value
          val decoded = scala.collection.mutable.Map[String, A]()
          val errors  = scala.collection.mutable.ListBuffer[DecodeError]()

          for ((key, fieldValue) <- fields) {
            decoder.decode(fieldValue) match {
              case Result.Success(a, _) =>
                decoded(key) = a
              case Result.Partial(a, errs, _) =>
                decoded(key) = a
                errors ++= errs
              case Result.Failure(errs, _) =>
                return Result.Failure(errs, (line = 1, column = 1, offset = 0))
            }
          }

          if (errors.isEmpty) {
            Result.Success(decoded.toMap, 0)
          } else {
            Result.Partial(decoded.toMap, errors.toList, 0)
          }

        case other =>
          Result.Failure(
            List(DecodeError.TypeMismatch("Object", jsonValueTypeName(other), (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
    }

  // ============================================================================
  // Helper Functions
  // ============================================================================

  /**
   * Get a human-readable type name for a JsonValue.
   */
  private def jsonValueTypeName(value: JsonValue): String = value match {
    case JsonValue.Null      => "Null"
    case JsonValue.Bool(_)   => "Boolean"
    case JsonValue.Number(_) => "Number"
    case JsonValue.Str(_)    => "String"
    case JsonValue.Array(_)  => "Array"
    case JsonValue.Object(_) => "Object"
  }
}
