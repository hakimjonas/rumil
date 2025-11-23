package parsers.toml

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}

import scala.language.strictEquality

// ============================================================================
// TOML TYPES - TOML v1.0.0 (Enums, Named Tuples, No Case Classes)
// ============================================================================

/**
 * TOML value types as per TOML v1.0.0 specification.
 */
enum TomlValue {
  case String(value: scala.Predef.String)
  case Integer(value: Long)
  case Float(value: Double)
  case Boolean(value: scala.Boolean)
  case DateTime(value: OffsetDateTime)
  case LocalDateTime(value: java.time.LocalDateTime)
  case LocalDate(value: java.time.LocalDate)
  case LocalTime(value: java.time.LocalTime)
  case Array(elements: List[TomlValue])
  case InlineTable(pairs: Map[scala.Predef.String, TomlValue])
}

// CanEqual instance for TomlValue
given CanEqual[TomlValue, TomlValue] = CanEqual.derived

/**
 * TOML table - collection of key-value pairs.
 *
 * @param isArrayTable Whether this is an array table (e.g., `[[name]]`)
 * @param pairs Key-value pairs in this table
 * @param subtables Nested tables
 */
type TomlTable = (
  isArrayTable: scala.Boolean,
  pairs: Map[scala.Predef.String, TomlValue],
  subtables: Map[scala.Predef.String, List[TomlTable]]
)

/**
 * TOML document - root table.
 */
type TomlDocument = TomlTable

/**
 * Creates an empty TOML table.
 */
def emptyTable: TomlTable = (
  isArrayTable = false,
  pairs = Map.empty,
  subtables = Map.empty
)

/**
 * Creates an array table.
 */
def arrayTable(
  pairs: Map[scala.Predef.String, TomlValue] = Map.empty,
  subtables: Map[scala.Predef.String, List[TomlTable]] = Map.empty
): TomlTable = (
  isArrayTable = true,
  pairs = pairs,
  subtables = subtables
)

/**
 * Key path for nested tables (e.g., "a.b.c" -> List("a", "b", "c")).
 */
type KeyPath = List[scala.Predef.String]

/**
 * Parses a dotted key into a key path.
 */
def parseKeyPath(key: scala.Predef.String): KeyPath =
  key.split('.').toList.filter(_.nonEmpty)

// ============================================================================
// TOML FORMATTING - Convert TOML values back to string representation
// ============================================================================

/**
 * Formats a TOML value as a string.
 *
 * @param value The TOML value to format
 * @return String representation of the value
 */
def formatTomlValue(value: TomlValue): scala.Predef.String = value match {
  case TomlValue.String(s)  => formatTomlString(s)
  case TomlValue.Integer(n) => n.toString
  case TomlValue.Float(d) =>
    if (d.isNaN) "nan"
    else if (d.isPosInfinity) "inf"
    else if (d.isNegInfinity) "-inf"
    else d.toString
  case TomlValue.Boolean(b)        => if (b) "true" else "false"
  case TomlValue.DateTime(dt)      => dt.toString
  case TomlValue.LocalDateTime(dt) => dt.toString
  case TomlValue.LocalDate(d)      => d.toString
  case TomlValue.LocalTime(t)      => t.toString
  case TomlValue.Array(elements) =>
    elements.map(formatTomlValue).mkString("[", ", ", "]")
  case TomlValue.InlineTable(pairs) =>
    pairs.map { case (k, v) => s"$k = ${formatTomlValue(v)}" }.mkString("{ ", ", ", " }")
}

/**
 * Formats a string value with proper TOML escaping.
 */
private def formatTomlString(s: scala.Predef.String): scala.Predef.String = {
  val escaped = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
  s"\"$escaped\""
}

/**
 * Formats a simple TOML document (key-value pairs only, no nested tables).
 *
 * @param doc The TOML document to format
 * @return String representation of the document
 */
def formatToml(doc: TomlDocument): scala.Predef.String =
  doc.pairs.map { case (k, v) => s"$k = ${formatTomlValue(v)}" }.mkString("\n")
