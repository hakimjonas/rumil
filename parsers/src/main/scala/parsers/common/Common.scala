package parsers.common

import parser.core._
import parser.syntax._

// ============================================================================
// COMMON UTILITIES - Shared across all parsers
// ============================================================================

/**
 * Parses a hexadecimal digit (0-9, a-f, A-F).
 */
def hexDigit: Parser[ParseError, Char] =
  satisfy(
    c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'),
    "hex digit")

/**
 * Parses a Unicode escape sequence (\uXXXX).
 *
 * Example: "\u0041" parses to 'A'
 */
def unicodeEscape: Parser[ParseError, Char] =
  for {
    _  <- char('\\')
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
 * Parses an optional sign (+ or -), returning 1 or -1.
 */
def sign: Parser[ParseError, Int] =
  (char('+').as(1) | char('-').as(-1)).optional.map(_.getOrElse(1))

/**
 * Parses a signed integer.
 */
def signedInt: Parser[ParseError, Int] =
  for {
    s      <- sign
    digits <- digit.many1
  } yield s * digits.mkString.toInt

/**
 * Parses an unsigned integer.
 */
def unsignedInt: Parser[ParseError, Int] =
  digit.many1.map(_.mkString.toInt)

/**
 * Parses a double/float with optional sign, decimal, and exponent.
 */
def floatingPoint: Parser[ParseError, Double] =
  for {
    s     <- sign
    whole <- digit.many1
    frac  <- (char('.') *> digit.many1).optional
    exp   <- (oneOf("eE") *> signedInt).optional
  } yield {
    val base = frac match {
      case Some(fracDigits) => s"${whole.mkString}.${fracDigits.mkString}"
      case None             => whole.mkString
    }
    val value = base.toDouble
    val withExp = exp match {
      case Some(e) => value * math.pow(10, e)
      case None    => value
    }
    withExp * s
  }

/**
 * Parses horizontal whitespace (space and tab only).
 */
def hspace: Parser[ParseError, Char] =
  satisfy(c => c == ' ' || c == '\t', "horizontal whitespace")

/**
 * Parses zero or more horizontal whitespace characters.
 */
def hspaces: Parser[ParseError, List[Char]] =
  hspace.many

/**
 * Parses one or more horizontal whitespace characters.
 */
def hspaces1: Parser[ParseError, List[Char]] =
  hspace.many1

/**
 * Parses a newline (LF, CR, or CRLF).
 */
def newline: Parser[ParseError, String] =
  stringIn("\r\n", "\n", "\r")

/**
 * Parses end of line or end of file.
 */
def eol: Parser[ParseError, Unit] =
  (newline.void | eof).named("end of line")

/**
 * Parses a string between quotes, handling escape sequences.
 *
 * @param quote The quote character (\" or \')
 * @param escapes Map of escape sequences to their actual characters
 */
def quotedString(quote: Char, escapes: Map[Char, Char]): Parser[ParseError, String] = {
  val escapeChar = char('\\') *> satisfy(escapes.contains, "escape char").map(escapes)
  val normalChar = satisfy(c => c != quote && c != '\\', "string char")

  char(quote) *> (escapeChar | normalChar).many <* char(quote)
}.map(_.mkString)

/**
 * Common escape sequences for strings.
 */
val commonEscapes: Map[Char, Char] = Map(
  'n'  -> '\n',
  'r'  -> '\r',
  't'  -> '\t',
  '\\' -> '\\',
  '"'  -> '"',
  '\'' -> '\'',
  'b'  -> '\b',
  'f'  -> '\f'
)

/**
 * Parses a double-quoted string with common escape sequences.
 */
def doubleQuotedString: Parser[ParseError, String] =
  quotedString('"', commonEscapes + ('"' -> '"'))

/**
 * Parses a single-quoted string with common escape sequences.
 */
def singleQuotedString: Parser[ParseError, String] =
  quotedString('\'', commonEscapes + ('\'' -> '\''))

/**
 * Parses an identifier (letter or underscore, followed by alphanumeric or underscore).
 */
def identifier: Parser[ParseError, String] =
  for {
    first <- letter | char('_')
    rest  <- (alphaNum | char('_')).many
  } yield s"$first${rest.mkString}"

/**
 * Skips a line comment starting with the given prefix.
 */
def lineComment(prefix: String): Parser[ParseError, Unit] =
  string(prefix) *> satisfy(_ != '\n', "any char").many *> (newline.void | eof)

/**
 * Skips a block comment between start and end markers.
 */
def blockComment(start: String, end: String): Parser[ParseError, Unit] =
  string(start) *> satisfy(_ => true, "any char").many.flatMap { _ =>
    string(end).void
  }
