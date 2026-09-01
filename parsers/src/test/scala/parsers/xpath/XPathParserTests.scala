package parsers.xpath

import munit.FunSuite
import net.ghoula.sarati.xpath.*

import parser.core.*
import parser.syntax.*

class XPathParserTests extends FunSuite {

  // ==== builders ====

  private def step(axis: Axis, test: NodeTest, preds: XPathExpr*): Step =
    Step(axis, test, preds.toList)

  private def name(local: String): NodeTest = NodeTest.Name(NameTest.Named(None, local))

  private def prefixed(prefix: String, local: String): NodeTest =
    NodeTest.Name(NameTest.Named(Some(prefix), local))

  private def childStep(test: NodeTest, preds: XPathExpr*): Step =
    step(Axis.Child, test, preds*)

  private val dosStep: Step = step(Axis.DescendantOrSelf, NodeTest.Node)

  private val selfStep: Step = step(Axis.Self, NodeTest.Node)

  private val parentStep: Step = step(Axis.Parent, NodeTest.Node)

  private def rel(test: NodeTest, preds: XPathExpr*): XPathExpr =
    XPathExpr.Path(isAbsolute = false, List(childStep(test, preds*)))

  /** A relative path over explicit steps (for non-child axes, where `rel` does not apply). */
  private def relSteps(steps: Step*): XPathExpr =
    XPathExpr.Path(isAbsolute = false, steps.toList)

  private def abs(steps: Step*): XPathExpr = XPathExpr.Path(isAbsolute = true, steps.toList)

  private def lit(s: String): XPathExpr = XPathExpr.Literal(s)

  private def numLit(d: Double): XPathExpr = XPathExpr.Number(d)

  private def call(n: String, args: XPathExpr*): XPathExpr = XPathExpr.FunctionCall(n, args.toList)

  private def vr(n: String): XPathExpr = XPathExpr.VariableRef(n)

  // ==== harness ====

  private def parse(input: String): XPathExpr = parseXPath(input) match {
    case Result.Success(value, _) => value
    case other => fail(s"'$input' expected a successful parse, got $other")
  }

  private def parsesTo(input: String, expected: XPathExpr): Unit =
    assertEquals(parse(input), expected, s"input: $input")

  /** The plan's error cases must fail with a positioned error — not just fail. */
  private def failsPositioned(input: String): Unit = parseXPath(input) match {
    case Result.Failure(errors, furthest) =>
      assert(errors.nonEmpty, s"'$input' failed without errors")
      assert(furthest.offset > 0, s"'$input' error not positioned: $furthest")
    case other => fail(s"'$input' expected failure, got $other")
  }

  // ==== 1. round-trip: parse(print(parse(x))) == parse(x) ====

  private val roundTripCorpus = List(
    // §5.3 group 1 — child/name tests
    "a",
    "/a",
    "r/a",
    "*",
    "/*/b",
    "r/c|c",
    "r/b",
    // group 2 — descendant/abbrev
    "//b",
    ".//b",
    "r/child::a",
    "descendant::b",
    "//b/parent::*",
    "r/a/following-sibling::*",
    // group 3 — node tests
    "r/a/text()",
    "//text()",
    "@id",
    "r/item/@kind",
    "r//@id",
    "node()",
    "comment()",
    "processing-instruction()",
    "processing-instruction('p')",
    // group 4 — predicates
    "r/a[1]",
    "r/a[last()]",
    "r/a[position()>1]",
    "r/a[b='1']",
    "r/item[@id=8]",
    "r/item[@kind]",
    "r/a[2][b]",
    // group 5 — functions
    "count(r/a)",
    "sum(r//b)",
    "string(r/a)",
    "string(r)",
    "string(/)",
    "contains(r/c,'3')",
    "concat(r/a[1]/b, r/a[2]/b)",
    "normalize-space(r)",
    "substring(r/c,2,1)",
    "name(r/*[1])",
    "local-name(r/*[1])",
    "string-length(r/c)",
    "number(//v)",
    "floor(r//v[2])",
    "round(r//v[2])",
    // group 6 — booleans/numeric scalars
    "r/a/b='1'",
    "count(r/a)=2",
    "boolean(r/a)",
    "not(r/z)",
    "r/c>2",
    "1+2*3",
    "-r//v",
    "r//v div 0",
    // group 8 — D6 paths
    "ns:b",
    "b",
    "*:b",
    // wildcards and axes beyond the §5.3 list
    "ns:*",
    "ancestor-or-self::*",
    "preceding::x",
    "namespace::n",
    "self::node()",
    "parent::node()",
    // numbers, literals, filters, operators
    ".5",
    "12.",
    "2.50",
    "--1",
    "-.5",
    "(1)",
    "(r/a)[2]",
    "(r/a)[2]//b",
    "(r/a)/b",
    "$var",
    "$var[1]",
    "$var//b",
    "a and(1)",
    "a and b",
    "a div div",
    "child::and",
    "r/and",
    "a - *",
    ".",
    "..",
    "\"it's\""
  )

