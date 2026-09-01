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
  *   - DOCTYPE declarations with internal-subset entity expansion
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
  (ws *> xmlElement(initialCtx(config)) <* ws <* eof).run(input)

/** XML whitespace: space, tab, CR, LF.
  */
private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many.void

/** One or more XML whitespace characters.
  */
private def ws1: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many1.void

/** A single XML whitespace character.
  */
private def wsChar: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").void

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

// ==========================================================================
// DTD internal-subset support
// ==========================================================================

/** The three XML entity kinds: internal (replacement text in the subset), external (resolved via a
  * system ID — out of scope here), and unparsed (NDATA, not referenceable as parsed content).
  */
private enum EntityKind derives CanEqual { case Internal, External, Unparsed }

/** A declared entity: its kind plus captured replacement text and/or system ID.
  */
private final case class EntityInfo(
  kind: EntityKind,
  value: Option[String],
  systemId: Option[String]
)

/** Internal parse context: the user config plus the entity table built from the DOCTYPE internal
  * subset, threaded through the content parsers.
  */
private final case class XmlCtx(
  config: XmlConfig,
  entities: Map[String, EntityInfo],
  strictEntityCheck: Boolean
)

/** Context with no declared entities and strict undeclared-entity checking.
  */
private def initialCtx(config: XmlConfig): XmlCtx =
  XmlCtx(config, Map.empty, strictEntityCheck = true)

/** One item parsed from the DOCTYPE internal subset: either an entity declaration, a
  * parameter-entity reference (which relaxes strict checking), or anything else that is recognized
  * and skipped.
  */
private enum SubsetItem derives CanEqual {
  case Entity(name: String, info: EntityInfo)
  case PeRef()
  case Ignored()
}

/** Parses an entity reference: &name;
  */
private def entityRef(ctx: XmlCtx, inAttribute: Boolean = false): Parser[ParseError, String] =
  for {
    _ <- char('&')
    name <- xmlName
    _ <- char(';')
    expanded <- entityExpansion(ctx, name, inAttribute)
  } yield expanded

/** Resolves a named entity reference to its expanded text, or fails per the contextual strictness
  * and kind rules.
  */
private def entityExpansion(ctx: XmlCtx, name: String, inAttribute: Boolean): Parser[ParseError, String] =
  if !ctx.config.expandEntities then succeed(s"&$name;")
  else
    xmlEntities.get(name) match {
      case Some(replacement) => succeed(replacement)
      case None =>
        ctx.entities.get(name) match {
          case None =>
            if ctx.strictEntityCheck then failWith(s"undeclared entity: &$name;")
            else succeed(s"&$name;")
          case Some(info) =>
            info.kind match {
              case EntityKind.Unparsed =>
                failWith(s"reference to unparsed entity: &$name;")
              case EntityKind.External =>
                if inAttribute then failWith(s"external entity in attribute: &$name;")
                else succeed(s"&$name;")
              case EntityKind.Internal =>
                val value = info.value.getOrElse("")
                if inAttribute && value.contains('<') then failWith("< in entity replacement text used in attribute")
                else
                  expandReplacement(value, ctx, inAttribute) match {
                    case Left(err) => failWith(err)
                    case Right(expanded) => succeed(expanded)
                  }
            }
        }
    }

/** Expands entity and character references inside replacement text. Nested internal entities are
  * expanded recursively (cycles are rejected up front by [[findEntityCycle]]).
  */
