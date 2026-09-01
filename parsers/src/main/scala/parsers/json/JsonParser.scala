package parsers.json

import net.ghoula.sarati.ast.json.*
import parsers.common.*

import parser.core.*
import parser.syntax.*

/** Parses a JSON value from a string.
  *
  * RFC 8259 compliant parser supporting:
  *   - All six JSON value types (null, boolean, number, string, array, object)
  *   - Full Unicode support including \uXXXX escapes
  *   - Numbers with exponents and negative values
  *   - Arbitrary nesting depth
  *   - Strict whitespace handling per RFC 8259
  *
  * @param input
  *   JSON text
  * @return
  *   Result containing parsed JSON value or errors
  */
def parseJson(input: String): Result[ParseError, JsonValue] =
  (ws *> jsonValue <* ws <* eof).run(input)

/** Exposed for benchmarking - the full JSON parser */
val jsonParser: Parser[ParseError, JsonValue] = ws *> jsonValue <* ws <* eof

/** JSON whitespace: space, tab, newline, carriage return.
  */
private lazy val ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\n' || c == '\r', "whitespace").many.void

/** Parses p surrounded by optional whitespace.
  */
private def lexeme[A](p: Parser[ParseError, A]): Parser[ParseError, A] =
  ws *> p <* ws

/** Parses JSON null.
  */
private lazy val jsonNull: Parser[ParseError, JsonValue] =
  lexeme(string("null")).as(JsonValue.Null).named("null")

/** Parses JSON boolean (true or false).
  */
private lazy val jsonBool: Parser[ParseError, JsonValue] =
  lexeme(
    string("true").as(JsonValue.Bool(true)) |
      string("false").as(JsonValue.Bool(false))
  ).named("boolean")

/** Parses JSON number.
  *
  * Format: [minus] int [frac] [exp]
  *   - int: digit1-9 digits | digit
  *   - frac: . digits
  *   - exp: e [+/-] digits
  */
private lazy val jsonNumber: Parser[ParseError, JsonValue] = {
  val negativeOpt = char('-').optional
  val zeroOrInt =
    char('0').as("0") |
      (satisfy(c => c >= '1' && c <= '9', "1-9") ~ digit.many).map { case (first, rest) =>
        s"$first${rest.mkString}"
      }
  val fracOpt = (char('.') *> digit.many1).optional
  val expOpt = (
    oneOf("eE") *>
      (char('+') | char('-')).optional ~
      digit.many1
  ).optional

  lexeme(
    for {
      negative <- negativeOpt
      intPart <- zeroOrInt
      fracPart <- fracOpt
      expPart <- expOpt
    } yield {
      val sign = if negative.isDefined then "-" else ""
      val frac = fracPart.map(digits => s".${digits.mkString}").getOrElse("")
      val exp = expPart.map { case (s, digits) =>
        val expSign = s.map(_.toString).getOrElse("")
        s"e$expSign${digits.mkString}"
      }
        .getOrElse("")

      val numStr = s"$sign$intPart$frac$exp"
      JsonValue.Number(numStr.toDouble, raw = Some(numStr))
    }
  ).named("number")
}

/** Parses JSON string with full escape sequence support.
  *
  * Supports:
  *   - Basic escapes: \" \\ \/ \b \f \n \r \t
  *   - Unicode escapes: \uXXXX
  */
private lazy val quote: Parser[ParseError, Char] = char('"')

private lazy val jsonString: Parser[ParseError, JsonValue] = {
  val stringCharsMany = stringChar.many
  lexeme(
    for {
      _ <- quote
      chars <- stringCharsMany
      _ <- quote
    } yield JsonValue.Str(chars.mkString)
  ).named("string")
}

/** Parses a single character or escape sequence in a JSON string.
  */
private lazy val stringChar: Parser[ParseError, Char] =
  escapeSequence | satisfy(c => c != '"' && c != '\\' && c >= '\u0020', "string char")

/** Parses JSON escape sequences.
  */
private lazy val backslash: Parser[ParseError, Char] = char('\\')

private lazy val escapeBody: Parser[ParseError, Char] =
  char('"').as('"') |
    char('\\').as('\\') |
    char('/').as('/') |
    char('b').as('\b') |
    char('f').as('\f') |
    char('n').as('\n') |
    char('r').as('\r') |
    char('t').as('\t') |
    unicodeEscapeJson

private lazy val escapeSequence: Parser[ParseError, Char] =
  backslash *> escapeBody

/** Parses \uXXXX Unicode escape sequence.
  */
private lazy val unicodeEscapeJson: Parser[ParseError, Char] = {
  val u = char('u')
  for {
    _ <- u
    d1 <- hexDigit
    d2 <- hexDigit
    d3 <- hexDigit
    d4 <- hexDigit
  } yield {
    val hex = s"$d1$d2$d3$d4"
    Integer.parseInt(hex, 16).toChar
  }
}

/** Parses a raw JSON string (without quotes) for use in object keys.
  */
private lazy val rawString: Parser[ParseError, String] = {
  val stringCharsMany = stringChar.many
  for {
    _ <- quote
    chars <- stringCharsMany
    _ <- quote
  } yield chars.mkString
}

/** Parses JSON array.
  *
  * Format: [ [value *(, value)] ]
  */
private lazy val lbracket: Parser[ParseError, Char] = lexeme(char('['))
private lazy val rbracket: Parser[ParseError, Char] = lexeme(char(']'))
private lazy val lbrace: Parser[ParseError, Char] = lexeme(char('{'))
private lazy val rbrace: Parser[ParseError, Char] = lexeme(char('}'))
private lazy val comma: Parser[ParseError, Char] = lexeme(char(','))
private lazy val colon: Parser[ParseError, Char] = lexeme(char(':'))

private lazy val jsonArray: Parser[ParseError, JsonValue] =
  (for {
    _ <- lbracket
    es <- jsonValue.sepBy(comma)
    _ <- rbracket
  } yield JsonValue.Array(es)).named("array")

/** Parses JSON object.
  *
  * Format: { [member *(, member)] } Member: string : value
  */
private lazy val jsonObject: Parser[ParseError, JsonValue] = {
  val rawKeyLexed = lexeme(rawString)
  // `member` must NOT be a module-level lazy val — it references jsonValue, which cycles back to
  // jsonObject. Keeping it inline inside the for-comprehension's lambda defers jsonValue access
  // until parse time, past the init cycle.
  (for {
    _ <- lbrace
    pairs <- (for {
      key <- rawKeyLexed
      _ <- colon
      value <- jsonValue
    } yield (key, value)).sepBy(comma)
    _ <- rbrace
  } yield JsonValue.Object(pairs.toMap)).named("object")
}

/** Parses any JSON value.
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

/** Parses JSON and returns a specific type.
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

// Re-export Sarati's JSON types and formatter so downstream consumers
// don't need to import from Sarati directly.
export net.ghoula.sarati.ast.json.{formatJson, JsonFormatConfig, compactFormat, prettyFormat}
