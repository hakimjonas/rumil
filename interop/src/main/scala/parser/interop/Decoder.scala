package parser.interop

import scala.deriving.Mirror
import scala.quoted.{Expr, Quotes, Type}

import parser.core._
import parsers.json.{JsonValue, given_CanEqual_JsonValue_JsonValue}

/**
 * Typeclass for decoding structured data into Scala types.
 *
 * Unlike Parser which operates on raw strings, Decoder operates on
 * pre-parsed structured data (JsonValue, XmlNode, TomlValue, etc.).
 *
 * This separation of concerns:
 * - Makes error messages clearer (parse errors vs decode errors)
 * - Allows reusing parsed data with different decoders
 * - Follows the pattern used by Circe, upickle, etc.
 *
 * Example:
 * {{{
 * import parser.interop.Decoder
 * import parser.interop.JsonDecoders.given
 *
 * case class Person(name: String, age: Int)
 * given Decoder[JsonValue, Person] = Decoder.derived
 *
 * val json: JsonValue = JsonValue.Object(Map(
 *   "name" -> JsonValue.Str("Alice"),
 *   "age" -> JsonValue.Number(30)
 * ))
 * val person: Result[DecodeError, Person] = Decoder[JsonValue, Person].decode(json)
 * // Success(Person("Alice", 30), 0)
 * }}}
 */
trait Decoder[From, +To] {

  /**
   * Decode a value from structured data.
   *
   * @param value The structured data to decode
   * @return Success with the decoded value, or Failure with decode errors
   */
  def decode(value: From): Result[DecodeError, To]

  /**
   * Map the result of this decoder.
   *
   * @param f The function to apply to successful results
   * @return A new decoder that applies f to decoded values
   */
  def map[B](f: To => B): Decoder[From, B] = new Decoder[From, B] {
    def decode(value: From): Result[DecodeError, B] =
      Decoder.this.decode(value) match {
        case Result.Success(a, consumed)         => Result.Success(f(a), consumed)
        case Result.Partial(a, errors, consumed) => Result.Partial(f(a), errors, consumed)
        case Result.Failure(errors, furthest)    => Result.Failure(errors, furthest)
      }
  }

  /**
   * FlatMap for decoders.
   *
   * @param f The function to apply to successful results
   * @return A new decoder that chains this decoder with f
   */
  def flatMap[B](f: To => Decoder[From, B]): Decoder[From, B] = new Decoder[From, B] {
    def decode(value: From): Result[DecodeError, B] =
      Decoder.this.decode(value) match {
        case Result.Success(a, _) => f(a).decode(value)
        case Result.Partial(a, errors, _) =>
          f(a).decode(value) match {
            case Result.Success(b, consumed) => Result.Partial(b, errors, consumed)
            case Result.Partial(b, moreErrors, consumed) =>
              Result.Partial(b, errors ++ moreErrors, consumed)
            case Result.Failure(moreErrors, furthest) =>
              Result.Failure(errors ++ moreErrors, furthest)
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
      }
  }
}

object Decoder {

  /**
   * Summon a Decoder instance from implicit scope.
   *
   * Example:
   * {{{
   * val decoder = Decoder[JsonValue, Person]
   * }}}
   */
  def apply[From, To](using decoder: Decoder[From, To]): Decoder[From, To] = decoder