private def expandReplacement(
  text: String,
  ctx: XmlCtx,
  inAttribute: Boolean
): Either[String, String] = {
  def loop(i: Int, sb: StringBuilder): Either[String, String] =
    if i >= text.length then Right(sb.result())
    else {
      val c = text(i)
      if c != '&' then {
        sb.append(c)
        loop(i + 1, sb)
      } else {
        val semi = text.indexOf(';', i + 1)
        if semi < 0 then Left("malformed entity reference in replacement text")
        else {
          val body = text.substring(i + 1, semi)
          if body.startsWith("#") then {
            val (radix, digits) =
              if body.startsWith("#x") || body.startsWith("#X") then (16, body.substring(2))
              else (10, body.substring(1))
            val cp =
              try Some(Integer.parseInt(digits, radix))
              catch case _: NumberFormatException => None
            cp match {
              case None => Left(s"invalid character reference: &$body;")
              case Some(value) =>
                sb.append(value.toChar.toString)
                loop(semi + 1, sb)
            }
          } else {
            xmlEntities.get(body) match {
              case Some(replacement) =>
                sb.append(replacement)
                loop(semi + 1, sb)
              case None =>
                ctx.entities.get(body) match {
                  case None =>
                    if ctx.strictEntityCheck then Left(s"undeclared entity: &$body;")
                    else {
                      sb.append(s"&$body;")
                      loop(semi + 1, sb)
                    }
                  case Some(info) =>
                    info.kind match {
                      case EntityKind.Unparsed =>
                        Left(s"reference to unparsed entity: &$body;")
                      case EntityKind.External =>
                        if inAttribute then Left(s"external entity in attribute: &$body;")
                        else {
                          sb.append(s"&$body;")
                          loop(semi + 1, sb)
                        }
                      case EntityKind.Internal =>
                        val value = info.value.getOrElse("")
                        if inAttribute && value.contains('<') then
                          Left("< in entity replacement text used in attribute")
                        else
                          expandReplacement(value, ctx, inAttribute) match {
                            case Left(err) => Left(err)
                            case Right(expanded) =>
                              sb.append(expanded)
                              loop(semi + 1, sb)
                          }
                    }
                }
            }
          }
        }
      }
    }

  loop(0, new StringBuilder)
}

/** Detects a reference cycle among the declared internal entities, returning the first entity name
  * on such a cycle.
  */
private def findEntityCycle(entities: Map[String, EntityInfo]): Option[String] = {
  def refsIn(value: String): List[String] = {
    val out = List.newBuilder[String]
    var i = 0
    while i < value.length do {
      if value(i) == '&' && i + 1 < value.length && value(i + 1) != '#' then {
        val semi = value.indexOf(';', i + 1)
        if semi > i + 1 then {
          out += value.substring(i + 1, semi)
          i = semi
        }
      }
      i += 1
    }
    out.result()
  }

  val visited = scala.collection.mutable.Set.empty[String]
  val stack = scala.collection.mutable.Set.empty[String]

  def hasCycle(name: String): Boolean =
    if stack.contains(name) then true
    else if visited.contains(name) then false
    else {
      visited += name
      stack += name
      val cyclic = entities.get(name).flatMap(_.value) match {
        case Some(value) => refsIn(value).exists(hasCycle)
        case None => false
      }
      stack -= name
      cyclic
    }

  entities.keys.find(hasCycle)
}

/** A quoted system literal: "..." or '...' with no embedded quote.
  */
private def systemLiteral: Parser[ParseError, String] = {
  def quoted(q: Char): Parser[ParseError, String] =
    (char(q) *> satisfy(c => c != q, "system literal char").many <* char(q)).map(_.mkString)
  quoted('"') | quoted('\'')
}

private val pubidChar: Parser[ParseError, Char] =
  satisfy(
    c => c.isLetterOrDigit || " \r\n-'()+,./:=?;!*#@$_%".contains(c),
    "pubid char"
  )

private def pubidLiteral: Parser[ParseError, Unit] = {
  def quoted(q: Char): Parser[ParseError, Unit] =
    char(q) *> pubidChar.many.void <* char(q)
  quoted('"') | quoted('\'')
}

/** External ID: SYSTEM syslit | PUBLIC pubid syslit.
  */
private def externalId: Parser[ParseError, Unit] =
  (string("SYSTEM") *> ws1 *> systemLiteral.void) |
    (string("PUBLIC") *> ws1 *> pubidLiteral *> ws1 *> systemLiteral.void)

/** External ID with the system literal captured (for external/unparsed entity declarations).
  */