  test("round-trip: print reparses to the same AST across the corpus") {
    roundTripCorpus.foreach { input =>
      val ast = parse(input)
      val printed = printXPath(ast)
      assertEquals(parseXPath(printed).toOption.get, ast, s"round-trip of '$input' (printed '$printed')")
    }
  }

  test("round-trip: the printer's canonical form is stable") {
    roundTripCorpus.foreach { input =>
      val once = printXPath(parse(input))
      val twice = printXPath(parse(once))
      assertEquals(twice, once, s"printer not idempotent for '$input'")
    }
  }

  // ==== 2. the §5.3 list parses to expected AST shapes ====

  test("child steps and name tests (§5.3 group 1)") {
    parsesTo("a", rel(name("a")))
    parsesTo("/a", abs(childStep(name("a"))))
    parsesTo("r/a", relSteps(childStep(name("r")), childStep(name("a"))))
    parsesTo("*", rel(NodeTest.Name(NameTest.Any)))
    parsesTo("/*/b", abs(childStep(NodeTest.Name(NameTest.Any)), childStep(name("b"))))
    parsesTo("r/c|c", XPathExpr.Union(relSteps(childStep(name("r")), childStep(name("c"))), rel(name("c"))))
  }

  test("descendant and abbreviated steps (§5.3 group 2)") {
    parsesTo("//b", abs(dosStep, childStep(name("b"))))
    parsesTo(".//b", XPathExpr.Path(isAbsolute = false, List(selfStep, dosStep, childStep(name("b")))))
    // the child axis is the default: explicit and abbreviated parse identically
    parsesTo("r/child::a", relSteps(childStep(name("r")), childStep(name("a"))))
    parsesTo("descendant::b", XPathExpr.Path(isAbsolute = false, List(step(Axis.Descendant, name("b")))))
    parsesTo("//b/parent::*", abs(dosStep, childStep(name("b")), step(Axis.Parent, NodeTest.Name(NameTest.Any))))
    parsesTo(
      "r/a/following-sibling::*",
      relSteps(childStep(name("r")), childStep(name("a")), step(Axis.FollowingSibling, NodeTest.Name(NameTest.Any)))
    )
  }

  test("node tests (§5.3 group 3)") {
    parsesTo("r/a/text()", relSteps(childStep(name("r")), childStep(name("a")), childStep(NodeTest.Text)))
    parsesTo("//text()", abs(dosStep, childStep(NodeTest.Text)))
    parsesTo("@id", relSteps(step(Axis.Attribute, name("id"))))
    parsesTo(
      "r/item/@kind",
      relSteps(childStep(name("r")), childStep(name("item")), step(Axis.Attribute, name("kind")))
    )
    parsesTo(
      "r//@id",
      XPathExpr.Path(isAbsolute = false, List(childStep(name("r")), dosStep, step(Axis.Attribute, name("id"))))
    )
    parsesTo("node()", rel(NodeTest.Node))
    parsesTo("comment()", rel(NodeTest.Comment))
    parsesTo("processing-instruction()", rel(NodeTest.ProcessingInstruction(None)))
    parsesTo("processing-instruction('p')", rel(NodeTest.ProcessingInstruction(Some("p"))))
  }

