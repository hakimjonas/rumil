package parsers.json

import parser.core.*
import parser.syntax.*

/** Lossless JSON parsing: the same RFC 8259 grammar as [[parseJson]], producing green trees that
  * preserve the source byte-for-byte instead of Sarati `JsonValue`s.
  *
  * Shape:
  *   - every leaf is a [[GreenNodeOf.Token]] carrying its exact source text (whitespace runs are
  *     first-class `Whitespace` token children, so nothing is swallowed);
  *   - objects, members, and arrays are [[GreenNodeOf.Tree]] nodes ([[JsonSyntaxKind.Object]],
  *     `Member`, `Array`);
  *   - the top level is a `Document` tree; scalars stay bare tokens (their token kind already
  *     classifies them).
  *
  * The lossless invariant on every success path: `GreenNodeOf.toSource(tree) == input`. On total
  * parse failure the [[jsonReparseable]] bundle's `onParseFailure` builds an `Error`-token tree
  * preserving the source, so downstream consumers never lose text.
  */
object JsonLossless {

  /** Green-tree type for JSON. */
  type JsonGreen = GreenNodeOf[JsonLanguage.Token, JsonLanguage.Syntax]

  // ------------------------------------------------------------------
  // Token-level parsers (each captures its exact source text)
  // ------------------------------------------------------------------

  /** A whitespace run as a Whitespace token; None when the next char is not whitespace. */
  private lazy val gws: Parser[ParseError, List[JsonGreen]] =
    satisfy(c => c == ' ' || c == '\t' || c == '\n' || c == '\r', "whitespace").many
      .map(chars =>
        if chars.isEmpty then Nil
        else List(GreenNode.token(JsonLanguage.Tokens.Whitespace, chars.mkString))
      )

  /** A single-char token. */
  private def gchar(kind: JsonLanguage.Token, c: Char): Parser[ParseError, JsonGreen] =
    char(c).as(GreenNode.token(kind, c.toString))