private def externalIdCapture: Parser[ParseError, String] =
  (string("SYSTEM") *> ws1 *> systemLiteral) |
    (string("PUBLIC") *> ws1 *> pubidLiteral *> ws1 *> systemLiteral)

private def ndataDecl: Parser[ParseError, Unit] =
  ws1 *> string("NDATA") *> ws1 *> xmlName.void

/** A quoted entity-value literal: plain chars plus preserved entity and character references.
  */
private def entityValueLiteral: Parser[ParseError, String] = {
  def quoted(q: Char): Parser[ParseError, String] = {
    val plain = satisfy(c => c != q && c != '&' && c != '%', "entity value char").map(_.toString)
    val namedRef = (char('&') *> xmlName <* char(';')).map(name => s"&$name;")
    val charRefHex = (string("&#x") *> hexDigit.many1 <* char(';')).map(ds => s"&#x${ds.mkString};")
    val charRefDec = (string("&#") *> digit.many1 <* char(';')).map(ds => s"&#${ds.mkString};")
    (char(q) *> (plain | charRefHex | charRefDec | namedRef).many <* char(q)).map(_.mkString)
  }
  quoted('"') | quoted('\'')
}

/** A general entity declaration: <!ENTITY name "value"> | <!ENTITY name SYSTEM|PUBLIC ... [NDATA
  * n]>.
  */
private def generalEntityDecl: Parser[ParseError, (String, EntityInfo)] =
  for {
    name <- xmlName
    _ <- ws1
    info <- entityValueLiteral.map(v => EntityInfo(EntityKind.Internal, Some(v), None)) |
      externalIdCapture.flatMap { sysId =>
        ndataDecl.as(EntityInfo(EntityKind.Unparsed, None, Some(sysId))) |
          succeed(EntityInfo(EntityKind.External, None, Some(sysId)))
      }
    _ <- ws
    _ <- char('>')
  } yield (name, info)

/** A parameter entity declaration, recognized and skipped (parameter entities are not expanded).
  */
private def parameterEntityDecl: Parser[ParseError, Unit] =
  char('%') *> ws1 *> xmlName.void *> ws1 *> (entityValueLiteral.void | externalId) <* ws <* char('>')

/** An ENTITY declaration (general or parameter).
  */
private def entityDecl: Parser[ParseError, SubsetItem] =
  string("ENTITY") *> ws1 *> (
    parameterEntityDecl.as(SubsetItem.Ignored()) |
      generalEntityDecl.map(e => SubsetItem.Entity(e._1, e._2))
  )

// ELEMENT / ATTLIST / NOTATION declarations are parsed to be skipped: the internal subset may
// contain them and their grammar must be consumed correctly (e.g. quoted default values in ATTLIST).

private def nmtoken: Parser[ParseError, Unit] =
  satisfy(c => c.isLetterOrDigit || c == '_' || c == ':' || c == '-' || c == '.', "name char").many1.void

private def contentParticle: Parser[ParseError, Unit] =
  (xmlName.void | defer(contentGroup)) <* oneOf("?*+").void.optional

private def contentGroup: Parser[ParseError, Unit] = {
  val seqSep = ws *> char(',') *> ws *> defer(contentParticle)
  val choiceSep = ws *> char('|') *> ws *> defer(contentParticle)
  char('(') *> ws *> defer(contentParticle) *> (
    ((choiceSep).many1 <* ws <* char(')')).void |
      ((seqSep).many <* ws <* char(')')).void
  )
}

private def mixed: Parser[ParseError, Unit] =
  char('(') *> ws *> string("#PCDATA") *> (
    ((ws *> char('|') *> ws *> xmlName.void).many <* ws <* string(")*")).void |
      (ws *> char(')')).void
  )

private def contentspec: Parser[ParseError, Unit] =
  string("EMPTY").void | string("ANY").void | mixed |
    (contentGroup <* oneOf("?*+").void.optional)

private def elementDecl: Parser[ParseError, Unit] =
  string("ELEMENT") *> ws1 *> xmlName.void *> ws1 *> contentspec <* ws <* char('>')

