package parser

import munit.FunSuite
import parser.core._
import parser.syntax._

class LeftRecursionTests extends FunSuite {

  test("direct left recursion - list") {
    // Grammar: list = list ',' item | item
    enum ListExpr {
      case Single(value: Int)
      case Cons(list: ListExpr, value: Int)
    }

    val item = digit.map(_.toString.toInt)

    lazy val list: Parser[ParseError, ListExpr] = recursive {
      (list ~ char(',') ~ item).map { case ((l, _), i) => ListExpr.Cons(l, i) } |
        item.map(ListExpr.Single(_))
    }

    val result = list.run("1,2,3,4")
    assert(result.isSuccess)

    // Verify structure: Cons(Cons(Cons(Single(1), 2), 3), 4)
    result.toOption match {
      case Some(ListExpr.Cons(ListExpr.Cons(ListExpr.Cons(ListExpr.Single(1), 2), 3), 4)) =>
        () // Expected structure
      case other =>
        fail(s"Unexpected structure: $other")
    }
  }

  test("indirect left recursion") {
    // Grammar: a = b 'x' | 'y'
    //          b = a 'z' | 'w'
    lazy val a: Parser[ParseError, String] = recursive {
      (b ~ char('x')).map { case (s, c) => s + c } |
        char('y').map(_.toString)
    }

    lazy val b: Parser[ParseError, String] = recursive {
      (a ~ char('z')).map { case (s, c) => s + c } |
        char('w').map(_.toString)
    }

    assertEquals(a.run("wxzx").toOption, Some("wxzx"))
    assertEquals(a.run("y").toOption, Some("y"))
    assertEquals(b.run("w").toOption, Some("w"))
  }

  test("arithmetic with direct left recursion") {
    enum Expr {
      case Num(n: Int)
      case Add(l: Expr, r: Expr)
      case Mul(l: Expr, r: Expr)
    }

    val num = digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

    lazy val expr: Parser[ParseError, Expr] = recursive {
      (expr ~ char('+') ~ term).map { case ((l, _), r) => Expr.Add(l, r) } |
        term
    }

    lazy val term: Parser[ParseError, Expr] = recursive {
      (term ~ char('*') ~ factor).map { case ((l, _), r) => Expr.Mul(l, r) } |
        factor
    }

    lazy val factor: Parser[ParseError, Expr] =
      num | (char('(') *> expr <* char(')'))

    val result = expr.run("2+3*4")
    assert(result.isSuccess)

    // Verify: Add(Num(2), Mul(Num(3), Num(4)))
    result.toOption match {
      case Some(Expr.Add(Expr.Num(2), Expr.Mul(Expr.Num(3), Expr.Num(4)))) =>
        () // Expected structure
      case other =>
        fail(s"Unexpected structure: $other")
    }
  }

  test("left recursion with backtracking") {
    lazy val p: Parser[ParseError, String] = recursive {
      (p ~ char('a')).map { case (s, c) => s + c } |
        (p ~ char('b')).map { case (s, c) => s + c } |
        char('c').map(_.toString)
    }

    assertEquals(p.run("caabaa").toOption, Some("caabaa"))
    assertEquals(p.run("cbbb").toOption, Some("cbbb"))
    assertEquals(p.run("c").toOption, Some("c"))
  }

  test("hidden left recursion") {
    // Grammar: a = (b 'x') | 'y'
    //          b = (a)
    lazy val a: Parser[ParseError, String] = recursive {
      (b ~ char('x')).map { case (s, c) => s + c } |
        char('y').map(_.toString)
    }

    lazy val b: Parser[ParseError, String] = recursive { a }

    assertEquals(a.run("yxxx").toOption, Some("yxxx"))
    assertEquals(a.run("y").toOption, Some("y"))
  }

  test("non-left-recursive parser still works") {
    val p = char('a') ~ char('b') ~ char('c')
    assertEquals(p.run("abc").toOption, Some((('a', 'b'), 'c')))
  }

  test("existing chainl1 still works") {
    val num = digit.map(_.toString.toInt)
    val add = char('+').as((a: Int, b: Int) => a + b)
    val p = num.chainl1(add)
    assertEquals(p.run("1+2+3").toOption, Some(6))
  }

  test("left recursion with optional parts") {
    // Grammar: expr = expr '+' num | num
    lazy val expr: Parser[ParseError, Int] = recursive {
      (expr ~ char('+') ~ num).map { case ((l, _), r) => l + r } |
        num
    }

    val num = digit.map(_.toString.toInt)

    assertEquals(expr.run("5").toOption, Some(5))
    assertEquals(expr.run("1+2+3+4").toOption, Some(10))
  }

