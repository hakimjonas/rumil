package parsers.xpath

import net.ghoula.sarati.xpath.*

/** Prints an [[XPathExpr]] back to text in a canonical abbreviated form: `.` for `self::node()`,
  * `..` for `parent::node()`, `@` for the attribute axis, `//` for `descendant-or-self::node()`, an
  * omitted `child::`, and full `axis::test` otherwise.
  *
  * The output reparses to the same AST for every parser-reachable expression. Two exceptions are
  * inherent to XPath 1.0, both unreachable from [[parseXPath]]: a negative `XPathExpr.Number` (the
  * grammar has no signed literals — it prints via [[XPathEval.formatNumber]] and reparses as unary
  * negation), and a literal containing both quote kinds (XPath 1.0 literals cannot represent one;
  * it prints single-quote-delimited).
  */
def printXPath(expr: XPathExpr): String = expr match {
  case XPathExpr.Or(left, right) => s"${printXPath(left)} or ${printXPath(right)}"
  case XPathExpr.And(left, right) => s"${printXPath(left)} and ${printXPath(right)}"
  case XPathExpr.Comparison(left, right, op) =>
    s"${printXPath(left)} ${comparisonOperator(op)} ${printXPath(right)}"
  case XPathExpr.Arithmetic(left, right, op) =>
    s"${printXPath(left)} ${arithmeticOperator(op)} ${printXPath(right)}"
  case XPathExpr.Negation(inner) => s"-${printXPath(inner)}"
  case XPathExpr.Union(left, right) => s"${printXPath(left)} | ${printXPath(right)}"
  case XPathExpr.Path(isAbsolute, steps) =>
    if isAbsolute then s"/${printSteps(steps)}" else printSteps(steps)
  case XPathExpr.Filter(primary, predicates, steps) =>
    val printed = s"(${printXPath(primary)})"
    val preds = predicates.map(p => s"[${printXPath(p)}]").mkString
    val stepsPart = if steps.isEmpty then "" else s"/${printSteps(steps)}"
    printed + preds + stepsPart
  case XPathExpr.Literal(value) => quoteLiteral(value)
  case XPathExpr.Number(value) => XPathEval.formatNumber(value)
  case XPathExpr.VariableRef(name) => s"$$$name"
  case XPathExpr.FunctionCall(name, args) => s"$name(${args.map(printXPath).mkString(", ")})"
}

private def printSteps(steps: List[Step]): String = steps.map(printStep).mkString("/")

/** One step; bare `self::node()`/`parent::node()`/`descendant-or-self::node()` print as `.`, `..`
  * and the empty `//` glue respectively — only without predicates, which the abbreviated forms
  * cannot carry per the Step grammar.
  */
private def printStep(step: Step): String = {
  val test = printTest(step.test)
  val preds = step.predicates.map(p => s"[${printXPath(p)}]").mkString
  val bare = step.test == NodeTest.Node && step.predicates.isEmpty
  step.axis match {
    case Axis.Child => test + preds
    case Axis.Attribute => s"@$test$preds"
    case Axis.Self if bare => "."
    case Axis.Parent if bare => ".."
    case Axis.DescendantOrSelf if bare => ""
    case axis => s"${axisName(axis)}::$test$preds"
  }
}

private def printTest(test: NodeTest): String = test match {
  case NodeTest.Name(NameTest.Any) => "*"
  case NodeTest.Name(NameTest.AnyLocalInNamespace(prefix)) => s"$prefix:*"
  case NodeTest.Name(NameTest.AnyNamespace(local)) => s"*:$local"
  case NodeTest.Name(NameTest.Named(None, local)) => local
  case NodeTest.Name(NameTest.Named(Some(prefix), local)) => s"$prefix:$local"
  case NodeTest.Node => "node()"
  case NodeTest.Text => "text()"
  case NodeTest.Comment => "comment()"
  case NodeTest.ProcessingInstruction(None) => "processing-instruction()"
  case NodeTest.ProcessingInstruction(Some(lit)) => s"processing-instruction(${quoteLiteral(lit)})"
}

private def comparisonOperator(op: BinaryOp): String = op match {
  case BinaryOp.Eq => "="
  case BinaryOp.Ne => "!="
  case BinaryOp.Lt => "<"
  case BinaryOp.Le => "<="
  case BinaryOp.Gt => ">"
  case BinaryOp.Ge => ">="
}

private def arithmeticOperator(op: ArithOp): String = op match {
  case ArithOp.Add => "+"
  case ArithOp.Sub => "-"
  case ArithOp.Mul => "*"
  case ArithOp.Div => "div"
  case ArithOp.Mod => "mod"
}

private val axisName: Map[Axis, String] = Map(
  Axis.Child -> "child",
  Axis.Descendant -> "descendant",
  Axis.Parent -> "parent",
  Axis.Ancestor -> "ancestor",
  Axis.FollowingSibling -> "following-sibling",
  Axis.PrecedingSibling -> "preceding-sibling",
  Axis.Following -> "following",
  Axis.Preceding -> "preceding",
  Axis.Attribute -> "attribute",
  Axis.Namespace -> "namespace",
  Axis.Self -> "self",
  Axis.DescendantOrSelf -> "descendant-or-self",
  Axis.AncestorOrSelf -> "ancestor-or-self"
)

/** `'...'` unless the value itself contains `'`, then `"..."`. A value containing both quote kinds
  * has no XPath 1.0 representation; it prints single-quote-delimited (lossy).
  */
private def quoteLiteral(value: String): String =
  if !value.contains('\'') then s"'$value'" else s""""$value""""
