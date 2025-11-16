package parser.interop

import scala.deriving.Mirror
import scala.quoted.{Expr, Quotes, Type}

import parser.core._

/**
 * Automatic parser derivation for case classes using Scala 3 macros.
 *
 * This object provides compile-time derivation of parsers for product types (case classes).
 * It uses Scala 3's macro system and Mirror-based reflection to generate efficient parsers
 * that parse case classes in the format: `ClassName(field1,field2,field3)`
 *
 * Example:
 * {{{
 * import parser.interop.Primitives.given
 * import parser.interop.Parser.derived
 *
 * case class Person(name: String, age: Int)
 * given Parser[ParseError, Person] = derived[Person]
 *
 * val parser = summon[Parser[ParseError, Person]]
 * parser.run("Person(Alice,30)")  // Success(Person("Alice", 30), consumed = 16)
 * }}}
 *
 * @since 0.2.0
 */
object Parser {

  /**
   * Derives a parser for a case class automatically.
   *
   * This inline method triggers macro expansion at compile time to generate
   * a parser for the given type A. The type must be a product type (case class)
   * and parsers must be available for all field types.
   *
   * @tparam A The case class type to derive a parser for
   * @return A parser that can parse the case class from input
   */
  inline def derived[A](using m: Mirror.ProductOf[A]): parser.core.Parser[ParseError, A] =
    ${ deriveParserImpl[A]('m) }

  /**
   * Macro implementation for parser derivation.
   *
   * This method performs compile-time reflection on the product type to:
   * 1. Extract the class name from Mirror.MirroredLabel
   * 2. Extract field names from Mirror.MirroredElemLabels
   * 3. Extract field types from Mirror.MirroredElemTypes
   * 4. Summon Parser instances for each field type
   * 5. Generate code that parses the format: ClassName(field1,field2)
   * 6. Reconstruct the case class using Mirror.fromProduct
   *
   * @param m Expression representing the Mirror instance
   * @tparam A The product type being derived
   * @return Expression representing the generated parser
   */
  def deriveParserImpl[A: Type](
    m: Expr[Mirror.ProductOf[A]]
  )(using q: Quotes): Expr[parser.core.Parser[ParseError, A]] = {
    import q.reflect.*

    // Extract the class name (e.g., "Person" from case class Person)
    val className: String = TypeRepr.of[A].typeSymbol.name

    // Get labels and element types by inspecting case class directly
    val aSymbol      = TypeRepr.of[A].typeSymbol
    val fieldSymbols = aSymbol.caseFields

    // Extract field labels
    val fieldLabels: List[String] = fieldSymbols.map(_.name)

    // Summon parsers for each field type
    val fieldParsers: List[Expr[parser.core.Parser[ParseError, Any]]] =
      fieldSymbols.map { field =>
        val fieldType = field.tree match {
          case v: ValDef => v.tpt.tpe
          case _         => report.errorAndAbort(s"Expected ValDef for field ${field.name}")
        }

        fieldType.asType match {
          case '[ft] =>
            Expr.summon[parser.core.Parser[ParseError, ft]] match {
              case Some(parserExpr) =>
                parserExpr.asExprOf[parser.core.Parser[ParseError, Any]]
              case None =>
                report.errorAndAbort(
                  s"Cannot derive Parser for ${TypeRepr.of[A].show}: " +
                    s"missing Parser[ParseError, ${fieldType.show}]. " +
                    s"Please provide a given instance."
                )
            }
        }
      }.toList

    // Generate the parser code
    generateParser[A](className, fieldLabels, fieldParsers, m)
  }

  /**
   * Generates the parser code that combines all field parsers.
   *
   * Creates a parser that:
   * 1. Parses the class name
   * 2. Parses opening parenthesis
   * 3. Parses each field separated by commas
   * 4. Parses closing parenthesis
   * 5. Reconstructs the case class using Mirror.fromProduct
   *
   * Format: `ClassName(field1,field2,field3)`
   *
   * @param className The name of the case class
   * @param fieldLabels The names of the fields (for error messages)
   * @param fieldParsers The parsers for each field
   * @param mirror The Mirror instance for reconstruction
   * @return Expression representing the complete parser
   */
  private def generateParser[A: Type](
    className: String,
    @annotation.unused fieldLabels: List[String],
    fieldParsers: List[Expr[parser.core.Parser[ParseError, Any]]],
    mirror: Expr[Mirror.ProductOf[A]]
  )(using q: Quotes): Expr[parser.core.Parser[ParseError, A]] = {

    val parsersExpr: Expr[List[parser.core.Parser[ParseError, Any]]] =
      Expr.ofList(fieldParsers)

    '{
      // Use parser.core.Parser.Custom to access the runtime interpreter
      parser.core.Parser.Custom[ParseError, A] { state =>
        import parser.syntax.*

        val parsers = ${ parsersExpr }

        // Build the parser: ClassName(field1,field2,field3)
        val classNameParser = string(${ Expr(className) })
        val openParen       = char('(')
        val closeParen      = char(')')
        val comma           = char(',')

        // Parse the class name and opening paren
        val prefixParser = classNameParser ~ openParen

        // Parse fields separated by commas
        def parseFields(remaining: List[parser.core.Parser[ParseError, Any]])
          : parser.core.Parser[ParseError, List[Any]] =
          remaining match {
            case Nil => parser.core.Parser.Succeed(Nil)
            case head :: Nil =>
              head.map(v => List(v))
            case head :: tail =>
              (head <* comma).flatMap { firstValue =>
                parseFields(tail).map(restValues => firstValue :: restValues)
              }
          }

        val fieldsParser = parseFields(parsers)

        // Complete parser: prefix ~ fields ~ closeParen
        val completeParser = prefixParser *> fieldsParser <* closeParen

        // Run the parser and reconstruct the case class
        val result = parser.runtime.interpret(completeParser, state)

        result match {
          case Result.Success(fieldValues, consumed) =>
            // Reconstruct the case class from field values
            val product = ${ mirror }.fromProduct(Tuple.fromArray(fieldValues.toArray))
            Result.Success(product, consumed)
          case Result.Failure(errors, furthest) =>
            Result.Failure(errors, furthest)
        }
      }
    }
  }
}
