package parsers.xml

import net.ghoula.sarati.ast.xml.{*, given}
import parsers.common.*

import parser.core.*
import parser.syntax.*

/** Parses an XML document from a string.
  *
  * Well-formed XML parser with namespace support:
  *   - Elements with start/end tags
  *   - Self-closing elements
  *   - Attributes (including namespace declarations)
  *   - Text content with entity expansion
  *   - CDATA sections
  *   - Comments
  *   - Processing instructions
  *   - XML declaration/prolog
  *   - Namespace prefixes
  *
  * @param input
  *   XML text
  * @param config
  *   Parsing configuration
  * @return
  *   Result containing parsed XML document
  */
def parseXml(input: String, config: XmlConfig = defaultXmlConfig): Result[ParseError, XmlDocument] =
  xmlDocument(config).run(input)

/** Parses just an XML element (fragment, not full document).
  */
def parseXmlFragment(input: String, config: XmlConfig = defaultXmlConfig): Result[ParseError, XmlNode] =
  (ws *> xmlElement(config) <* ws <* eof).run(input)

/** XML whitespace: space, tab, CR, LF.
  */
private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many.void

/** XML name: letter or _ or : followed by name chars.
  */
private def xmlName: Parser[ParseError, String] = {
  val nameStartChar = satisfy(
    c => c.isLetter || c == '_' || c == ':',
    "name start char"
  )
  val nameChar = satisfy(
    c => c.isLetterOrDigit || c == '_' || c == ':' || c == '-' || c == '.',
    "name char"
  )

  for {
    first <- nameStartChar
    rest <- nameChar.many
  } yield s"$first${rest.mkString}"
}

/** Parses a qualified name (with optional namespace prefix).
  */
private def qualifiedName: Parser[ParseError, QName] =
  xmlName.map(qname)

/** Parses XML declaration: <?xml version="1.0" encoding="UTF-8"?>
  */
private def xmlDecl: Parser[ParseError, (String, Option[String], Option[Boolean])] =
  for {
    _ <- string("<?xml")
    _ <- ws
    _ <- string("version")
    _ <- ws
    _ <- char('=')
    _ <- ws
    version <- quotedString('"', Map()) | quotedString('\'', Map())
    encoding <- (for {
      _ <- ws
      _ <- string("encoding")
      _ <- ws
      _ <- char('=')
      _ <- ws
      enc <- quotedString('"', Map()) | quotedString('\'', Map())
    } yield enc).optional
    standalone <- (for {
      _ <- ws
      _ <- string("standalone")
      _ <- ws
      _ <- char('=')
      _ <- ws
      value <- quotedString('"', Map()) | quotedString('\'', Map())
    } yield value == "yes").optional
    _ <- ws
    _ <- string("?>")
  } yield (version, encoding, standalone)

/** Consumes characters until the specified delimiter is found.
  */
private def untilString(delimiter: String): Parser[ParseError, String] = {
  val char = parser.core.notFollowedBy(string(delimiter)) *> satisfy(_ => true, "any char")
  char.many.map(_.mkString)
}

/** Parses XML comment: <!-- content -->
  */
private def xmlComment: Parser[ParseError, XmlNode] =
  for {
    _ <- string("<!--")
    content <- untilString("-->")
    _ <- string("-->")
  } yield XmlNode.Comment(content.trim)

/** Parses processing instruction: <?target content?>
  */
private def processingInstruction: Parser[ParseError, XmlNode] =
  for {
    _ <- string("<?")
    target <- xmlName
    _ <- ws
    content <- satisfy(c => c != '?', "PI content char").many.map(_.mkString)
    _ <- string("?>")
  } yield XmlNode.ProcessingInstruction(target, content.trim)

/** Parses CDATA section: <![CDATA[content]]>
  */
private def cdataSection: Parser[ParseError, XmlNode] =
  for {
    _ <- string("<![CDATA[")
    content <- untilString("]]>")
    _ <- string("]]>")
  } yield XmlNode.CData(content)

/** Parses entity reference: &name;
  */
private def entityRef(config: XmlConfig): Parser[ParseError, String] =
  for {
    _ <- char('&')
    name <- xmlName
    _ <- char(';')
  } yield
    if config.expandEntities then {
      xmlEntities.getOrElse(name, s"&$name;")
    } else {
      s"&$name;"
    }

/** Parses character reference: &#65; or &#x41;
  */
