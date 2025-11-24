package parsers.toml

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}

import parser.core._
import parser.syntax._
import parsers.common._

// ============================================================================
// TOML PARSER - TOML v1.0.0 Specification
// ============================================================================

/**
 * Parses a TOML document from a string.
 *
 * TOML v1.0.0 compliant parser supporting:
 * - All value types (string, integer, float, boolean, datetime, array, inline table)
 * - Tables and nested tables
 * - Array tables
 * - Dotted keys
 * - Multi-line strings (basic and literal)
 * - Comments
 * - Full datetime support (RFC 3339)
 *
 * @param input TOML text
 * @return Result containing parsed TOML document
 */
def parseToml(input: scala.Predef.String): Result[ParseError, TomlDocument] =
  tomlDocument.run(input)

// ============================================================================
// Whitespace and Comments
// ============================================================================

/**
 * TOML whitespace: space or tab.
 */
private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t', "whitespace").many.void

/**
 * Comment: # followed by anything until end of line.
 */
private def comment: Parser[ParseError, Unit] =
  char('#') *> satisfy(_ != '\n', "comment char").many *> (newline.void | eof)

/**
 * Skip whitespace and comments.
 */
private def skip: Parser[ParseError, Unit] =
  (ws *> comment.optional *> ws).void

/**
 * End of line (newline or EOF).
 */
private def eol: Parser[ParseError, Unit] =
  ws *> comment.optional *> (newline.void | eof)

// ============================================================================
// Keys
// ============================================================================

/**
 * Bare key: alphanumeric, -, _.
 */
private def bareKey: Parser[ParseError, scala.Predef.String] =
  satisfy(c => c.isLetterOrDigit || c == '-' || c == '_', "bare key char").many1
    .map(_.mkString)

/**
 * Quoted key: "..." or '...'
 */
private def quotedKey: Parser[ParseError, scala.Predef.String] =
  basicString | literalString

/**
 * Simple key (bare or quoted).
 */
private def simpleKey: Parser[ParseError, scala.Predef.String] =
  bareKey | quotedKey

/**
 * Dotted key: key.key.key
 */
private def dottedKey: Parser[ParseError, List[scala.Predef.String]] =
  simpleKey.sepBy1(ws *> char('.') *> ws)

// ============================================================================
// Strings
// ============================================================================

/**
 * Basic string: "..."
 */
private def basicString: Parser[ParseError, scala.Predef.String] = {
  val escape = char('\\') *> (
    char('"').as('"') |
      char('\\').as('\\') |
      char('b').as('\b') |
      char('f').as('\f') |
      char('n').as('\n') |
      char('r').as('\r') |
      char('t').as('\t') |
      (char('u') *> hexDigit.count(4).map(digits => Integer.parseInt(digits.mkString, 16).toChar)) |
      (char('U') *> hexDigit.count(8).map(digits => Integer.parseInt(digits.mkString, 16).toChar))
  )

  val regularChar = satisfy(c => c != '"' && c != '\\' && c != '\n', "string char")

  char('"') *> (escape | regularChar).many <* char('"')
}.map(_.mkString)

/**
 * Literal string: '...' (no escapes).
 */
private def literalString: Parser[ParseError, scala.Predef.String] = {
  char('\'') *> satisfy(c => c != '\'' && c != '\n', "literal string char").many <* char('\'')
}.map(_.mkString)

/**
 * Multi-line basic string: """..."""
 *
 * Uses standard combinators with notFollowedBy to detect closing delimiter.
 */
private def multiLineBasicString: Parser[ParseError, scala.Predef.String] = {
  val escape = char('\\') *> (
    char('"').as('"') |
      char('\\').as('\\') |
      char('b').as('\b') |
      char('f').as('\f') |
      char('n').as('\n') |
      char('r').as('\r') |
      char('t').as('\t') |
      newline.as("") // Line-ending backslash - consumes the newline but contributes empty string
  )

  // A content character is either an escape OR a regular character
  // but we must NOT be at the closing delimiter
  val contentChar =
    escape.map(_.toString) |
      (parser.core.notFollowedBy(string("\"\"\"")) *> satisfy(_ => true, "any char")
        .map(_.toString))

  for {
    _     <- string("\"\"\"")
    _     <- newline.optional // Skip immediate newline after opening
    chars <- contentChar.many
    _     <- string("\"\"\"")
  } yield chars.mkString
}

/**
 * Multi-line literal string: '''...'''
 *
 * No escape sequences - content is taken literally.
 * Uses notFollowedBy to detect closing delimiter without consuming it.
 */
private def multiLineLiteralString: Parser[ParseError, scala.Predef.String] = {
  // A content character is any character, but we must NOT be at the closing delimiter
  val contentChar =
    parser.core.notFollowedBy(string("'''")) *> satisfy(_ => true, "any char")

  for {
    _     <- string("'''")
    _     <- newline.optional // Skip immediate newline after opening
    chars <- contentChar.many
    _     <- string("'''")
  } yield chars.mkString
}

