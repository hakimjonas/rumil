package parsers.json

import parser.core._
import parser.syntax._
import parsers.common._

// ============================================================================
// JSON PARSER - RFC 8259 Compliant
// ============================================================================

/**
 * Parses a JSON value from a string.
 *
 * RFC 8259 compliant parser supporting:
 * - All six JSON value types (null, boolean, number, string, array, object)
 * - Full Unicode support including \uXXXX escapes
 * - Numbers with exponents and negative values
 * - Arbitrary nesting depth
 * - Strict whitespace handling per RFC 8259
 *
 * @param input JSON text
 * @return Result containing parsed JSON value or errors
 */
def parseJson(input: String): Result[ParseError, JsonValue] =
  (ws *> jsonValue <* ws <* eof).run(input)

// ============================================================================
// Whitespace handling (RFC 8259 Section 2)
// ============================================================================

/**
 * JSON whitespace: space, tab, newline, carriage return.
 */
private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\n' || c == '\r', "whitespace").many.void

/**
 * Parses p surrounded by optional whitespace.
 */
private def lexeme[A](p: Parser[ParseError, A]): Parser[ParseError, A] =
  ws *> p <* ws

// ============================================================================
// Literals (RFC 8259 Section 3)
// ============================================================================

/**
 * Parses JSON null.
 */
private def jsonNull: Parser[ParseError, JsonValue] =
  lexeme(string("null")).as(JsonValue.Null).named("null")

/**
 * Parses JSON boolean (true or false).
 */
private def jsonBool: Parser[ParseError, JsonValue] =
  lexeme(
    string("true").as(JsonValue.Bool(true)) |
      string("false").as(JsonValue.Bool(false))
  ).named("boolean")

// ============================================================================
// Numbers (RFC 8259 Section 6)
// ============================================================================

/**
 * Parses JSON number.
 *
 * Format: [minus] int [frac] [exp]
 * - int: digit1-9 digits | digit
 * - frac: . digits
 * - exp: e [+/-] digits
 */
private def jsonNumber: Parser[ParseError, JsonValue] =
  lexeme(
    for {
      negative <- char('-').optional
      intPart <-
        char('0').as("0") |
          (satisfy(c => c >= '1' && c <= '9', "1-9") ~ digit.many)
            .map { case (first, rest) => s"$first${rest.mkString}" }
      fracPart <- (char('.') *> digit.manyNonEmpty).optional
      expPart <- (
                   oneOf("eE") *>
                     (char('+') | char('-')).optional ~
                     digit.manyNonEmpty
                 ).optional
    } yield {
      val sign = if (negative.isDefined) "-" else ""
      val frac = fracPart.map(digits => s".${digits.mkString}").getOrElse("")
      val exp = expPart
        .map { case (s, digits) =>
          val expSign = s.map(_.toString).getOrElse("")
          s"e$expSign${digits.mkString}"
        }
        .getOrElse("")

      val numStr = s"$sign$intPart$frac$exp"
      JsonValue.Number(numStr.toDouble)
    }
  ).named("number")

// ============================================================================
// Strings (RFC 8259 Section 7)
// ============================================================================

/**
 * Parses JSON string with full escape sequence support.
 *
 * Supports:
 * - Basic escapes: \" \\ \/ \b \f \n \r \t
 * - Unicode escapes: \uXXXX
 */
private def jsonString: Parser[ParseError, JsonValue] =
  lexeme(
    for {
      _     <- char('"')
      chars <- stringChar.many
      _     <- char('"')
    } yield JsonValue.Str(chars.mkString)
  ).named("string")

/**
 * Parses a single character or escape sequence in a JSON string.
 */
private def stringChar: Parser[ParseError, Char] =
  escapeSequence | satisfy(c => c != '"' && c != '\\' && c >= '\u0020', "string char")

/**
 * Parses JSON escape sequences.
 */
private def escapeSequence: Parser[ParseError, Char] =
  char('\\') *> (
    char('"').as('"') |
      char('\\').as('\\') |
      char('/').as('/') |
      char('b').as('\b') |
      char('f').as('\f') |
      char('n').as('\n') |
      char('r').as('\r') |
      char('t').as('\t') |
      unicodeEscapeJson
  )

/**
 * Parses \uXXXX Unicode escape sequence.
 */
private def unicodeEscapeJson: Parser[ParseError, Char] =
  for {
    _  <- char('u')
    d1 <- hexDigit
    d2 <- hexDigit
    d3 <- hexDigit
    d4 <- hexDigit
  } yield {
    val hex = s"$d1$d2$d3$d4"
    Integer.parseInt(hex, 16).toChar
  }