private def attType: Parser[ParseError, Unit] = {
  val tokenized = stringIn("IDREFS", "IDREF", "ID", "ENTITIES", "ENTITY", "NMTOKENS", "NMTOKEN").void
  val notationType = string("NOTATION") *> ws1 *> char('(') *> ws *> xmlName.void *>
    (ws *> char('|') *> ws *> xmlName.void).many <* ws <* char(')')
  val enumeration = char('(') *> ws *> nmtoken *>
    (ws *> char('|') *> ws *> nmtoken).many <* ws <* char(')')
  string("CDATA").void | tokenized | notationType.void | enumeration.void
}

private def dtdAttValue: Parser[ParseError, Unit] = {
  def quoted(q: Char): Parser[ParseError, Unit] = {
    val plain = satisfy(c => c != q && c != '<' && c != '&', "attr value char").void
    val ref = (char('&') *> satisfy(_ != ';', "entity ref char").many1 <* char(';')).void
    (char(q) *> (plain | ref).many <* char(q)).void
  }
  quoted('"') | quoted('\'')
}

private def defaultDecl: Parser[ParseError, Unit] =
  string("#REQUIRED").void | string("#IMPLIED").void |
    (string("#FIXED") *> ws1 *> dtdAttValue) |
    dtdAttValue

private def attDef: Parser[ParseError, Unit] =
  ws1 *> xmlName.void *> ws1 *> attType *> ws1 *> defaultDecl

private def attlistDecl: Parser[ParseError, Unit] =
  (string("ATTLIST") *> ws1 *> xmlName.void *> attDef.many <* ws <* char('>')).void

private def notationDecl: Parser[ParseError, Unit] = {
  val publicId = string("PUBLIC") *> ws1 *> pubidLiteral
  string("NOTATION") *> ws1 *> xmlName.void *> ws1 *> (externalId | publicId) <* ws <* char('>')
}

private def markupDecl: Parser[ParseError, SubsetItem] =
  string("<!") *> (
    entityDecl |
      elementDecl.as(SubsetItem.Ignored()) |
      attlistDecl.as(SubsetItem.Ignored()) |
      notationDecl.as(SubsetItem.Ignored())
  )

/** One item inside the bracketed internal subset.
  */
private def internalSubsetItem: Parser[ParseError, SubsetItem] =
  markupDecl |
    (char('%') *> xmlName <* char(';')).as(SubsetItem.PeRef()) |
    xmlComment.as(SubsetItem.Ignored()) |
    processingInstruction.as(SubsetItem.Ignored()) |
    wsChar.as(SubsetItem.Ignored())

/** The bracketed internal subset: a sequence of the items above, folded into an entity table plus a
  * has-parameter-entity-reference flag.
  */
private def internalSubset: Parser[ParseError, (Map[String, EntityInfo], Boolean)] =
  internalSubsetItem.many.map { items =>
    val m = scala.collection.mutable.Map.empty[String, EntityInfo]
    var hasPERefs = false
    for item <- items do
      item match {
        case SubsetItem.Entity(name, info) =>
          if !m.contains(name) then m(name) = info
        case SubsetItem.PeRef() =>
          hasPERefs = true
        case SubsetItem.Ignored() => ()
      }
    (m.toMap, hasPERefs)
  }

/** Parses a DOCTYPE declaration, building the parse context from its internal subset. External IDs
  * are recognized but not resolved; parameter-entity references relax strict undeclared-entity
  * checking (the external subset may declare what we cannot see).
  */
private def doctypeDecl(config: XmlConfig): Parser[ParseError, XmlCtx] =
  (for {
    _ <- string("<!DOCTYPE")
    _ <- ws1
    _ <- xmlName
    hasExternalId <- (ws1 *> externalId).optional
    _ <- ws
    subset <- (char('[') *> internalSubset <* char(']') <* ws).optional
    _ <- char('>')
  } yield (hasExternalId, subset)).flatMap { case (hasExternalId, subset) =>
    val (entities, hasPERefs) = subset.getOrElse((Map.empty[String, EntityInfo], false))
    val hasExternalMarkup = hasExternalId.isDefined || hasPERefs
    findEntityCycle(entities) match {
      case Some(name) => failWith(s"circular entity reference: $name")
      case None => succeed(XmlCtx(config, entities, strictEntityCheck = !hasExternalMarkup))
    }
  }