  test("predicates (§5.3 group 4)") {
    parsesTo("r/a[1]", relSteps(childStep(name("r")), childStep(name("a"), numLit(1))))
    parsesTo("r/a[last()]", relSteps(childStep(name("r")), childStep(name("a"), call("last"))))
    parsesTo(
      "r/a[position()>1]",
      relSteps(
        childStep(name("r")),
        childStep(name("a"), XPathExpr.Comparison(call("position"), numLit(1), BinaryOp.Gt))
      )
    )
    parsesTo(
      "r/a[b='1']",
      relSteps(childStep(name("r")), childStep(name("a"), XPathExpr.Comparison(rel(name("b")), lit("1"), BinaryOp.Eq)))
    )
    parsesTo(
      "r/item[@id=8]",
      relSteps(
        childStep(name("r")),
        childStep(
          name("item"),
          XPathExpr.Comparison(relSteps(step(Axis.Attribute, name("id"))), numLit(8), BinaryOp.Eq)
        )
      )
    )
    parsesTo(
      "r/item[@kind]",
      relSteps(childStep(name("r")), childStep(name("item"), relSteps(step(Axis.Attribute, name("kind")))))
    )
    parsesTo(
      "r/a[2][b]",
      relSteps(childStep(name("r")), childStep(name("a"), numLit(2), rel(name("b"))))
    )
  }

  test("function calls (§5.3 group 5)") {
    parsesTo("count(r/a)", call("count", relSteps(childStep(name("r")), childStep(name("a")))))
    parsesTo("sum(r//b)", call("sum", relSteps(childStep(name("r")), dosStep, childStep(name("b")))))
    parsesTo("string(r/a)", call("string", relSteps(childStep(name("r")), childStep(name("a")))))
    parsesTo("string(r)", call("string", relSteps(childStep(name("r")))))
    parsesTo("string(/)", call("string", XPathExpr.Path(isAbsolute = true, List.empty)))
    parsesTo("contains(r/c,'3')", call("contains", relSteps(childStep(name("r")), childStep(name("c"))), lit("3")))
    parsesTo(
      "concat(r/a[1]/b, r/a[2]/b)",
      call(
        "concat",
        relSteps(childStep(name("r")), childStep(name("a"), numLit(1)), childStep(name("b"))),
        relSteps(childStep(name("r")), childStep(name("a"), numLit(2)), childStep(name("b")))
      )
    )
    parsesTo("normalize-space(r)", call("normalize-space", relSteps(childStep(name("r")))))
    parsesTo(
      "substring(r/c,2,1)",
      call("substring", relSteps(childStep(name("r")), childStep(name("c"))), numLit(2), numLit(1))
    )
    parsesTo(
      "name(r/*[1])",
      call("name", relSteps(childStep(name("r")), childStep(NodeTest.Name(NameTest.Any), numLit(1))))
    )
    parsesTo(
      "local-name(r/*[1])",
      call("local-name", relSteps(childStep(name("r")), childStep(NodeTest.Name(NameTest.Any), numLit(1))))
    )
    parsesTo("string-length(r/c)", call("string-length", relSteps(childStep(name("r")), childStep(name("c")))))
    parsesTo("number(//v)", call("number", abs(dosStep, childStep(name("v")))))
    parsesTo(
      "floor(r//v[2])",
      call("floor", relSteps(childStep(name("r")), dosStep, childStep(name("v"), numLit(2))))
    )
    parsesTo(
      "round(r//v[2])",
      call("round", relSteps(childStep(name("r")), dosStep, childStep(name("v"), numLit(2))))
    )
  }

