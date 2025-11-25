package parser.interop

import scala.deriving.Mirror
import scala.quoted.{Expr, Quotes, Type}

import parser.core._
import parsers.json.{JsonValue, given_CanEqual_JsonValue_JsonValue}
import parsers.toml.{TomlValue, given_CanEqual_TomlValue_TomlValue}
import parsers.xml.{XmlNode, given_CanEqual_XmlNode_XmlNode}
import parsers.yaml.{YamlValue, given_CanEqual_YamlValue_YamlValue}

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
    val toSymbol = TypeRepr.of[To].typeSymbol

    // Use primaryConstructor.paramSymss to get constructor parameters
    // Note: caseFields can also be used, but paramSymss is more direct
    val fieldSymbols = toSymbol.primaryConstructor.paramSymss.flatten

    // Extract field labels (just use field names directly)
    val fieldLabels: List[String] = fieldSymbols.map(_.name)

    // Support multiple source types
    TypeRepr.of[From].typeSymbol.fullName match {
      case "parsers.json.JsonValue" =>
        generateJsonDecoder[To](using q)(className, fieldLabels, fieldSymbols, m)
          .asExprOf[Decoder[From, To]]
      case "parsers.toml.TomlValue" =>
        generateTomlDecoder[To](using q)(className, fieldLabels, fieldSymbols, m)
          .asExprOf[Decoder[From, To]]
      case "parsers.yaml.YamlValue" =>
        generateYamlDecoder[To](using q)(className, fieldLabels, fieldSymbols, m)
          .asExprOf[Decoder[From, To]]
      case "parsers.xml.XmlNode" =>
        generateXmlDecoder[To](using q)(className, fieldLabels, fieldSymbols, m)
          .asExprOf[Decoder[From, To]]
      case other =>
        report.errorAndAbort(
          s"Decoder derivation supports JsonValue, TomlValue, YamlValue, and XmlNode. Got $other"
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
                        // On failure, add a default value (null) to maintain tuple structure
                        decodedFields += null
                        errors ++= errs
                    }
                  case None =>
                    // Missing field
                    decodedFields += null
                    errors += DecodeError.MissingField(
                      fieldName,
                      (line = 1, column = 1, offset = 0)
                    )
                }

              // Reconstruct the case class
              if (errors.isEmpty) {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Success(product, 0)
              } else {
                // If there are errors, we still try to reconstruct (with nulls)
                // This is for partial parsing support
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Partial(product, errors.toList, 0)
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
                    (line = 1, column = 1, offset = 0)
                  )
                ),
                (line = 1, column = 1, offset = 0)
              )
          }
        }
      }
    }
  }

  /**
   * Generate a decoder for TomlValue -> case class.
   *
   * Generates code that:
   * 1. Expects a TomlValue.InlineTable
   * 2. Looks up each field by name
   * 3. Decodes each field using its Decoder instance
   * 4. Reconstructs the case class
   */
  private def generateTomlDecoder[To: Type](using q: Quotes)(
    @annotation.unused className: String,
    fieldLabels: List[String],
    fieldSymbols: List[q.reflect.Symbol],
    mirror: Expr[Mirror.ProductOf[To]]
  ): Expr[Decoder[TomlValue, To]] = {
    import q.reflect.*

    val fieldDecoders: List[Expr[Decoder[TomlValue, Any]]] =
      fieldSymbols.map { field =>
        val fieldType = field.tree match {
          case v: ValDef => v.tpt.tpe
          case _         => report.errorAndAbort(s"Expected ValDef for field ${field.name}")
        }

        fieldType.asType match {
          case '[ft] =>
            Expr.summon[Decoder[TomlValue, ft]] match {
              case Some(decoderExpr) =>
                decoderExpr.asExprOf[Decoder[TomlValue, Any]]
              case None =>
                report.errorAndAbort(
                  s"Cannot derive Decoder for ${TypeRepr.of[To].show}: " +
                    s"missing Decoder[TomlValue, ${fieldType.show}]. " +
                    s"Please provide a given instance."
                )
            }
        }
      }.toList

    val fieldLabelsExpr                                   = Expr(fieldLabels)
    val decodersExpr: Expr[List[Decoder[TomlValue, Any]]] = Expr.ofList(fieldDecoders)

    '{
      new Decoder[TomlValue, To] {
        def decode(value: TomlValue): Result[DecodeError, To] = {
          val fieldLabels   = ${ fieldLabelsExpr }
          val fieldDecoders = ${ decodersExpr }

          value match {
            case TomlValue.InlineTable(fields) =>
              val decodedFields = scala.collection.mutable.ListBuffer[Any]()
              val errors        = scala.collection.mutable.ListBuffer[DecodeError]()

              for ((fieldName, decoder) <- fieldLabels.zip(fieldDecoders))
                fields.get(fieldName) match {
                  case Some(fieldValue) =>
                    decoder.decode(fieldValue) match {
                      case Result.Success(v, _) =>
                        decodedFields += v
                      case Result.Partial(v, errs, _) =>
                        decodedFields += v
                        errors ++= errs
                      case Result.Failure(errs, _) =>
                        decodedFields += null
                        errors ++= errs
                    }
                  case None =>
                    decodedFields += null
                    errors += DecodeError.MissingField(
                      fieldName,
                      (line = 1, column = 1, offset = 0)
                    )
                }

              if (errors.isEmpty) {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Success(product, 0)
              } else {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Partial(product, errors.toList, 0)
              }

            case other =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch(
                    "InlineTable",
                    other match {
                      case TomlValue.String(_)        => "String"
                      case TomlValue.Integer(_)       => "Integer"
                      case TomlValue.Float(_)         => "Float"
                      case TomlValue.Boolean(_)       => "Boolean"
                      case TomlValue.DateTime(_)      => "DateTime"
                      case TomlValue.LocalDateTime(_) => "LocalDateTime"
                      case TomlValue.LocalDate(_)     => "LocalDate"
                      case TomlValue.LocalTime(_)     => "LocalTime"
                      case TomlValue.Array(_)         => "Array"
                      case _                          => "Unknown"
                    },
                    (line = 1, column = 1, offset = 0)
                  )
                ),
                (line = 1, column = 1, offset = 0)
              )
          }
        }
      }
    }
  }

  /**
   * Generate a decoder for YamlValue -> case class.
   *
   * Generates code that:
   * 1. Expects a YamlValue.Mapping
   * 2. Looks up each field by name
   * 3. Decodes each field using its Decoder instance
   * 4. Reconstructs the case class
   */
  private def generateYamlDecoder[To: Type](using q: Quotes)(
    @annotation.unused className: String,
    fieldLabels: List[String],
    fieldSymbols: List[q.reflect.Symbol],
    mirror: Expr[Mirror.ProductOf[To]]
  ): Expr[Decoder[YamlValue, To]] = {
    import q.reflect.*

    val fieldDecoders: List[Expr[Decoder[YamlValue, Any]]] =
      fieldSymbols.map { field =>
        val fieldType = field.tree match {
          case v: ValDef => v.tpt.tpe
          case _         => report.errorAndAbort(s"Expected ValDef for field ${field.name}")
        }

        fieldType.asType match {
          case '[ft] =>
            Expr.summon[Decoder[YamlValue, ft]] match {
              case Some(decoderExpr) =>
                decoderExpr.asExprOf[Decoder[YamlValue, Any]]
              case None =>
                report.errorAndAbort(
                  s"Cannot derive Decoder for ${TypeRepr.of[To].show}: " +
                    s"missing Decoder[YamlValue, ${fieldType.show}]. " +
                    s"Please provide a given instance."
                )
            }
        }
      }.toList

    val fieldLabelsExpr                                   = Expr(fieldLabels)
    val decodersExpr: Expr[List[Decoder[YamlValue, Any]]] = Expr.ofList(fieldDecoders)

    '{
      new Decoder[YamlValue, To] {
        def decode(value: YamlValue): Result[DecodeError, To] = {
          val fieldLabels   = ${ fieldLabelsExpr }
          val fieldDecoders = ${ decodersExpr }

          value match {
            case YamlValue.Mapping(fields) =>
              val decodedFields = scala.collection.mutable.ListBuffer[Any]()
              val errors        = scala.collection.mutable.ListBuffer[DecodeError]()

              for ((fieldName, decoder) <- fieldLabels.zip(fieldDecoders))
                fields.get(fieldName) match {
                  case Some(fieldValue) =>
                    decoder.decode(fieldValue) match {
                      case Result.Success(v, _) =>
                        decodedFields += v
                      case Result.Partial(v, errs, _) =>
                        decodedFields += v
                        errors ++= errs
                      case Result.Failure(errs, _) =>
                        decodedFields += null
                        errors ++= errs
                    }
                  case None =>
                    decodedFields += null
                    errors += DecodeError.MissingField(
                      fieldName,
                      (line = 1, column = 1, offset = 0)
                    )
                }

              if (errors.isEmpty) {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Success(product, 0)
              } else {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Partial(product, errors.toList, 0)
              }

            case other =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch(
                    "Mapping",
                    other match {
                      case YamlValue.Null        => "Null"
                      case YamlValue.Boolean(_)  => "Boolean"
                      case YamlValue.Integer(_)  => "Integer"
                      case YamlValue.Float(_)    => "Float"
                      case YamlValue.String(_)   => "String"
                      case YamlValue.Sequence(_) => "Sequence"
                      case _                     => "Unknown"
                    },
                    (line = 1, column = 1, offset = 0)
                  )
                ),
                (line = 1, column = 1, offset = 0)
              )
          }
        }
      }
    }
  }

  /**
   * Generate a decoder for XmlNode -> case class.
   *
   * Generates code that:
   * 1. Expects an XmlNode.Element
   * 2. Looks up each field by child element name
   * 3. Decodes each field using its Decoder instance
   * 4. Reconstructs the case class
   *
   * For XML, fields map to child elements with matching names.
   */
  private def generateXmlDecoder[To: Type](using q: Quotes)(
    @annotation.unused className: String,
    fieldLabels: List[String],
    fieldSymbols: List[q.reflect.Symbol],
    mirror: Expr[Mirror.ProductOf[To]]
  ): Expr[Decoder[XmlNode, To]] = {
    import q.reflect.*

    val fieldDecoders: List[Expr[Decoder[XmlNode, Any]]] =
      fieldSymbols.map { field =>
        val fieldType = field.tree match {
          case v: ValDef => v.tpt.tpe
          case _         => report.errorAndAbort(s"Expected ValDef for field ${field.name}")
        }

        fieldType.asType match {
          case '[ft] =>
            Expr.summon[Decoder[XmlNode, ft]] match {
              case Some(decoderExpr) =>
                decoderExpr.asExprOf[Decoder[XmlNode, Any]]
              case None =>
                report.errorAndAbort(
                  s"Cannot derive Decoder for ${TypeRepr.of[To].show}: " +
                    s"missing Decoder[XmlNode, ${fieldType.show}]. " +
                    s"Please provide a given instance."
                )
            }
        }
      }.toList

    val fieldLabelsExpr                                 = Expr(fieldLabels)
    val decodersExpr: Expr[List[Decoder[XmlNode, Any]]] = Expr.ofList(fieldDecoders)

    '{
      new Decoder[XmlNode, To] {
        def decode(value: XmlNode): Result[DecodeError, To] = {
          val fieldLabels   = ${ fieldLabelsExpr }
          val fieldDecoders = ${ decodersExpr }

          value match {
            case elem: XmlNode.Element =>
              val decodedFields = scala.collection.mutable.ListBuffer[Any]()
              val errors        = scala.collection.mutable.ListBuffer[DecodeError]()

              // Build a map of child elements by local name
              val childMap: Map[String, XmlNode] = elem.children.collect {
                case child: XmlNode.Element => child.name.localName -> (child: XmlNode)
              }.toMap

              for ((fieldName, decoder) <- fieldLabels.zip(fieldDecoders))
                childMap.get(fieldName) match {
                  case Some(childElem) =>
                    decoder.decode(childElem) match {
                      case Result.Success(v, _) =>
                        decodedFields += v
                      case Result.Partial(v, errs, _) =>
                        decodedFields += v
                        errors ++= errs
                      case Result.Failure(errs, _) =>
                        decodedFields += null
                        errors ++= errs
                    }
                  case None =>
                    decodedFields += null
                    errors += DecodeError.MissingField(
                      fieldName,
                      (line = 1, column = 1, offset = 0)
                    )
                }

              if (errors.isEmpty) {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Success(product, 0)
              } else {
                val product = ${ mirror }.fromProduct(Tuple.fromArray(decodedFields.toArray))
                Result.Partial(product, errors.toList, 0)
              }

            case other =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch(
                    "Element",
                    other match {
                      case XmlNode.Text(_)                     => "Text"
                      case XmlNode.CData(_)                    => "CData"
                      case XmlNode.Comment(_)                  => "Comment"
                      case XmlNode.ProcessingInstruction(_, _) => "ProcessingInstruction"
                      case _                                   => "Unknown"
                    },
                    (line = 1, column = 1, offset = 0)
                  )
                ),
                (line = 1, column = 1, offset = 0)
              )
          }
        }
      }
    }
  }
}
