package parsers.xpath

import net.ghoula.sarati.xpath.*
import parsers.common.*

import parser.core.*
import parser.syntax.*

/** Parses an XPath 1.0 expression into Sarati's [[XPathExpr]] AST.
  *
  * The grammar is the XPath 1.0 production set (~30 productions) with the precedence ladder `or` <
  * `and` < equality < relational < additive < multiplicative < unary `-` < union < path (per the
  * spec grammar, not the prose ladder in the scope plan). Built with `chainl1` per level; no
  * separate tokenizer. Two hand-rolled lexical guards replicate the Dart port's techniques:
  *   - keyword operators (`and`, `or`, `div`, `mod`) carry a name-continuation boundary guard, so
  *     `a andb` fails rather than parsing as `a and b`; the name reading survives in step position
  *     (`child::and`, `r/and`).
  *   - `.` and `..` steps require that no digit follows the dot, so `.5` is a number; the number
  *     parser is tried before the step parser, making the guard a second line of defense.
  *
  * FilterExpr predicates attach to the primary ([[XPathExpr.Filter]]) — `(expr)` with no predicates
  * and no steps yields the bare primary, since a predicateless filter is the identity.
  */
def parseXPath(input: String): Result[ParseError, XPathExpr] =
  (expr <* eof).run(input)

/** XPath whitespace: space, tab, CR, LF (ExprWhitespace). */
private def ws: Parser[ParseError, Unit] =
  satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace").many.void

/** A lexeme: the token with whitespace on both sides. All tokens go through this, so operands never
  * see leading whitespace.
  */
private def lexeme[A](p: Parser[ParseError, A]): Parser[ParseError, A] = ws *> p <* ws

private def symbol(s: String): Parser[ParseError, String] = lexeme(string(s))

/** A keyword operator: the exact word, not extended by an NCName continuation character. The
  * boundary guard runs before the trailing whitespace skip — `and-b` is one name, `a and-b` an
  * error, `a and -b` the operator.
  */
private def keyword(k: String): Parser[ParseError, Unit] =
  lexeme(string(k) <* nameContChar.notFollowedBy).void

/** NCName continuation: letter, digit, `.`, `-`, `_` (XML Namespaces NCNameChar minus `:`). */
private val nameContChar: Parser[ParseError, Char] =
  satisfy(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '_', "name character")

/** NCName: letter or `_`, then NCName chars (letter, digit, `.`, `-`, `_`). */
private def ncName: Parser[ParseError, String] =
  for {
    first <- satisfy(c => c.isLetter || c == '_', "NCName")
    rest <- nameContChar.many
  } yield (first :: rest).mkString

/** XPath 1.0 Number: `Digits ('.' Digits?)?` or `'.' Digits` — no sign, no exponent. */
private def numberLiteral: Parser[ParseError, XPathExpr] = lexeme {
  val fractional = for {
    whole <- digit.many1
    frac <- (char('.') *> digit.many1.optional).optional
  } yield (whole.mkString, frac) match {
    case (w, Some(Some(f))) => s"$w.${f.mkString}"
    case (w, Some(None)) => s"$w."
    case (w, None) => w
  }
  fractional | (char('.') *> digit.many1.map("." + _.mkString))
}.map(text => XPathExpr.Number(text.toDouble))

/** Literal: `'...'` or `"..."` — XPath 1.0 has no escape sequences; the other quote kind is the
  * escape hatch.
  */
private def literalString: Parser[ParseError, String] = {
  val single = (char('\'') *> satisfy(_ != '\'', "literal character").many <* char('\'')).map(_.mkString)
  val double = (char('"') *> satisfy(_ != '"', "literal character").many <* char('"')).map(_.mkString)
  single | double
}

private def literal: Parser[ParseError, XPathExpr] = literalString.map(XPathExpr.Literal(_))

private def variableReference: Parser[ParseError, XPathExpr] =
  lexeme(char('$') *> ncName).map(XPathExpr.VariableRef(_))

private def parenExpr: Parser[ParseError, XPathExpr] =
  symbol("(") *> expr <* symbol(")")

