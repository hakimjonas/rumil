package parser.interop

/**
 * Extension point for customizing how case class fields are mapped during decoder derivation.
 *
 * This trait allows users to implement custom field mapping logic, including:
 * - Renaming fields (e.g., snake_case ↔ camelCase)
 * - Ignoring fields during decoding
 * - Accepting multiple names for the same field (aliases)
 * - Any other field-level transformation logic
 *
 * Example: Snake case converter
 * {{{
 * object SnakeCaseTransformer extends FieldTransformer {
 *   def transformFieldName(fieldName: String): String =
 *     fieldName.replaceAll("([A-Z])", "_$1").toLowerCase
 *
 *   def shouldIncludeField(fieldName: String): Boolean = true
 * }
 *
 * // Use with derivation (when supported)
 * given Decoder[JsonValue, User] = Decoder.derivedWith(SnakeCaseTransformer)
 * }}}
 *
 * Example: Annotation-based transformer
 * {{{
 * // See examples package for complete implementation using Scala 3 macros
 * // to read field annotations like @Rename("custom_name")
 * }}}
 *
 * @since 0.2.0
 */
trait FieldTransformer {
  /**
   * Transform a field name during decoder derivation.
   *
   * This method is called for each case class field to determine what name
   * to look for in the source data (JSON, TOML, YAML, XML).
   *
   * @param fieldName The Scala case class field name
   * @return The name to use when looking up the field in source data
   */
  def transformFieldName(fieldName: String): String

  /**
   * Determine whether a field should be included in decoding.
   *
   * Return false to skip a field entirely during decoding. The field
   * must have a default value in the case class definition.
   *
   * @param fieldName The Scala case class field name
   * @return true if the field should be decoded, false to skip it
   */
  def shouldIncludeField(fieldName: String): Boolean
}

/**
 * Default field transformer that uses field names as-is with no transformations.
 */
object IdentityFieldTransformer extends FieldTransformer {
  def transformFieldName(fieldName: String): String = fieldName
  def shouldIncludeField(fieldName: String): Boolean = true
}

/**
 * Commonly used field transformers for standard naming conventions.
 */
object FieldTransformers {
  /**
   * Convert camelCase to snake_case.
   *
   * Example: userName → user_name
   */
  object SnakeCase extends FieldTransformer {
    def transformFieldName(fieldName: String): String =
      fieldName.replaceAll("([A-Z])", "_$1").toLowerCase.stripPrefix("_")

    def shouldIncludeField(fieldName: String): Boolean = true
  }

  /**
   * Convert camelCase to kebab-case.
   *
   * Example: userName → user-name
   */
  object KebabCase extends FieldTransformer {
    def transformFieldName(fieldName: String): String =
      fieldName.replaceAll("([A-Z])", "-$1").toLowerCase.stripPrefix("-")

    def shouldIncludeField(fieldName: String): Boolean = true
  }

  /**
   * Convert to SCREAMING_SNAKE_CASE.
   *
   * Example: userName → USER_NAME
   */
  object ScreamingSnakeCase extends FieldTransformer {
    def transformFieldName(fieldName: String): String =
      fieldName.replaceAll("([A-Z])", "_$1").toUpperCase.stripPrefix("_")

    def shouldIncludeField(fieldName: String): Boolean = true
  }
}
