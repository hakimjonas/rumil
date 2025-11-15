package parsers.csv

import parser.core.*
import parser.syntax.*
import parsers.common.*

// ============================================================================
// CSV PARSER - RFC 4180 Compliant
// ============================================================================

/**
 * RFC 4180 compliant CSV parser.
 *
 * Supports:
 * - Quoted fields with embedded delimiters, quotes, and newlines
 * - Escaped quotes (doubled quotes: "")
 * - Configurable delimiters (CSV, TSV, etc.)
 * - Optional header detection
 * - Empty fields and lines
 */
object CsvParser {

  /**
   * Parses a CSV document with the given configuration.
   *
   * @param config CSV parsing configuration
   * @return Parser that produces a CSV document
   */
  def parser(config: CsvConfig = defaultCsvConfig): Parser[ParseError, CsvDocument] = {
    csvDocument(config)
  }

  /**
   * Convenience method to parse CSV from a string.
   *
   * @param input CSV text
   * @param config CSV parsing configuration
   * @return Result containing parsed CSV or errors
   */
  def parse(input: String, config: CsvConfig = defaultCsvConfig): Result[ParseError, CsvDocument] = {
    parser(config).run(input)
  }

  /**
   * Parses TSV (Tab-Separated Values) from a string.
   */
  def parseTsv(input: String): Result[ParseError, CsvDocument] = {
    parse(input, defaultTsvConfig)
  }

  // Internal parsers

  /**
   * Parses a complete CSV document.
   */
  private def csvDocument(config: CsvConfig): Parser[ParseError, CsvDocument] = {
    for {
      records <- csvRecord(config).sepBy(newline)
      _ <- eof
    } yield {
      if (config.skipEmptyLines) {
        records.filter(_.nonEmpty)
      } else {
        records
      }
    }
  }

  /**
   * Parses a single CSV record (row).
   */
  private def csvRecord(config: CsvConfig): Parser[ParseError, List[String]] = {
    csvField(config).sepBy(char(config.delimiter))
  }

  /**
   * Parses a single CSV field.
   *
   * A field can be either:
   * - Quoted: "value with, delimiter or \"quotes\""
   * - Unquoted: simple value
   */
  private def csvField(config: CsvConfig): Parser[ParseError, String] = {
    val quoted = quotedField(config)
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
    val regularChar = satisfy(c => c != config.quote, "field char")

    for {
      _ <- char(config.quote)
      chars <- (escapedQuote | regularChar).many
      _ <- char(config.quote)
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
  private def unquotedField(config: CsvConfig): Parser[ParseError, String] = {
    satisfy(
      c => c != config.delimiter && c != '\n' && c != '\r' && c != config.quote,
      "unquoted field char"
    ).many.map(_.mkString)
  }

  /**
   * Parses CSV and validates that all rows have the same number of columns.
   *
   * @param config CSV parsing configuration
   * @return Parser that produces validated CSV with consistent columns
   */
  def parseStrict(input: String, config: CsvConfig = defaultCsvConfig): Result[ParseError, CsvResult] = {
    parse(input, config) match {
      case Result.Success(records, consumed) => {
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
            case Some((row, idx)) => {
              Result.Failure(
                List(ParseError.Custom(
                  s"Inconsistent columns: expected $expectedColumns, found ${row.length} at row ${idx + 1}",
                  (line = idx + 1, column = 1, offset = 0)
                )),
                (line = idx + 1, column = 1, offset = 0)
              )
            }
            case None => {
              val maxCols = records.map(_.length).maxOption.getOrElse(0)
              Result.Success(
                (records = records, rowCount = records.length, columnCount = maxCols),
                consumed
              )
            }
          }
        }
      }
      case Result.Failure(errors, furthest) => {
        Result.Failure(errors, furthest)
      }
    }
  }

  /**
   * Parses CSV with header detection.
   *
   * Returns a tuple of (headers, data rows).
   */
  def parseWithHeaders(input: String, config: CsvConfig = defaultCsvConfig): Result[ParseError, (List[String], CsvDocument)] = {
    parse(input, config) match {
      case Result.Success(records, consumed) => {
        records match {
          case Nil => Result.Success((List.empty, List.empty), consumed)
          case headers :: data => Result.Success((headers, data), consumed)
        }
      }
      case Result.Failure(errors, furthest) => {
        Result.Failure(errors, furthest)
      }
    }
  }

  /**
   * Parses CSV into a list of maps (each row as a map of header -> value).
   *
   * Useful for working with CSV as records with named fields.
   */
  def parseAsMaps(input: String, config: CsvConfig = defaultCsvConfig): Result[ParseError, List[Map[String, String]]] = {
    parseWithHeaders(input, config) match {
      case Result.Success((headers, rows), consumed) => {
        val maps = rows.map { row =>
          headers.zip(row).toMap
        }
        Result.Success(maps, consumed)
      }
      case Result.Failure(errors, furthest) => {
        Result.Failure(errors, furthest)
      }
    }
  }
}
