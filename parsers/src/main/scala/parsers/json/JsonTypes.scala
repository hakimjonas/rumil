package parsers.json

// ============================================================================
// JSON TYPES - RFC 8259 Compliant (Enums, No Case Classes)
// ============================================================================

/**
 * JSON value as per RFC 8259.
 *
 * The six structural types defined by JSON:
 * - null
 * - boolean (true/false)
 * - number (integer or floating-point)
 * - string (Unicode text)
 * - array (ordered sequence of values)
 * - object (unordered collection of key-value pairs)
 */
enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

/**
 * JSON parsing error types.
 */
enum JsonError {
  case InvalidLiteral(expected: String, found: String, line: Int, column: Int)
  case InvalidNumber(reason: String, line: Int, column: Int)
  case InvalidString(reason: String, line: Int, column: Int)
  case InvalidEscape(char: Char, line: Int, column: Int)
  case InvalidUnicode(sequence: String, line: Int, column: Int)
  case UnexpectedEndOfInput(expected: String, line: Int, column: Int)
  case TrailingComma(context: String, line: Int, column: Int)
  case DuplicateKey(key: String, line: Int, column: Int)
}

/**
 * JSON formatting options for pretty-printing.
 *
 * @param indent Number of spaces per indentation level
 * @param newlines Whether to use newlines between elements
 * @param sortKeys Whether to sort object keys alphabetically
 */
type JsonFormatConfig = (
  indent: Int,
  newlines: Boolean,
  sortKeys: Boolean
)

/**
 * Default JSON formatting configuration (compact).
 */
val compactFormat: JsonFormatConfig = (
  indent = 0,
  newlines = false,
  sortKeys = false
)

/**
 * Pretty-print JSON formatting configuration.
 */
val prettyFormat: JsonFormatConfig = (
  indent = 2,
  newlines = true,
  sortKeys = false
)
