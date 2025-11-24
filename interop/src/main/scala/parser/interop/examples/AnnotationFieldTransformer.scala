package parser.interop.examples

import scala.quoted.{Expr, Quotes, Type}
import parser.interop.FieldTransformer

/**
 * Example implementation: Creating a FieldTransformer by reading field annotations.
 *
 * This demonstrates how to use Scala 3 inline metaprogramming (quotes reflection API)
 * to read annotations from a case class and generate a customized FieldTransformer.
 *
 * IMPORTANT: This is example code showing the technique. Users can adapt this
 * pattern to implement their own annotation-based transformers.
 *
 * Example usage:
 * {{{
 * import parser.interop.examples.{Rename, Ignore, AnnotationFieldTransformer}
 *
 * case class User(
 *   @Rename("user_name") name: String,
 *   @Ignore internal: String = "default",
 *   age: Int
 * )
 *
 * // Create transformer by reading annotations at compile time
 * val transformer = AnnotationFieldTransformer.derive[User]
 *
 * transformer.transformFieldName("name")     // "user_name"
 * transformer.transformFieldName("age")      // "age"
 * transformer.shouldIncludeField("internal") // false
 * transformer.shouldIncludeField("age")      // true
 * }}}
 *
 * @since 0.2.0
 */
object AnnotationFieldTransformer {

  /**
   * Derive a FieldTransformer by reading annotations from a case class type.
   *
   * This inline method triggers compile-time analysis of the case class to extract
   * annotation information and generate a runtime FieldTransformer.
   *
   * Supports:
   * - @Rename("new_name") - Maps field to different name
   * - @Ignore - Excludes field from processing
   *
   * @tparam T The case class type to analyze
   * @return A FieldTransformer configured based on the type's annotations
   */
  inline def derive[T]: FieldTransformer = ${ deriveImpl[T] }

  /**
   * Macro implementation using Scala 3 quotes reflection API.
   *
   * Key techniques demonstrated:
   * 1. Access constructor parameters via primaryConstructor.paramSymss
   * 2. Check for annotations using hasAnnotation
   * 3. Extract annotation values using getAnnotation and pattern matching
   * 4. Generate runtime data structures from compile-time information
   *
   * Note: We use primaryConstructor.paramSymss instead of caseFields because
   * caseFields symbols have empty annotations in Scala 3 (known limitation).
   */
  def deriveImpl[T: Type](using q: Quotes): Expr[FieldTransformer] = {
    import q.reflect.*

    val typeSymbol = TypeRepr.of[T].typeSymbol
    val fields = typeSymbol.primaryConstructor.paramSymss.flatten

    // Get annotation type symbols for checking
    val renameSymbol = TypeRepr.of[Rename].typeSymbol
    val ignoreSymbol = TypeRepr.of[Ignore].typeSymbol

    // Extract rename mappings: originalName -> renamedName
    val renameMappings: Map[String, String] = fields.flatMap { field =>
      val originalName = field.name

      if (field.hasAnnotation(renameSymbol)) {
        // Extract the annotation and get the name argument
        field.getAnnotation(renameSymbol).flatMap {
          // Match on Apply node with string literal argument
          case Apply(_, List(Literal(StringConstant(newName)))) =>
            Some(originalName -> newName)
          // Match on Apply node with named argument
          case Apply(_, NamedArg("name", Literal(StringConstant(newName))) :: Nil) =>
            Some(originalName -> newName)
          case _ =>
            report.warning(
              s"Could not extract @Rename value for field '$originalName'. " +
              "Ensure annotation is used as @Rename(\"name\") with a string literal."
            )
            None
        }
      } else None
    }.toMap

    // Extract ignored field names
    val ignoredFields: Set[String] = fields
      .filter(_.hasAnnotation(ignoreSymbol))
      .map(_.name)
      .toSet

    // Convert compile-time data to runtime expressions
    val renameMappingsExpr = Expr(renameMappings)
    val ignoredFieldsExpr = Expr(ignoredFields)

    // Generate the runtime FieldTransformer instance
    '{
      val renameMap = $renameMappingsExpr
      val ignoredSet = $ignoredFieldsExpr

      new FieldTransformer {
        def transformFieldName(fieldName: String): String = {
          renameMap.getOrElse(fieldName, fieldName)
        }

        def shouldIncludeField(fieldName: String): Boolean = {
          !ignoredSet.contains(fieldName)
        }
      }
    }
  }
}