  test("operator ladder and precedence (§5.3 group 6)") {
    parsesTo(
      "r/a/b='1'",
      XPathExpr.Comparison(
        relSteps(childStep(name("r")), childStep(name("a")), childStep(name("b"))),
        lit("1"),
        BinaryOp.Eq
      )
    )
    parsesTo(
      "count(r/a)=2",
      XPathExpr.Comparison(call("count", relSteps(childStep(name("r")), childStep(name("a")))), numLit(2), BinaryOp.Eq)
    )
    parsesTo("boolean(r/a)", call("boolean", relSteps(childStep(name("r")), childStep(name("a")))))
    parsesTo("not(r/z)", call("not", relSteps(childStep(name("r")), childStep(name("z")))))
    parsesTo(
      "r/c>2",
      XPathExpr.Comparison(relSteps(childStep(name("r")), childStep(name("c"))), numLit(2), BinaryOp.Gt)
    )
    // multiplication binds tighter than addition
    parsesTo(
      "1+2*3",
      XPathExpr.Arithmetic(numLit(1), XPathExpr.Arithmetic(numLit(2), numLit(3), ArithOp.Mul), ArithOp.Add)
    )
    parsesTo("-r//v", XPathExpr.Negation(relSteps(childStep(name("r")), dosStep, childStep(name("v")))))
    parsesTo(
      "r//v div 0",
      XPathExpr.Arithmetic(relSteps(childStep(name("r")), dosStep, childStep(name("v"))), numLit(0), ArithOp.Div)
    )
    // or/and bind loosest, equality binds tighter than and
    parsesTo(
      "a or b and c=d",
      XPathExpr.Or(
        rel(name("a")),
        XPathExpr.And(rel(name("b")), XPathExpr.Comparison(rel(name("c")), rel(name("d")), BinaryOp.Eq))
      )
    )
  }

  test("D6 prefixed paths (§5.3 group 8)") {
    parsesTo("ns:b", rel(prefixed("ns", "b")))
    parsesTo("b", rel(name("b")))
    parsesTo("*:b", rel(NodeTest.Name(NameTest.AnyNamespace("b"))))
    parsesTo("ns:*", rel(NodeTest.Name(NameTest.AnyLocalInNamespace("ns"))))
  }

  // ==== 3. FilterExpr primaries (the sarati Filter shape) ====

  test("a parenthesized expression with no predicates or steps stays the bare primary") {
    // (1) must be the number one, not a filter wrapping it — the evaluator treats a
    // predicateless filter over a non-node-set as an empty node-set
    parsesTo("(1)", numLit(1))
    parsesTo("string((1))", call("string", numLit(1)))
    parsesTo(
      "(r/a)[2]",
      XPathExpr.Filter(relSteps(childStep(name("r")), childStep(name("a"))), List(numLit(2)), List.empty)
    )
    parsesTo(
      "(r/a)[2]//b",
      XPathExpr.Filter(
        relSteps(childStep(name("r")), childStep(name("a"))),
        List(numLit(2)),
        List(dosStep, childStep(name("b")))
      )
    )
    parsesTo("$var", vr("var"))
    parsesTo("$var[1]", XPathExpr.Filter(vr("var"), List(numLit(1)), List.empty))
  }

  // ==== 4. lexical disambiguation ====

  test("keyword operators vs names") {
    parsesTo(
      "a and b",
      XPathExpr.And(rel(name("a")), rel(name("b")))
    )
    // `and` in step position is an element name
    parsesTo("child::and", rel(name("and")))
    parsesTo("r/and", relSteps(childStep(name("r")), childStep(name("and"))))
    // operator then name
    parsesTo(
      "a div div",
      XPathExpr.Arithmetic(rel(name("a")), rel(name("div")), ArithOp.Div)
    )
    // one name, not an operator: the boundary guard rejects `and` + continuation
    assert(parseXPath("a andb").isFailure, "'a andb' must not parse as 'a and b'")
    // NCName after an operator token stays the operator, so `and(1)` after `a` is the and-operator
    parsesTo("a and(1)", XPathExpr.And(rel(name("a")), numLit(1)))
  }

  test("dot abbreviations vs decimal literals") {
    parsesTo(".5", numLit(0.5))
    parsesTo("..", XPathExpr.Path(isAbsolute = false, List(parentStep)))
    parsesTo(".", XPathExpr.Path(isAbsolute = false, List(selfStep)))
    // `.5` after a slash is not a step
    assert(parseXPath("a/.5").isFailure, "'a/.5' must not parse")
  }

  test("star as operator vs wildcard name test") {
    val product = XPathExpr.Arithmetic(numLit(2), numLit(3), ArithOp.Mul)
    parsesTo("2*3", product)
    parsesTo("2 * 3", product)
    parsesTo("*", rel(NodeTest.Name(NameTest.Any)))
    // after the operator `-`, `*` is a name test, per the spec's operator-adjacency rule
    parsesTo(
      "a - *",
      XPathExpr.Arithmetic(rel(name("a")), rel(NodeTest.Name(NameTest.Any)), ArithOp.Sub)
    )
    // without the space, `a-` is one NCName (`-` is an NCNameChar) and the `*` trails: an error
  }

  // ==== 5. errors are positioned ====

  test("error cases fail with positioned ParseErrors") {
    failsPositioned("'abc") // unterminated literal
    failsPositioned("r/[")
    failsPositioned("r/a[]") // empty predicate
    failsPositioned("$") // variable reference with no name
    failsPositioned("a b") // trailing input
    failsPositioned("r/foo::b") // unknown axis name
    failsPositioned("(a") // unterminated paren
    failsPositioned("foo(") // unterminated call
    failsPositioned("a and") // dangling operator
    failsPositioned("1..2") // a number cannot continue with .2
    failsPositioned("r/a[b") // unterminated predicate
    failsPositioned("@") // attribute axis with no test
    failsPositioned("a-*") // `a-` is one NCName; the `*` trails
  }

  test("empty input fails") {
    assertEquals(parseXPath("").isFailure, true)
  }

  // ==== 6. printer shapes ====

  test("abbreviated canonical printing") {
    assertEquals(printXPath(parse("//b")), "//b")
    assertEquals(printXPath(parse(".//b")), ".//b")
    assertEquals(printXPath(parse("@id")), "@id")
    assertEquals(printXPath(parse(".")), ".")
    assertEquals(printXPath(parse("..")), "..")
    assertEquals(printXPath(parse("r//@id")), "r//@id")
    assertEquals(printXPath(parse("//b/parent::*")), "//b/parent::*")
    assertEquals(printXPath(parse("r/ancestor-or-self::*")), "r/ancestor-or-self::*")
    // abbreviated forms cannot carry predicates — the full axis form is used instead
    assertEquals(printXPath(parse("self::node()[1]")), "self::node()[1]")
    assertEquals(printXPath(parse("parent::node()[2]")), "parent::node()[2]")
    assertEquals(printXPath(parse("(r/a)[2]")), "(r/a)[2]")
    assertEquals(printXPath(parse("(r/a)[2]//b")), "(r/a)[2]//b")
    assertEquals(printXPath(parse("2.50")), "2.5")
    assertEquals(printXPath(parse("\"it's\"")), "\"it's\"")
    assertEquals(printXPath(parse("processing-instruction('p')")), "processing-instruction('p')")
    assertEquals(printXPath(parse("1+2*3")), "1 + 2 * 3")
  }

  // ==== 7. stack-safety pins (scope plan §6: the Dart chainl1 overflowed at depth 850) ====

  private def countOr(expr: XPathExpr): Int = expr match {
    case XPathExpr.Or(l, r) => 1 + countOr(l) + countOr(r)
    case _ => 0
  }

  test("long or-chains parse without stack overflow") {
    val chain = List.fill(2000)("a").mkString(" or ")
    assertEquals(countOr(parse(chain)), 1999)
  }

  test("long location paths parse without stack overflow") {
    val chain = List.fill(2000)("a").mkString("/")
    parse(chain) match {
      case XPathExpr.Path(false, steps) => assertEquals(steps.length, 2000)
      case other => fail(s"expected a path, got $other")
    }
  }

  test("deeply nested parens parse without stack overflow") {
    val nested = "(" * 500 + "1" + ")" * 500
    parsesTo(nested, numLit(1))
  }
}