/** FunctionName ::= QName - NodeType: a node-type name followed by `(` is a node test, never a call
  * (`node()` must reach the location-path branch). The guard's error never surfaces — the
  * location-path branch parses node tests, and any syntax error inside them is positioned there.
  */
private val nodeTypeNames: Set[String] = Set("node", "text", "comment", "processing-instruction")

private def functionCall: Parser[ParseError, XPathExpr] =
  ncName.flatMap { name =>
    if nodeTypeNames(name) then failWith(s"function name expected, node test `$name` found")
    else
      for {
        _ <- symbol("(")
        args <- expr.sepBy(symbol(","))
        _ <- symbol(")")
      } yield XPathExpr.FunctionCall(name, args)
  }

private def primaryExpr: Parser[ParseError, XPathExpr] =
  variableReference | parenExpr | literal | numberLiteral | functionCall

private def predicate: Parser[ParseError, XPathExpr] =
  symbol("[") *> expr <* symbol("]")

private val axisNames: Map[String, Axis] = Map(
  "ancestor" -> Axis.Ancestor,
  "ancestor-or-self" -> Axis.AncestorOrSelf,
  "attribute" -> Axis.Attribute,
  "child" -> Axis.Child,
  "descendant" -> Axis.Descendant,
  "descendant-or-self" -> Axis.DescendantOrSelf,
  "following" -> Axis.Following,
  "following-sibling" -> Axis.FollowingSibling,
  "namespace" -> Axis.Namespace,
  "parent" -> Axis.Parent,
  "preceding" -> Axis.Preceding,
  "preceding-sibling" -> Axis.PrecedingSibling,
  "self" -> Axis.Self
)

/** Axis name, longest-match via the radix keywords parser (`descendant-or-self` wins over
  * `descendant`); `@` abbreviates `attribute::`; a bare step defaults to `child`.
  */
private def axisSpecifier: Parser[ParseError, Axis] =
  lexeme(char('@')).map(_ => Axis.Attribute) |
    (keywords(axisNames) <* symbol("::")) |
    succeed(Axis.Child)

/** Node-type tests are recognized only with their parens, so a bare `node` stays a name test. */
private def nodeTypeTest: Parser[ParseError, NodeTest] =
  (keywords(Map("node" -> NodeTest.Node, "text" -> NodeTest.Text, "comment" -> NodeTest.Comment)) <*
    symbol("(") <* symbol(")")) |
    (string("processing-instruction") *> symbol("(") *> literalString.optional <* symbol(")"))
      .map(NodeTest.ProcessingInstruction(_))

private def nameTest: Parser[ParseError, NameTest] =
  char('*').flatMap { _ =>
    (char(':') *> ncName).optional.map {
      case Some(local) => NameTest.AnyNamespace(local)
      case None => NameTest.Any
    }
  } | ncName.flatMap { prefix =>
    (
      char(':') *>
        (char('*').map(_ => NameTest.AnyLocalInNamespace(prefix)) |
          ncName.map(local => NameTest.Named(Some(prefix), local)))
    ).optional.map(_.getOrElse(NameTest.Named(None, prefix)))
  }

private def nodeTest: Parser[ParseError, NodeTest] =
  nodeTypeTest | nameTest.map(NodeTest.Name(_))

/** `..` before `.`; the dot step rejects a following digit so `.5` never parses as a step. */
private def dotDotStep: Parser[ParseError, Step] =
  lexeme(string("..")).map(_ => Step(Axis.Parent, NodeTest.Node, List.empty))

private def dotStep: Parser[ParseError, Step] =
  lexeme(char('.') <* digit.notFollowedBy).map(_ => Step(Axis.Self, NodeTest.Node, List.empty))

private def axisStep: Parser[ParseError, Step] =
  for {
    axis <- axisSpecifier
    test <- nodeTest
    preds <- predicate.many
  } yield Step(axis, test, preds)

private def step: Parser[ParseError, Step] = dotDotStep | dotStep | axisStep

/** `//` inserts an explicit `descendant-or-self::node()` step (the AST has no `//` marker). */
private def stepSuffix: Parser[ParseError, List[Step]] =
  (lexeme(string("//")).map(_ => true) | lexeme(string("/")).map(_ => false)).flatMap { descendantOrSelf =>
    if descendantOrSelf then step.map(s => List(Step(Axis.DescendantOrSelf, NodeTest.Node, List.empty), s))
    else step.map(List(_))
  }