  private lazy val gLBrace: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.LBrace, '{')
  private lazy val gRBrace: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.RBrace, '}')
  private lazy val gLBracket: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.LBracket, '[')
  private lazy val gRBracket: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.RBracket, ']')
  private lazy val gColon: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.Colon, ':')
  private lazy val gComma: Parser[ParseError, JsonGreen] = gchar(JsonLanguage.Tokens.Comma, ',')

  /** `null` / `true` / `false` keywords with exact text. */
  private def gkeyword(kind: JsonLanguage.Token, word: String): Parser[ParseError, JsonGreen] =
    string(word).as(GreenNode.token(kind, word))

  /** A JSON number token — raw text preserved (no `toDouble` rounding). */
  private lazy val gNumber: Parser[ParseError, JsonGreen] = {
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

    (for {
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
      }.getOrElse("")
      GreenNode.token(JsonLanguage.Tokens.Number, s"$sign$intPart$frac$exp")
    }).named("number")
  }

  /** A JSON string token — raw text INCLUDING the quotes and escape sequences (no decoding). */
  private lazy val gString: Parser[ParseError, JsonGreen] = {
    val stringCharRaw = satisfy(c => c != '"' && c != '\\' && c >= '\u0020', "string char")
    val escapeRaw = (char('\\') ~ anyChar).map { case (bs, c) => s"$bs$c" }
    val inner = (escapeRaw | stringCharRaw).many
    (for {
      open <- char('"')
      body <- inner
      close <- char('"')
    } yield GreenNode.token(JsonLanguage.Tokens.Str, s"$open${body.mkString}$close")).named("string")
  }

  // ------------------------------------------------------------------
  // Structure
  // ------------------------------------------------------------------

  /** Trailing whitespace helper: parse `p` then any following whitespace run, keeping both as
    * siblings. whitespace BETWEEN tokens is thus never lost.
    */
  private def withTrailingWs(p: Parser[ParseError, JsonGreen]): Parser[ParseError, List[JsonGreen]] =
    (p ~ gws).map { case (node, wsNodes) => node :: wsNodes }

  /** `key : value` member, with surrounding whitespace preserved as children.
    *
    * A `def`, not a `lazy val`: it references [[gValue]], which cycles back through
    * `gObject → gMembers → gMember`. The same init-cycle constraint the semantic JSON parser
    * documents; a method defers the reference to parse time.
    */
  private def gMember: Parser[ParseError, JsonGreen] =
    (for {
      lead <- gws
      key <- gString
      colonWs <- gws
      colon <- gColon
      valueWs <- gws
      value <- gValue
      trail <- gws
    } yield GreenNode.treeOfVec(
      JsonLanguage.Syntaxes.Member,
      (lead ++ List(key) ++ colonWs ++ List(colon) ++ valueWs ++ List(value) ++ trail).toVector
    )).named("member")

  /** `{ member *(, member) }` — commas and whitespace become children in source order. */
  private lazy val gObject: Parser[ParseError, JsonGreen] =
    (for {
      lead <- gws
      open <- gLBrace
      members <- gMembers
      closeWs <- gws
      close <- gRBrace
      trail <- gws
    } yield GreenNode.treeOfVec(
      JsonLanguage.Syntaxes.Object,
      (lead ++ List(open) ++ members ++ closeWs ++ List(close) ++ trail).toVector
    )).named("object")

  /** `member *(, member)` — the first member (if any), then comma-prefixed groups. An empty object
    * leaves interior whitespace to the object's own closeWs child.
    */
  private lazy val gMembers: Parser[ParseError, List[JsonGreen]] = {
    // `more` carries its own empty fallback: a missing comma must end the repetition, not
    // fail the chain (which would backtrack the whole first member).
    def more: Parser[ParseError, List[JsonGreen]] =
      ((for {
        pre <- gws
        comma <- gComma
        member <- gMember
        rest <- more
      } yield pre ++ List(comma) ++ List(member) ++ rest) | Parser.Succeed(Nil))

    ((for {
      first <- gMember
      rest <- more
    } yield List(first) ++ rest) | Parser.Succeed(Nil))
  }

  /** `[ value *(, value) ]` with whitespace preserved. */
  private lazy val gArray: Parser[ParseError, JsonGreen] =
    (for {
      lead <- gws
      open <- gLBracket
      elements <- gElements
      closeWs <- gws
      close <- gRBracket
      trail <- gws
    } yield GreenNode.treeOfVec(
      JsonLanguage.Syntaxes.Array,
      (lead ++ List(open) ++ elements ++ closeWs ++ List(close) ++ trail).toVector
    )).named("array")

  /** `value *(, value)` — the first element (if any), then comma-prefixed groups. */
  private lazy val gElements: Parser[ParseError, List[JsonGreen]] = {
    def more: Parser[ParseError, List[JsonGreen]] =
      ((for {
        pre <- gws
        comma <- gComma
        valueWs <- gws
        value <- gValue
        rest <- more
      } yield pre ++ List(comma) ++ valueWs ++ List(value) ++ rest) | Parser.Succeed(Nil))

    ((for {
      first <- gValue
      rest <- more
    } yield List(first) ++ rest) | Parser.Succeed(Nil))
  }

  /** Any JSON value — an object, array, string, number, or keyword token. */
  private lazy val gValue: Parser[ParseError, JsonGreen] =
    (gObject |
      gArray |
      gString |
      gNumber |
      gkeyword(JsonLanguage.Tokens.True, "true") |
      gkeyword(JsonLanguage.Tokens.False, "false") |
      gkeyword(JsonLanguage.Tokens.Null, "null")).named("value")

  /** Whole document: optional leading whitespace, one value, optional trailing whitespace, EOF. */
  private lazy val gDocument: Parser[ParseError, JsonGreen] =
    (for {
      lead <- gws
      value <- gValue
      trail <- gws
      _ <- eof
    } yield GreenNode.treeOfVec(
      JsonLanguage.Syntaxes.Document,
      (lead ++ List(value) ++ trail).toVector
    )).named("document")

  // ------------------------------------------------------------------
  // Public entry points
  // ------------------------------------------------------------------

  /** Batch lossless parse: the entire input as a green tree. Lossless on success
    * (`GreenNodeOf.toSource(tree) == input`) and on total failure (the `onParseFailure` fallback of
    * [[jsonReparseable]]).
    */
  def parseJsonLossless(input: String): Result[ParseError, JsonGreen] =
    gDocument.run(input)

  /** Reparseable-parsers bundle for incremental JSON editing: object, member, and array regions can
    * reparse in isolation; number, string, and keyword tokens are "simple" (editable in place);
    * total failure falls back to an Error-token tree that still preserves the source.
    */
  lazy val jsonReparseable: IncrementalParser.ReparseableParsers[JsonLanguage.Token, JsonLanguage.Syntax, ParseError] =
    IncrementalParser.ReparseableParsers[JsonLanguage.Token, JsonLanguage.Syntax, ParseError](
      full = gDocument,
      byKind = Map(
        JsonLanguage.Syntaxes.Object -> gObject,
        JsonLanguage.Syntaxes.Member -> gMember,
        JsonLanguage.Syntaxes.Array -> gArray
      ),
      isSimpleToken = kind =>
        kind == JsonLanguage.Tokens.Number || kind == JsonLanguage.Tokens.Str ||
          kind == JsonLanguage.Tokens.True || kind == JsonLanguage.Tokens.False ||
          kind == JsonLanguage.Tokens.Null,
      onParseFailure = source => GreenNode.token(JsonLanguage.Tokens.Error, source)
    )
}