/** Runs the DOCTYPE parser when DTD resolution is enabled; otherwise returns a bare context. Uses a
  * non-consuming lookahead to distinguish "no DOCTYPE" (fall back to a bare context) from a
  * malformed DOCTYPE (which must fail rather than be silently skipped).
  */
private def maybeDoctype(config: XmlConfig): Parser[ParseError, XmlCtx] =
  if !config.resolveDtd then succeed(initialCtx(config))
  else
    parser.core.lookAhead(string("<!DOCTYPE")).optional.flatMap {
      case Some(_) => doctypeDecl(config)
      case None => succeed(initialCtx(config))
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
private def textContent(ctx: XmlCtx): Parser[ParseError, XmlNode] = {
  val regularChar = satisfy(c => c != '<' && c != '&', "text char")
  val entity = entityRef(ctx)
  val charReference = charRef

  for {
    parts <- (regularChar.map(_.toString) | entity | charReference).many1
    text = parts.mkString
  } yield {
    val content = if ctx.config.preserveWhitespace then text else text.trim
    XmlNode.Text(content)
  }
}

/** Parses an attribute value (quoted string with entity support).
  */
private def attributeValue(ctx: XmlCtx): Parser[ParseError, String] = {
  val quote = char('"') | char('\'')

  for {
    q <- quote
    parts <- (
      entityRef(ctx, inAttribute = true) |
        charRef |
        satisfy(c => c != q && c != '<' && c != '&', "attr char").map(_.toString)
    ).many
    _ <- char(q)
  } yield parts.mkString
}

/** Parses an attribute: name="value"
  */
private def attribute(ctx: XmlCtx): Parser[ParseError, XmlAttribute] =
  for {
    name <- qualifiedName
    _ <- ws
    _ <- char('=')
    _ <- ws
    value <- attributeValue(ctx)
  } yield (name = name, value = value)

/** Parses zero or more attributes.
  */
private def attributes(ctx: XmlCtx): Parser[ParseError, List[XmlAttribute]] =
  (ws *> attribute(ctx)).many

/** Parses self-closing element: <name attrs/>
  */
private def selfClosingElement(ctx: XmlCtx): Parser[ParseError, XmlNode] =
  for {
    _ <- char('<')
    name <- qualifiedName
    attrs <- attributes(ctx)
    _ <- ws
    _ <- string("/>")
  } yield XmlNode.Element(name, attrs, List())

/** Parses element with content: <name attrs>content</name>
  */
private def normalElement(ctx: XmlCtx): Parser[ParseError, XmlNode] =
  for {
    _ <- char('<')
    name <- qualifiedName
    attrs <- attributes(ctx)
    _ <- ws
    _ <- char('>')
    children <- xmlContent(ctx)
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
private def xmlElement(ctx: XmlCtx): Parser[ParseError, XmlNode] =
  selfClosingElement(ctx) | normalElement(ctx)

/** Parses XML content (child nodes of an element).
  */
private def xmlContent(ctx: XmlCtx): Parser[ParseError, List[XmlNode]] = {
  val nodeParser =
    cdataSection |
      (if ctx.config.parseComments then xmlComment
       else failWith("comments are disabled by the parser configuration")) |
      (if ctx.config.parseProcessingInstructions then processingInstruction
       else failWith("processing instructions are disabled by the parser configuration")) |
      xmlElement(ctx) |
      textContent(ctx)

  val wrappedParser = if ctx.config.preserveWhitespace then {
    nodeParser
  } else {
    ws *> nodeParser <* ws
  }

  wrappedParser.many.map { nodes =>
    if ctx.config.preserveWhitespace then {
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
    ctx <- maybeDoctype(config)
    _ <- ws
    root <- xmlElement(ctx)
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