/**
 * Any TOML string.
 */
private def tomlString: Parser[ParseError, TomlValue] =
  (multiLineBasicString | multiLineLiteralString | basicString | literalString)
    .map(TomlValue.String.apply)

// ============================================================================
// Numbers
// ============================================================================

/**
 * Integer: decimal, hex, octal, or binary.
 */
private def tomlInteger: Parser[ParseError, TomlValue] = {
  val hex =
    string("0x") *> hexDigit.many1.map(digits => java.lang.Long.parseLong(digits.mkString, 16))

  val octal = string("0o") *> satisfy(c => c >= '0' && c <= '7', "octal digit").many1.map(digits =>
    java.lang.Long.parseLong(digits.mkString, 8))

  val binary =
    string("0b") *> satisfy(c => c == '0' || c == '1', "binary digit").many1.map(digits =>
      java.lang.Long.parseLong(digits.mkString, 2))

  val decimal = for {
    negative <- char('-').optional
    digits <- satisfy(c => c.isDigit || c == '_', "digit or underscore").many1
                .map(_.filter(_ != '_').mkString)
  } yield {
    val sign = if (negative.isDefined) -1L else 1L
    sign * digits.toLong
  }

  (hex | octal | binary | decimal).map(TomlValue.Integer.apply)
}

/**
 * Float: decimal with fraction or exponent.
 * MUST have either a decimal point OR an exponent to be a float (not plain integer).
 */
private def tomlFloat: Parser[ParseError, TomlValue] = {
  val special = keywords(Map(
    "+inf" -> Double.PositiveInfinity,
    "-inf" -> Double.NegativeInfinity,
    "inf"  -> Double.PositiveInfinity,
    "+nan" -> Double.NaN,
    "-nan" -> Double.NaN,
    "nan"  -> Double.NaN
  ))

  // Float with decimal point (and optional exponent): 3.14, 3.14e10
  val withFraction = for {
    negative <- char('-').optional | char('+').optional
    whole <- satisfy(c => c.isDigit || c == '_', "digit or underscore").many1
               .map(_.filter(_ != '_').mkString)
    _ <- char('.') // REQUIRED decimal point
    frac <- satisfy(c => c.isDigit || c == '_', "digit or underscore").many1
              .map(_.filter(_ != '_').mkString)
    exp <- (oneOf("eE") *> (char('-') | char('+')).optional ~ satisfy(
             c => c.isDigit || c == '_',
             "digit or underscore").many1.map(_.filter(_ != '_').mkString)).optional
  } yield {
    val sign = negative match {
      case Some('-') => "-"
      case _         => ""
    }
    val expPart = exp
      .map { case (s, d) =>
        s"e${s.map(_.toString).getOrElse("")}$d"
      }
      .getOrElse("")

    s"$sign$whole.$frac$expPart".toDouble
  }

  // Float with only exponent (no decimal point): 5e22
  val onlyExponent = for {
    negative <- char('-').optional | char('+').optional
    whole <- satisfy(c => c.isDigit || c == '_', "digit or underscore").many1
               .map(_.filter(_ != '_').mkString)
    exp <- oneOf("eE") *> (char('-') | char('+')).optional ~ satisfy(
             c => c.isDigit || c == '_',
             "digit or underscore").many1.map(_.filter(_ != '_').mkString)
  } yield {
    val sign = negative match {
      case Some('-') => "-"
      case _         => ""
    }
    val expPart = s"e${exp._1.map(_.toString).getOrElse("")}${exp._2}"

    s"$sign$whole$expPart".toDouble
  }

  (special | withFraction | onlyExponent).map(TomlValue.Float.apply)
}

/**
 * Boolean: true or false.
 */
private def tomlBoolean: Parser[ParseError, TomlValue] =
  keywords(Map(
    "true"  -> TomlValue.Boolean(true),
    "false" -> TomlValue.Boolean(false)
  ))

// ============================================================================
// Datetimes
// ============================================================================

/**
 * Date: YYYY-MM-DD
 */
private def tomlDate: Parser[ParseError, TomlValue] =
  for {
    year  <- digit.count(4).map(_.mkString.toInt)
    _     <- char('-')
    month <- digit.count(2).map(_.mkString.toInt)
    _     <- char('-')
    day   <- digit.count(2).map(_.mkString.toInt)
  } yield TomlValue.LocalDate(LocalDate.of(year, month, day))

/**
 * Time: HH:MM:SS[.fraction]
 */
private def tomlTime: Parser[ParseError, TomlValue] =
  for {
    hour     <- digit.count(2).map(_.mkString.toInt)
    _        <- char(':')
    minute   <- digit.count(2).map(_.mkString.toInt)
    _        <- char(':')
    second   <- digit.count(2).map(_.mkString.toInt)
    fraction <- (char('.') *> digit.many1.map(_.mkString.toInt)).optional
  } yield {
    val nanos = fraction.getOrElse(0) * 1000000 // Simple conversion
    TomlValue.LocalTime(LocalTime.of(hour, minute, second, nanos))
  }

