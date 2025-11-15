package parsers.yaml

import parser.core._
import parser.syntax._
import parsers.common._

// ============================================================================
// YAML PARSER - YAML 1.2 Subset
// ============================================================================

/**
 * Parses a YAML document from a string.
 *
 * YAML 1.2 parser (simplified subset) supporting:
 * - Scalars (strings, numbers, booleans, null)
 * - Sequences (block and flow style)
 * - Mappings (block and flow style)
 * - Comments
 * - Basic indentation handling
 *
 * Note: This is a simplified parser focusing on common YAML features.
 * Full YAML 1.2 spec includes anchors, aliases, tags, and more.
 *
 * @param input YAML text
 * @return Result containing parsed YAML document
 */
def parseYaml(input: scala.Predef.String): Result[ParseError, YamlDocument] =
  yamlDocument.run(input)

// ============================================================================
// Whitespace and Comments
// ============================================================================

private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many.void

private def yamlComment: Parser[ParseError, Unit] =
  char('#') *> satisfy(_ != '\n', "comment char").many *> (newline.void | eof)

private def blankLine: Parser[ParseError, Unit] =
  hspaces *> (yamlComment | newline.void)

private def indent(n: Int): Parser[ParseError, Unit] =
  hspace.count(n).void

// ============================================================================
// Scalars
// ============================================================================

private def yamlNull: Parser[ParseError, YamlValue] =
  (string("null") | string("~")).as(YamlValue.Null)

private def yamlBoolean: Parser[ParseError, YamlValue] =
  (string("true") | string("yes") | string("on")).as(YamlValue.Boolean(true)) |
    (string("false") | string("no") | string("off")).as(YamlValue.Boolean(false))

private def yamlNumber: Parser[ParseError, YamlValue] = {
  // Try to parse as integer first (no decimal point, no exponent)
  val integer = signedInt.map(n => YamlValue.Integer(n.toLong))
  val float   = floatingPoint.map(YamlValue.Float.apply)

  // Parser.Custom to peek ahead and decide which parser to use
  Parser.Custom { state =>
    val remaining = state.input.substring(state.offset)
    // Check if the number contains '.' or 'e'/'E' (float indicators)
    val hasDecimalOrExp = remaining.takeWhile(c =>
      c.isDigit || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E')

    if (hasDecimalOrExp.contains('.') || hasDecimalOrExp.toLowerCase.contains('e')) {
      parser.runtime.interpret(float, state)
    } else {
      parser.runtime.interpret(integer, state)
    }
  }
}

private def plainString: Parser[ParseError, YamlValue] =
  satisfy(
    c => c != ':' && c != '#' && c != '\n' && c != '[' && c != ']' && c != '{' && c != '}',
    "plain char").many1
    .map(chars => YamlValue.String(chars.mkString.trim))

private def quotedString: Parser[ParseError, YamlValue] =
  (doubleQuotedString | singleQuotedString).map(YamlValue.String.apply)

private def yamlScalar: Parser[ParseError, YamlValue] =
  yamlNull | yamlBoolean | yamlNumber | quotedString | plainString

// ============================================================================
// Flow Style (JSON-like)
// ============================================================================

private def flowSequence: Parser[ParseError, YamlValue] =
  Parser.Custom { state =>
    val seqParser = for {
      _        <- char('[') *> ws
      elements <- yamlValue.sepBy(ws *> char(',') *> ws)
      _        <- ws *> char(']')
    } yield YamlValue.Sequence(elements)

    parser.runtime.interpret(seqParser, state)
  }

private def flowMapping: Parser[ParseError, YamlValue] =
  Parser.Custom { state =>
    val pair = for {
      key   <- plainString | quotedString
      _     <- ws *> char(':') *> ws
      value <- yamlValue
    } yield {
      val keyStr = key match {
        case YamlValue.String(s) => s
        case _                   => ""
      }
      (keyStr, value)
    }

    val mapParser = for {
      _     <- char('{') *> ws
      pairs <- pair.sepBy(ws *> char(',') *> ws)
      _     <- ws *> char('}')
    } yield YamlValue.Mapping(pairs.toMap)

    parser.runtime.interpret(mapParser, state)
  }

// ============================================================================
// Block Style (Indentation-based)
// ============================================================================

private def blockSequence: Parser[ParseError, YamlValue] =
  Parser.Custom { state =>
    val item = for {
      _     <- char('-') *> hspace.many1
      value <- yamlScalar
      _     <- newline.optional
    } yield value

    val seqParser = item.many1.map(YamlValue.Sequence.apply)

    parser.runtime.interpret(seqParser, state)
  }

private def blockMapping: Parser[ParseError, YamlValue] =
  Parser.Custom { state =>
    val pair = for {
      key   <- satisfy(c => c != ':' && c != '\n' && c != '#', "key char").many1.map(_.mkString.trim)
      _     <- char(':')
      _     <- hspace.many1 | newline.map(_ => ' ')
      value <- yamlScalar
      _     <- newline.optional
    } yield (key, value)

    val mapParser = pair.many1.map(pairs => YamlValue.Mapping(pairs.toMap))

    parser.runtime.interpret(mapParser, state)
  }

// ============================================================================
// Main Value Parser
// ============================================================================

private def yamlValue: Parser[ParseError, YamlValue] =
  Parser.Custom { state =>
    val valueParser =
      flowSequence |
        flowMapping |
        blockSequence |
        blockMapping |
        yamlScalar

    parser.runtime.interpret(valueParser, state)
  }

// ============================================================================
// Document
// ============================================================================

private def yamlDocument: Parser[ParseError, YamlDocument] =
  for {
    _    <- blankLine.many
    _    <- (string("---") <* newline.optional).optional
    _    <- blankLine.many
    root <- yamlValue
    _    <- blankLine.many
    _    <- (string("...") <* newline.optional).optional
    _    <- ws *> eof
  } yield (root = root, directives = List())