private def relativeSteps: Parser[ParseError, List[Step]] =
  step.flatMap(first => stepSuffix.many.map(rest => first :: rest.flatten))

private def relativeLocationPath: Parser[ParseError, XPathExpr] =
  relativeSteps.map(XPathExpr.Path(isAbsolute = false, _))

private def absoluteLocationPath: Parser[ParseError, XPathExpr] =
  (lexeme(string("//")) *>
    relativeSteps.map(steps =>
      XPathExpr.Path(isAbsolute = true, Step(Axis.DescendantOrSelf, NodeTest.Node, List.empty) :: steps)
    )) |
    (lexeme(string("/")) *> relativeSteps.optional.map {
      case Some(steps) => XPathExpr.Path(isAbsolute = true, steps)
      case None => XPathExpr.Path(isAbsolute = true, List.empty)
    })

private def locationPath: Parser[ParseError, XPathExpr] =
  absoluteLocationPath | relativeLocationPath

/** FilterExpr = primary + predicates + optional `/`/`//` continuation. With no predicates and no
  * steps the bare primary is returned — `(1)` must stay a number, not a filter wrapping one.
  */
private def filterExpr: Parser[ParseError, XPathExpr] =
  for {
    primary <- primaryExpr
    preds <- predicate.many
    first <- stepSuffix.optional
    rest <- stepSuffix.many
  } yield (preds, first) match {
    case (Nil, None) => primary
    case (ps, head) => XPathExpr.Filter(primary, ps, head.getOrElse(List.empty) ++ rest.flatten)
  }

private def pathExpr: Parser[ParseError, XPathExpr] = filterExpr | locationPath

private def unionExpr: Parser[ParseError, XPathExpr] =
  pathExpr.chainl1(symbol("|").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Union(l, r)))

private def unaryExpr: Parser[ParseError, XPathExpr] =
  (lexeme(char('-')) *> defer(unaryExpr)).map(XPathExpr.Negation(_)) | unionExpr

private def multiplicativeExpr: Parser[ParseError, XPathExpr] =
  unaryExpr.chainl1(
    (symbol("*").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Arithmetic(l, r, ArithOp.Mul)) |
      keyword("div").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Arithmetic(l, r, ArithOp.Div)) |
      keyword("mod").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Arithmetic(l, r, ArithOp.Mod)))
  )

private def additiveExpr: Parser[ParseError, XPathExpr] =
  multiplicativeExpr.chainl1(lexeme(stringIn("+", "-")).map {
    case "+" => (l: XPathExpr, r: XPathExpr) => XPathExpr.Arithmetic(l, r, ArithOp.Add)
    case _ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Arithmetic(l, r, ArithOp.Sub)
  })

private def relationalExpr: Parser[ParseError, XPathExpr] =
  additiveExpr.chainl1(lexeme(stringIn("<=", ">=", "<", ">")).map {
    case "<=" => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Le)
    case ">=" => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Ge)
    case "<" => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Lt)
    case _ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Gt)
  })

private def equalityExpr: Parser[ParseError, XPathExpr] =
  relationalExpr.chainl1(lexeme(stringIn("!=", "=")).map {
    case "!=" => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Ne)
    case _ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Comparison(l, r, BinaryOp.Eq)
  })

private def andExpr: Parser[ParseError, XPathExpr] =
  equalityExpr.chainl1(keyword("and").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.And(l, r)))

private def orExpr: Parser[ParseError, XPathExpr] =
  andExpr.chainl1(keyword("or").map(_ => (l: XPathExpr, r: XPathExpr) => XPathExpr.Or(l, r)))

private def expr: Parser[ParseError, XPathExpr] = defer(orExpr)

// Re-export Sarati's XPath AST types so downstream consumers don't need to import from Sarati
// directly for what this parser produces.
export net.ghoula.sarati.xpath.{ArithOp, Axis, BinaryOp, NameTest, NodeTest, Step, XPathExpr}
