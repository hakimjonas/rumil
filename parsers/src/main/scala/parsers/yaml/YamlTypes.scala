package parsers.yaml

import scala.language.strictEquality

// ============================================================================
// YAML TYPES - YAML 1.2 (Enums, No Case Classes)
// ============================================================================

/**
 * YAML value types as per YAML 1.2 specification.
 */
enum YamlValue {
  case Null
  case Boolean(value: scala.Boolean)
  case Integer(value: Long)
  case Float(value: Double)
  case String(value: scala.Predef.String)
  case Sequence(elements: List[YamlValue])
  case Mapping(pairs: Map[scala.Predef.String, YamlValue])
}

// CanEqual instance for strict equality
given CanEqual[YamlValue, YamlValue] = CanEqual.derived

/**
 * YAML document.
 *
 * @param root Root value
 * @param directives Document directives (e.g., %YAML 1.2)
 */
type YamlDocument = (
  root: YamlValue,
  directives: List[scala.Predef.String]
)

/**
 * YAML parsing context for tracking indentation levels.
 *
 * @param currentIndent Current indentation level
 * @param blockContext Whether in block context
 */
type YamlContext = (
  currentIndent: Int,
  blockContext: scala.Boolean
)

/**
 * Default YAML context.
 */
val defaultYamlContext: YamlContext = (
  currentIndent = 0,
  blockContext = true
)