/**
 * Parses a raw JSON string (without quotes) for use in object keys.
 */
private def rawString: Parser[ParseError, String] =
  for {
    _     <- char('"')
    chars <- stringChar.many
    _     <- char('"')
  } yield chars.mkString

// ============================================================================
// Arrays (RFC 8259 Section 5)
// ============================================================================

/**
 * Parses JSON array.
 *
 * Format: [ [value *(, value)] ]
 */
private lazy val jsonArray: Parser[ParseError, JsonValue] =
  (for {
    _        <- lexeme(char('['))
    elements <- jsonValue.separatedBy(lexeme(char(',')))
    _        <- lexeme(char(']'))
  } yield JsonValue.Array(elements)).named("array")

/**
 * Parses JSON object.
 *
 * Format: { [member *(, member)] }
 * Member: string : value
 */
private lazy val jsonObject: Parser[ParseError, JsonValue] = {
  val member = for {
    key   <- lexeme(rawString)
    _     <- lexeme(char(':'))
    value <- jsonValue
  } yield (key, value)

  (for {
    _     <- lexeme(char('{'))
    pairs <- member.separatedBy(lexeme(char(',')))
    _     <- lexeme(char('}'))
  } yield JsonValue.Object(pairs.toMap)).named("object")
}

// ============================================================================
// Main parser
// ============================================================================

/**
 * Parses any JSON value.
 *
 * A JSON text is a serialized value (object, array, number, string, true, false, null).
 */
private lazy val jsonValue: Parser[ParseError, JsonValue] =
  (jsonNull |
    jsonBool |
    jsonNumber |
    jsonString |
    jsonArray |
    jsonObject).named("value")

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Parses JSON and returns a specific type.
 */
def parseJsonAs[A](input: String)(f: JsonValue => Option[A]): Result[ParseError, A] =
  parseJson(input) match {
    case Result.Success(value, consumed) =>
      f(value) match {
        case Some(a) => Result.Success(a, consumed)
        case None =>
          Result.Failure(
            List(ParseError.Custom("Type mismatch", (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
    case Result.Partial(value, errors, consumed) =>
      f(value) match {
        case Some(a) => Result.Partial(a, errors, consumed)
        case None =>
          Result.Failure(
            errors ++ List(ParseError.Custom("Type mismatch", (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
    case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
  }

/**
 * Formats a JSON value as a string.
 */
def formatJson(value: JsonValue, config: JsonFormatConfig = compactFormat): String =
  formatJsonValue(value, 0, config)

private def formatJsonValue(value: JsonValue, depth: Int, config: JsonFormatConfig): String =
  value match {
    case JsonValue.Null      => "null"
    case JsonValue.Bool(b)   => b.toString
    case JsonValue.Number(n) =>
      // Format numbers nicely (remove .0 for whole numbers)
      if (n.isWhole) n.toLong.toString
      else n.toString
    case JsonValue.Str(s) =>
      val escaped = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
      s"\"$escaped\""
    case JsonValue.Array(elements) =>
      if (elements.isEmpty) {
        "[]"
      } else if (config.newlines) {
        val indent      = " " * (config.indent * (depth + 1))
        val closeIndent = " " * (config.indent * depth)
        val items =
          elements.map(e => s"$indent${formatJsonValue(e, depth + 1, config)}").mkString(",\n")
        s"[\n$items\n$closeIndent]"
      } else {
        val items = elements.map(e => formatJsonValue(e, depth + 1, config)).mkString(",")
        s"[$items]"
      }
    case JsonValue.Object(fields) =>
      if (fields.isEmpty) {
        "{}"
      } else {
        val pairs = if (config.sortKeys) {
          fields.toList.sortBy(_._1)
        } else {
          fields.toList
        }

        if (config.newlines) {
          val indent      = " " * (config.indent * (depth + 1))
          val closeIndent = " " * (config.indent * depth)
          val items = pairs
            .map { case (k, v) =>
              s"$indent\"$k\":${formatJsonValue(v, depth + 1, config)}"
            }
            .mkString(",\n")
          s"{\n$items\n$closeIndent}"
        } else {
          val items = pairs
            .map { case (k, v) =>
              s"\"$k\":${formatJsonValue(v, depth + 1, config)}"
            }
            .mkString(",")
          s"{$items}"
        }
      }
  }