  /**
   * Automatically derive a Decoder for a case class.
   *
   * Uses Scala 3 macros to inspect case class fields and generate
   * decoding logic at compile time.
   *
   * Currently supports:
   * - JsonValue as the source type
   * - Case classes with primitive fields
   * - Nested case classes
   * - Collections (List, Option, etc.)
   *
   * Example:
   * {{{
   * case class User(name: String, age: Int, email: String)
   * given Decoder[JsonValue, User] = Decoder.derived
   * }}}
   */
  inline def derived[From, To](using m: Mirror.ProductOf[To]): Decoder[From, To] =
    ${ deriveDecoderImpl[From, To]('m) }

  /**
   * Macro implementation for decoder derivation.
   *
   * This method performs compile-time reflection on the product type to:
   * 1. Extract the class name from the type symbol
   * 2. Extract field names from case class fields
   * 3. Extract field types from case class fields
   * 4. Summon Decoder instances for each field type
   * 5. Generate code that extracts fields from JsonObject
   * 6. Reconstruct the case class using Mirror.fromProduct
   *
   * @param m Expression representing the Mirror instance
   * @tparam From The source type (e.g., JsonValue)
   * @tparam To The target product type (case class)
   * @return Expression representing the generated decoder
   */
  def deriveDecoderImpl[From: Type, To: Type](
    m: Expr[Mirror.ProductOf[To]]
  )(using q: Quotes): Expr[Decoder[From, To]] = {
    import q.reflect.*

    // Extract the class name (e.g., "Person" from case class Person)
    val className: String = TypeRepr.of[To].typeSymbol.name

    // Get field symbols from the case class
    val aSymbol      = TypeRepr.of[To].typeSymbol
    val fieldSymbols = aSymbol.caseFields

    // Extract field labels
    val fieldLabels: List[String] = fieldSymbols.map(_.name)

    // For now, only support JsonValue as From type
    // (can extend to XmlNode, TomlValue later)
    TypeRepr.of[From].typeSymbol.fullName match {
      case "parsers.json.JsonValue" =>
        generateJsonDecoder[To](using q)(className, fieldLabels, fieldSymbols, m)
          .asExprOf[Decoder[From, To]]
      case other =>
        report.errorAndAbort(
          s"Decoder derivation currently only supports JsonValue as source type, got $other"
        )
    }
  }

  /**
   * Generate a decoder for JsonValue -> case class.
   *
   * Generates code that:
   * 1. Expects a JsonObject
   * 2. Looks up each field by name
   * 3. Decodes each field using its Decoder instance
   * 4. Reconstructs the case class
   *
   * @param className The name of the case class
   * @param fieldLabels The names of the fields
   * @param fieldSymbols The field symbols (for extracting types)
   * @param mirror The Mirror instance for reconstruction
   * @return Expression representing the generated decoder
   */
  private def generateJsonDecoder[To: Type](using q: Quotes)(
    @annotation.unused className: String,
    fieldLabels: List[String],
    fieldSymbols: List[q.reflect.Symbol],
    mirror: Expr[Mirror.ProductOf[To]]
  ): Expr[Decoder[JsonValue, To]] = {
    import q.reflect.*

    // Summon decoders for each field type - same pattern as Parser.derived
    val fieldDecoders: List[Expr[Decoder[JsonValue, Any]]] =
      fieldSymbols.map { field =>
        val fieldType = field.tree match {
          case v: ValDef => v.tpt.tpe
          case _         => report.errorAndAbort(s"Expected ValDef for field ${field.name}")
        }

        fieldType.asType match {
          case '[ft] =>
            Expr.summon[Decoder[JsonValue, ft]] match {
              case Some(decoderExpr) =>
                decoderExpr.asExprOf[Decoder[JsonValue, Any]]
              case None =>
                report.errorAndAbort(
                  s"Cannot derive Decoder for ${TypeRepr.of[To].show}: " +
                    s"missing Decoder[JsonValue, ${fieldType.show}]. " +
                    s"Please provide a given instance."
                )
            }
        }
      }.toList

    val fieldLabelsExpr                                   = Expr(fieldLabels)
    val decodersExpr: Expr[List[Decoder[JsonValue, Any]]] = Expr.ofList(fieldDecoders)

    '{
      new Decoder[JsonValue, To] {
        def decode(value: JsonValue): Result[DecodeError, To] = {
          val fieldLabels   = ${ fieldLabelsExpr }
          val fieldDecoders = ${ decodersExpr }

          // Expect a JsonObject
          value match {
            case JsonValue.Object(fields) =>
              // Decode each field
              val decodedFields = scala.collection.mutable.ListBuffer[Any]()
              val errors        = scala.collection.mutable.ListBuffer[DecodeError]()

              for ((fieldName, decoder) <- fieldLabels.zip(fieldDecoders))
                fields.get(fieldName) match {
                  case Some(fieldValue) =>
                    // Decode the field
                    decoder.decode(fieldValue) match {
                      case Result.Success(v, _) =>
                        decodedFields += v
                      case Result.Partial(v, errs, _) =>
                        decodedFields += v
                        errors ++= errs
                      case Result.Failure(errs, _) =>
                        // On failure, record errors but don't add to fields
                        // We cannot safely reconstruct without all fields
                        errors ++= errs
                    }
                  case None =>
                    // Missing field - record error
                    errors += DecodeError.MissingField(
                      fieldName,
                      UnknownLocation
                    )
                }

              // Only reconstruct if we successfully decoded all fields
              if (errors.isEmpty) {
                // All fields decoded successfully
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Success(product, 0)
              } else if (decodedFields.size == fieldLabels.size) {
                // All fields decoded but with some partial errors
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Partial(product, errors.toList, 0)
              } else {
                // Some fields failed completely - cannot reconstruct safely
                Result.Failure(errors.toList, UnknownLocation)
              }

            case other =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch(
                    "Object",
                    other match {
                      case JsonValue.Null      => "Null"
                      case JsonValue.Bool(_)   => "Boolean"
                      case JsonValue.Number(_) => "Number"
                      case JsonValue.Str(_)    => "String"
                      case JsonValue.Array(_)  => "Array"
                      case _                   => "Unknown"
                    },
                    UnknownLocation
                  )
                ),
                UnknownLocation
              )
          }
        }
      }
    }
  }
}