private def charRef: Parser[ParseError, String] = {
  val decimal = for {
    _ <- string("&#")
    digits <- digit.many1
    _ <- char(';')
  } yield digits.mkString.toInt.toChar.toString

  val hex = for {
    _ <- string("&#x")
    digits <- hexDigit.many1
    _ <- char(';')
  } yield Integer.parseInt(digits.mkString, 16).toChar.toString

  decimal | hex
}

/** Parses text content (character data).
  */
private def textContent(config: XmlConfig): Parser[ParseError, XmlNode] = {
  val regularChar = satisfy(c => c != '<' && c != '&', "text char")
  val entity = entityRef(config)
  val charReference = charRef

  for {
    parts <- (regularChar.map(_.toString) | entity | charReference).many1
    text = parts.mkString
  } yield {
    val content = if config.preserveWhitespace then text else text.trim
    XmlNode.Text(content)
  }
}

/** Parses an attribute value (quoted string with entity support).
  */
private def attributeValue(config: XmlConfig): Parser[ParseError, String] = {
  val quote = char('"') | char('\'')

  for {
    q <- quote
    parts <- (
      entityRef(config) |
        charRef |
        satisfy(c => c != q && c != '<' && c != '&', "attr char").map(_.toString)
    ).many
    _ <- char(q)
  } yield parts.mkString
}

/** Parses an attribute: name="value"
  */
private def attribute(config: XmlConfig): Parser[ParseError, XmlAttribute] =
  for {
    name <- qualifiedName
    _ <- ws
    _ <- char('=')
    _ <- ws
    value <- attributeValue(config)
  } yield (name = name, value = value)

/** Parses zero or more attributes.
  */
private def attributes(config: XmlConfig): Parser[ParseError, List[XmlAttribute]] =
  (ws *> attribute(config)).many

/** Parses self-closing element: <name attrs/>
  */
private def selfClosingElement(config: XmlConfig): Parser[ParseError, XmlNode] =
  for {
    _ <- char('<')
    name <- qualifiedName
    attrs <- attributes(config)
    _ <- ws
    _ <- string("/>")
  } yield XmlNode.Element(name, attrs, List())

/** Parses element with content: <name attrs>content</name>
  */
private def normalElement(config: XmlConfig): Parser[ParseError, XmlNode] =
  for {
    _ <- char('<')
    name <- qualifiedName
    attrs <- attributes(config)
    _ <- ws
    _ <- char('>')
    children <- xmlContent(config)
    _ <- string("</")
    _ <- ws
    closeName <- qualifiedName
    _ <- ws
    _ <- char('>')
    elem <-
      (name == closeName) match {
        case true => succeed(XmlNode.Element(name, attrs, children))
        case false =>
          failWith(s"mismatched end tag: expected </$name>, found </$closeName>")
      }
  } yield elem

/** Parses any XML element.
  */
private def xmlElement(config: XmlConfig): Parser[ParseError, XmlNode] =
  selfClosingElement(config) | normalElement(config)

/** Parses XML content (child nodes of an element).
  */
private def xmlContent(config: XmlConfig): Parser[ParseError, List[XmlNode]] = {
  val nodeParser =
    cdataSection |
      (if config.parseComments then xmlComment
       else failWith("comments are disabled by the parser configuration")) |
      (if config.parseProcessingInstructions then processingInstruction
       else failWith("processing instructions are disabled by the parser configuration")) |
      xmlElement(config) |
      textContent(config)

  val wrappedParser = if config.preserveWhitespace then {
    nodeParser
  } else {
    ws *> nodeParser <* ws
  }

  wrappedParser.many.map { nodes =>
    if config.preserveWhitespace then {
      nodes
    } else {
      nodes.filter {
        case XmlNode.Text(content) => content.nonEmpty
        case _ => true
      }
    }
  }
}

/** Parses a complete XML document.
  */
private def xmlDocument(config: XmlConfig): Parser[ParseError, XmlDocument] =
  for {
    _ <- ws
    decl <- xmlDecl.optional
    _ <- ws
    _ <- (processingInstruction | xmlComment).many
    _ <- ws
    root <- xmlElement(config)
    _ <- ws
    _ <- eof
  } yield {
    val (version, encoding, standalone) =
      decl.getOrElse((defaultXmlVersion, defaultXmlEncoding, None))
    (
      version = version,
      encoding = encoding,
      standalone = standalone,
      root = root
    )
  }

// Re-export Sarati's XML types and formatters so downstream consumers
// don't need to import from Sarati directly.
export net.ghoula.sarati.ast.xml.{formatXml, formatXmlDocument}