  test("left recursion with multiple alternatives") {
    enum Op {
      case Add(l: Op, r: Op)
      case Sub(l: Op, r: Op)
      case Num(n: Int)
    }

    val num = digit.map(n => Op.Num(n.toString.toInt))

    lazy val expr: Parser[ParseError, Op] = recursive {
      (expr ~ char('+') ~ num).map { case ((l, _), r) => Op.Add(l, r) } |
        (expr ~ char('-') ~ num).map { case ((l, _), r) => Op.Sub(l, r) } |
        num
    }

    val result1 = expr.run("1+2+3")
    assert(result1.isSuccess)

    val result2 = expr.run("5-3")
    assert(result2.isSuccess)
  }

  test("deeply nested left recursion") {
    // Grammar: a = a a 'x' | 'y'
    lazy val a: Parser[ParseError, String] = recursive {
      (a ~ a ~ char('x')).map { case ((s1, s2), c) => s1 + s2 + c } |
        char('y').map(_.toString)
    }

    val result = a.run("yyyx")
    assert(result.isSuccess)
  }

  test("left recursion with parentheses") {
    enum Expr {
      case Num(n: Int)
      case Add(l: Expr, r: Expr)
    }

    val num = digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

    lazy val expr: Parser[ParseError, Expr] = recursive {
      (expr ~ char('+') ~ factor).map { case ((l, _), r) => Expr.Add(l, r) } |
        factor
    }

    lazy val factor: Parser[ParseError, Expr] =
      num | (char('(') *> expr <* char(')'))

    val result = expr.run("(1+2)+3")
    assert(result.isSuccess)

    result.toOption match {
      case Some(Expr.Add(Expr.Add(Expr.Num(1), Expr.Num(2)), Expr.Num(3))) =>
        () // Expected
      case other =>
        fail(s"Unexpected: $other")
    }
  }

  test("left recursion termination") {
    // Ensure the algorithm terminates even with complex recursion
    lazy val p: Parser[ParseError, String] = recursive {
      (p ~ p).map { case (a, b) => a + b } |
        char('x').map(_.toString)
    }

    val result = p.run("xxxx")
    assert(result.isSuccess)
  }

  test("left recursion with many combinator") {
    enum Expr {
      case Num(n: Int)
      case Add(l: Expr, r: Expr)
    }

    val num = digit.map(c => Expr.Num(c.toString.toInt))

    lazy val expr: Parser[ParseError, Expr] = recursive {
      (expr ~ char('+') ~ num).map { case ((l, _), r) => Expr.Add(l, r) } |
        num
    }

    val result = expr.run("1+2+3+4+5")
    assert(result.isSuccess)
  }

  test("mixed left and right recursion") {
    enum Expr {
      case Add(l: Expr, r: Expr)
      case Pow(l: Expr, r: Expr)
      case Num(n: Int)
    }

    val num = digit.map(c => Expr.Num(c.toString.toInt))

    // Left-associative addition
    lazy val expr: Parser[ParseError, Expr] = recursive {
      (expr ~ char('+') ~ term).map { case ((l, _), r) => Expr.Add(l, r) } |
        term
    }

    // Right-associative power
    lazy val term: Parser[ParseError, Expr] = recursive {
      (num ~ char('^') ~ term).map { case ((l, _), r) => Expr.Pow(l, r) } |
        num
    }

    val result = expr.run("2^3+4")
    assert(result.isSuccess)
  }

  test("left recursion error handling") {
    lazy val expr: Parser[ParseError, Int] = recursive {
      (expr ~ char('+') ~ digit.map(_.toString.toInt)).map { case ((l, _), r) => l + r } |
        digit.map(_.toString.toInt)
    }

    val result = expr.run("1+")
    assert(result.isFailure)
  }

  test("simple left recursion") {
    // Most basic test: a = a 'x' | 'y'
    lazy val a: Parser[ParseError, String] = recursive {
      (a ~ char('x')).map { case (s, c) => s + c } |
        char('y').map(_.toString)
    }

    assertEquals(a.run("y").toOption, Some("y"))
    assertEquals(a.run("yx").toOption, Some("yx"))
    assertEquals(a.run("yxx").toOption, Some("yxx"))
    assertEquals(a.run("yxxx").toOption, Some("yxxx"))
  }
}
