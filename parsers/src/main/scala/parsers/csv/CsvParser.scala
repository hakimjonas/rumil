package parsers.csv

import parser.core._
import parser.syntax._
import parsers.common._

// ============================================================================
// CSV PARSER - RFC 4180 Compliant
// ============================================================================

/**
 * Parses a CSV document with the given configuration.
 *
 * @param config CSV parsing configuration
 * @return Parser that produces a CSV document
 */
def csvParser(config: CsvConfig = defaultCsvConfig): Parser[ParseError, CsvDocument] =
  csvDocument(config)

/**
 * Parses CSV from a string.
 *
 * RFC 4180 compliant parser supporting:
 * - Quoted fields with embedded delimiters, quotes, and newlines
 * - Escaped quotes (doubled quotes: "")
 * - Configurable delimiters (CSV, TSV, etc.)
 * - Optional header detection
 * - Empty fields and lines
 *
 * @param input CSV text
 * @param config CSV parsing configuration
 * @return Result containing parsed CSV or errors
 */
def parseCsv(input: String, config: CsvConfig = defaultCsvConfig): Result[ParseError, CsvDocument] =
  csvParser(config).run(input)

/**
 * Parses TSV (Tab-Separated Values) from a string.
 */
def parseTsv(input: String): Result[ParseError, CsvDocument] =
  parseCsv(input, defaultTsvConfig)

// ============================================================================
// Internal Parsers
// ============================================================================

/**
 * Parses a complete CSV document.
 */
private def csvDocument(config: CsvConfig): Parser[ParseError, CsvDocument] =
  for {
    records <- csvRecord(config).separatedBy(newline)
    _       <- eof
  } yield
    if (config.skipEmptyLines) {
      records.filter(row => row.nonEmpty && !row.forall(_.isEmpty))
    } else {
      records
    }

/**
 * Parses a single CSV record (row).
 */
private def csvRecord(config: CsvConfig): Parser[ParseError, List[String]] =
  csvField(config).separatedBy(char(config.delimiter))

/**
 * Parses a single CSV field.
 *
 * A field can be either:
 * - Quoted: "value with, delimiter or \"quotes\""
 * - Unquoted: simple value
 */
private def csvField(config: CsvConfig): Parser[ParseError, String] = {
  val quoted   = quotedField(config)
  val unquoted = unquotedField(config)

  (quoted | unquoted).map { field =>
    if (config.trimWhitespace) field.trim else field
  }
}

/**
 * Parses a quoted field.
 *
 * Quoted fields can contain:
 * - The delimiter
 * - Newlines
 * - Quotes (escaped by doubling: "")
 */
private def quotedField(config: CsvConfig): Parser[ParseError, String] = {
  val escapedQuote = string(s"${config.escape}${config.quote}").as(config.quote)
  val regularChar  = satisfy(c => c != config.quote, "field char")

  for {
    _     <- char(config.quote)
    chars <- (escapedQuote | regularChar).many
    _     <- char(config.quote)
  } yield chars.mkString
}

/**
 * Parses an unquoted field.
 *
 * Unquoted fields cannot contain:
 * - The delimiter
 * - Newlines
 * - The quote character (at the start)
 */
private def unquotedField(config: CsvConfig): Parser[ParseError, String] =
  satisfy(
    c => c != config.delimiter && c != '\n' && c != '\r' && c != config.quote,
    "unquoted field char"
  ).many.map(_.mkString)

/**
 * Parses CSV and validates that all rows have the same number of columns.
 *
 * @param config CSV parsing configuration
 * @return Parser that produces validated CSV with consistent columns
 */
def parseCsvStrict(
  input: String,
  config: CsvConfig = defaultCsvConfig): Result[ParseError, CsvResult] =
  parseCsv(input, config) match {
    case Result.Success(records, consumed) =>
      if (records.isEmpty) {
        Result.Success(
          (records = records, rowCount = 0, columnCount = 0),
          consumed
        )
      } else {
        val expectedColumns = records.head.length
        val invalidRow = records.zipWithIndex.find { case (row, _) =>
          row.length != expectedColumns
        }

        invalidRow match {
          case Some((row, idx)) =>
            Result.Failure(
              List(ParseError.Custom(
                s"Inconsistent columns: expected $expectedColumns, found ${row.length} at row ${idx + 1}",
                (line = idx + 1, column = 1, offset = 0)
              )),
              (line = idx + 1, column = 1, offset = 0)
            )
          case None =>
            val maxCols = records.map(_.length).maxOption.getOrElse(0)
            Result.Success(
              (records = records, rowCount = records.length, columnCount = maxCols),
              consumed
            )
        }
      }
    case Result.Partial(records, errors, consumed) =>
      // Partial success - validate structure but preserve errors
      if (records.isEmpty) {
        Result.Partial(
          (records = records, rowCount = 0, columnCount = 0),
          errors,
          consumed
        )
      } else {
        val maxCols = records.map(_.length).maxOption.getOrElse(0)
        Result.Partial(
          (records = records, rowCount = records.length, columnCount = maxCols),
          errors,
          consumed
        )
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }

/**
 * Parses CSV with header detection.
 *
 * Returns a tuple of (headers, data rows).
 */
def parseCsvWithHeaders(
  input: String,
  config: CsvConfig = defaultCsvConfig): Result[ParseError, (List[String], CsvDocument)] =
  parseCsv(input, config) match {
    case Result.Success(records, consumed) =>
      records match {
        case Nil             => Result.Success((List.empty, List.empty), consumed)
        case headers :: data => Result.Success((headers, data), consumed)
      }
    case Result.Partial(records, errors, consumed) =>
      records match {
        case Nil             => Result.Partial((List.empty, List.empty), errors, consumed)
        case headers :: data => Result.Partial((headers, data), errors, consumed)
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }

/**
 * Parses CSV into a list of maps (each row as a map of header -> value).
 *
 * Useful for working with CSV as records with named fields.
 */
def parseCsvAsMaps(
  input: String,
  config: CsvConfig = defaultCsvConfig): Result[ParseError, List[Map[String, String]]] =
  parseCsvWithHeaders(input, config) match {
    case Result.Success((headers, rows), consumed) =>
      val maps = rows.map { row =>
        headers.zip(row).toMap
      }
      Result.Success(maps, consumed)
    case Result.Partial((headers, rows), errors, consumed) =>
      val maps = rows.map { row =>
        headers.zip(row).toMap
      }
      Result.Partial(maps, errors, consumed)
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }
