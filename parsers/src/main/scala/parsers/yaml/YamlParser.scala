package parsers.yaml

import parser.core._
import parser.syntax._
import parsers.common._

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

private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many.void

private def yamlComment: Parser[ParseError, Unit] =
  char('#') *> satisfy(_ != '\n', "comment char").many *> (newline.void | eof)

private def blankLine: Parser[ParseError, Unit] =
  hspaces *> (yamlComment | newline.void)

private def indent(n: Int): Parser[ParseError, Unit] =
  hspace.count(n).void

private def yamlNull: Parser[ParseError, YamlValue] =
  stringIn("null", "~").as(YamlValue.Null)

private def yamlBoolean: Parser[ParseError, YamlValue] =
  keywords(
    Map(
      "true"  -> YamlValue.Boolean(true),
      "yes"   -> YamlValue.Boolean(true),
      "on"    -> YamlValue.Boolean(true),
      "false" -> YamlValue.Boolean(false),
      "no"    -> YamlValue.Boolean(false),
      "off"   -> YamlValue.Boolean(false)
    ))

private def yamlNumber: Parser[ParseError, YamlValue] = {
  val integer = (signedInt <* parser.core.notFollowedBy(oneOf(".eE")))
    .map(n => YamlValue.Integer(n.toLong))
  val float = floatingPoint.map(YamlValue.Float.apply)

  integer | float
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

private lazy val flowSequence: Parser[ParseError, YamlValue] =
  for {
    _        <- char('[') *> ws
    elements <- defer(yamlValue).sepBy(ws *> char(',') *> ws)
    _        <- ws *> char(']')
  } yield YamlValue.Sequence(elements)

private lazy val flowMapping: Parser[ParseError, YamlValue] = {
  val pair = for {
    key   <- plainString | quotedString
    _     <- ws *> char(':') *> ws
    value <- defer(yamlValue)
  } yield {
    val keyStr = key match {
      case YamlValue.String(s) => s
      case _                   => ""
    }
    (keyStr, value)
  }

  for {
    _     <- char('{') *> ws
    pairs <- pair.sepBy(ws *> char(',') *> ws)
    _     <- ws *> char('}')
  } yield YamlValue.Mapping(pairs.toMap)
}

private lazy val blockSequence: Parser[ParseError, YamlValue] = {
  val item = for {
    _     <- char('-') *> hspace.many1
    value <- yamlScalar
    _     <- newline.optional
  } yield value

  item.many1.map(YamlValue.Sequence.apply)
}

private lazy val blockMapping: Parser[ParseError, YamlValue] = {
  val pair = for {
    key   <- satisfy(c => c != ':' && c != '\n' && c != '#', "key char").many1.map(_.mkString.trim)
    _     <- char(':')
    _     <- hspace.many1 | newline.map(_ => ' ')
    value <- yamlScalar
    _     <- newline.optional
  } yield (key, value)

  pair.many1.map(pairs => YamlValue.Mapping(pairs.toMap))
}

private lazy val yamlValue: Parser[ParseError, YamlValue] =
  flowSequence |
    flowMapping |
    blockSequence |
    blockMapping |
    yamlScalar

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
