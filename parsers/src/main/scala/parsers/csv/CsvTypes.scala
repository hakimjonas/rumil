package parsers.csv

// ============================================================================
// CSV TYPES - Following Rumil manifesto (enums, named tuples, no case classes)
// ============================================================================

/**
 * Configuration for CSV parsing.
 *
 * @param delimiter Field delimiter character (default: ',')
 * @param quote Quote character for escaping (default: '"')
 * @param escape Escape character (default: '"' for doubled quotes)
 * @param trimWhitespace Whether to trim whitespace from fields
 * @param skipEmptyLines Whether to skip empty lines
 */
type CsvConfig = (
  delimiter: Char,
  quote: Char,
  escape: Char,
  trimWhitespace: Boolean,
  skipEmptyLines: Boolean
)

/**
 * Default CSV configuration per RFC 4180.
 */
val defaultCsvConfig: CsvConfig = (
  delimiter = ',',
  quote = '"',
  escape = '"',
  trimWhitespace = false,
  skipEmptyLines = false
)

/**
 * Default TSV (Tab-Separated Values) configuration.
 */
val defaultTsvConfig: CsvConfig = (
  delimiter = '\t',
  quote = '"',
  escape = '"',
  trimWhitespace = false,
  skipEmptyLines = false
)

/**
 * A CSV document is a list of records (rows).
 * Each record is a list of fields (strings).
 *
 * Example:
 * {{{
 * List(
 *   List("name", "age", "city"),
 *   List("Alice", "30", "NYC"),
 *   List("Bob", "25", "SF")
 * )
 * }}}
 */
type CsvDocument = List[List[String]]

/**
 * Result of CSV parsing with metadata.
 *
 * @param records The parsed records
 * @param rowCount Number of rows parsed
 * @param columnCount Maximum number of columns across all rows
 */
type CsvResult = (
  records: CsvDocument,
  rowCount: Int,
  columnCount: Int
)

/**
 * CSV parsing error types.
 */
enum CsvError {
  case UnclosedQuote(line: Int, column: Int)
  case InvalidEscape(line: Int, column: Int, found: Char)
  case InconsistentColumns(expected: Int, found: Int, row: Int)
  case ParseError(message: String, line: Int, column: Int)
}