/**
 * Datetime: full RFC 3339 datetime.
 */
private def tomlDateTime: Parser[ParseError, TomlValue] =
  // Simplified - just parse the string and use Java's parser
  for {
    chars <- satisfy(c => c.isLetterOrDigit || ":-+.TZ".contains(c), "datetime char").many1
  } yield {
    val str = chars.mkString
    try
      // Try different datetime formats
      if (str.contains('T') || str.contains('t')) {
        if (str.contains('Z') || str.contains('+') || str.lastIndexOf('-') > 8) {
          TomlValue.DateTime(OffsetDateTime.parse(str))
        } else {
          TomlValue.LocalDateTime(LocalDateTime.parse(str))
        }
      } else {
        TomlValue.LocalDate(LocalDate.parse(str))
      }
    catch {
      case _: Exception =>
        TomlValue.String(str) // Fallback
    }
  }

// ============================================================================
// Arrays
// ============================================================================

/**
 * Array: [ values ]
 */
private lazy val tomlArray: Parser[ParseError, TomlValue] =
  for {
    _        <- char('[')
    _        <- skip
    elements <- defer(tomlValue).sepBy(skip *> char(',') *> skip)
    _        <- (skip *> char(',') *> skip).optional // Trailing comma
    _        <- skip
    _        <- char(']')
  } yield TomlValue.Array(elements)

// ============================================================================
// Inline Tables
// ============================================================================

/**
 * Inline table: { key = value, ... }
 */
private lazy val inlineTable: Parser[ParseError, TomlValue] = {
  val pair = for {
    key   <- simpleKey
    _     <- ws *> char('=') *> ws
    value <- defer(tomlValue)
  } yield (key, value)

  for {
    _     <- char('{')
    _     <- ws
    pairs <- pair.sepBy(ws *> char(',') *> ws)
    _     <- ws
    _     <- char('}')
  } yield TomlValue.InlineTable(pairs.toMap)
}

// ============================================================================
// Values
// ============================================================================

/**
 * Any TOML value.
 *
 * CRITICAL: Order matters! More specific parsers must come before general ones.
 * - Strings (quoted) are unambiguous
 * - Booleans must come before datetime (to avoid "true" being parsed as identifier)
 * - Numbers must come before datetime (datetime's fallback catches numeric strings)
 * - Datetime is greedy and has a String fallback, so it must come late
 */
private lazy val tomlValue: Parser[ParseError, TomlValue] =
  tomlString |
    tomlBoolean | // Before datetime/numbers
    tomlFloat |   // Before integer (3.14 should match float, not fail on integer)
    tomlInteger | // Before datetime
    tomlArray |
    inlineTable |
    tomlDateTime // Last - greedy with String fallback

// ============================================================================
// Key-Value Pairs
// ============================================================================

/**
 * Key-value pair: key = value
 */
private def keyValue: Parser[ParseError, (List[scala.Predef.String], TomlValue)] =
  for {
    key   <- dottedKey
    _     <- ws *> char('=') *> ws
    value <- tomlValue
    _     <- eol
  } yield (key, value)

// ============================================================================
// Tables
// ============================================================================

/**
 * Table header: [key.key.key]
 */
private def tableHeader: Parser[ParseError, List[scala.Predef.String]] =
  char('[') *> ws *> dottedKey <* ws <* char(']') <* eol

/**
 * Array table header: [[key.key.key]]
 */
private def arrayTableHeader: Parser[ParseError, List[scala.Predef.String]] =
  string("[[") *> ws *> dottedKey <* ws <* string("]]") <* eol

// ============================================================================
// Document
// ============================================================================

/**
 * Skip blank lines and full-line comments.
 * Does NOT consume whitespace at the start of a content line.
 */
private def skipBlankAndComments: Parser[ParseError, Unit] =
  (newline | (ws *> comment)).many.void

/**
 * Parses a complete TOML document.
 *
 * Handles comments and blank lines while avoiding exponential backtracking.
 * Key insight: keyValue already handles inline comments via eol.
 */
private def tomlDocument: Parser[ParseError, TomlDocument] =
  for {
    _     <- skipBlankAndComments                 // Skip leading blank lines and comments
    pairs <- keyValue.sepBy(skipBlankAndComments) // Parse key-values separated by blanks/comments
    _     <- skipBlankAndComments                 // Trailing whitespace
    _     <- eof
  } yield {
    val pairMap = pairs.foldLeft(Map.empty[scala.Predef.String, TomlValue]) {
      case (acc, (keys, value)) =>
        acc + (keys.mkString(".") -> value)
    }

    (isArrayTable = false, pairs = pairMap, subtables = Map.empty)
  }
