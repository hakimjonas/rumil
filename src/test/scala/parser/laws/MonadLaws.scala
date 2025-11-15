package parser.laws

import org.scalacheck.{Prop, Arbitrary, Gen, Properties}
import org.scalacheck.Prop.propBoolean
import parser.core.{Result as PResult, *}
import parser.syntax.*
import parser.typeclasses.{*, given}

// ============================================================================
// MONAD LAWS - Property-based verification
// ============================================================================

object ResultMonadLaws extends Properties("Result Monad Laws") {

  // Generators for PResult
  given Arbitrary[PResult[String, Int]] = Arbitrary {
    Gen.oneOf(
      Gen.choose(0, 100).map(n => PResult.Success(n, 0)),
      Gen.alphaNumStr.map(s => PResult.Failure(List(s), (line = 1, column = 1, offset = 0)))
    )
  }

  given Arbitrary[Int => PResult[String, Int]] = Arbitrary {
    Gen.oneOf(
      Gen.const((x: Int) => PResult.Success(x * 2, 0)),
      Gen.const((x: Int) => PResult.Success(x + 1, 0)),
      Gen.const((_: Int) => PResult.Failure(List("error"), (line = 1, column = 1, offset = 0)))
    )
  }

  val M = summon[Monad[[A] =>> PResult[String, A]]]

  // Left identity: pure(a).flatMap(f) == f(a)
  property("left identity") = Prop.forAll { (a: Int, f: Int => PResult[String, Int]) =>
    val left = M.flatMap(M.pure(a))(f)
    val right = f(a)

    (left, right) match {
      case (PResult.Success(v1, _), PResult.Success(v2, _)) => v1 == v2
      case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
      case _ => false
    }
  }

  // Right identity: m.flatMap(pure) == m
  property("right identity") = Prop.forAll { (m: PResult[String, Int]) =>
    val left = M.flatMap(m)(M.pure)

    (left, m) match {
      case (PResult.Success(v1, _), PResult.Success(v2, _)) => v1 == v2
      case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
      case _ => false
    }
  }

  // Associativity: m.flatMap(f).flatMap(g) == m.flatMap(x => f(x).flatMap(g))
  property("associativity") = Prop.forAll {
    (m: PResult[String, Int], f: Int => PResult[String, Int], g: Int => PResult[String, Int]) =>

    val left = M.flatMap(M.flatMap(m)(f))(g)
    val right = M.flatMap(m)((x: Int) => M.flatMap(f(x))(g))

    (left, right) match {
      case (PResult.Success(v1, _), PResult.Success(v2, _)) => v1 == v2
      case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
      case _ => false
    }
  }
}

// ============================================================================
// FUNCTOR LAWS
// ============================================================================

object ResultFunctorLaws extends Properties("Result Functor Laws") {

  given Arbitrary[PResult[String, Int]] = Arbitrary {
    Gen.oneOf(
      Gen.choose(0, 100).map(n => PResult.Success(n, 0)),
      Gen.alphaNumStr.map(s => PResult.Failure(List(s), (line = 1, column = 1, offset = 0)))
    )
  }

  val F = summon[Functor[[A] =>> PResult[String, A]]]

  // Identity: map(fa)(id) == fa
  property("identity") = Prop.forAll { (fa: PResult[String, Int]) =>
    val left = F.map(fa)(x => x)

    (left, fa) match {
      case (PResult.Success(v1, _), PResult.Success(v2, _)) => v1 == v2
      case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
      case _ => false
    }
  }

  // Composition: map(map(fa)(f))(g) == map(fa)(f andThen g)
  property("composition") = Prop.forAll { (fa: PResult[String, Int]) =>
    val f: Int => Int = _ * 2
    val g: Int => Int = _ + 1

    val left = F.map(F.map(fa)(f))(g)
    val right = F.map(fa)(f.andThen(g))

    (left, right) match {
      case (PResult.Success(v1, _), PResult.Success(v2, _)) => v1 == v2
      case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
      case _ => false
    }
  }
}

// ============================================================================
// PARSER EXECUTION LAWS - Verifying parser behavior
// ============================================================================

object ParserExecutionLaws extends Properties("Parser Execution Laws") {

  // Succeed always succeeds
  property("succeed always succeeds") = Prop.forAll { (a: Int, input: String) =>
    succeed(a).run(input) match {
      case PResult.Success(value, consumed) => value == a && consumed == 0
      case PResult.Failure(_, _) => false
    }
  }

  // Fail always fails
  property("fail always fails") = Prop.forAll { (error: String, input: String) =>
    fail(error).run(input) match {
      case PResult.Success(_, _) => false
      case PResult.Failure(errors, _) => errors.contains(error)
    }
  }

  // char succeeds on matching character
  property("char succeeds on match") = Prop.forAll { (c: Char) =>
    char(c).run(c.toString) match {
      case PResult.Success(value, consumed) => value == c && consumed == 1
      case PResult.Failure(_, _) => false
    }
  }

  // char fails on non-matching character
  property("char fails on mismatch") = Prop.forAll { (c1: Char, c2: Char) =>
    (c1 != c2) ==> {
      char(c1).run(c2.toString) match {
        case PResult.Success(_, _) => false
        case PResult.Failure(_, _) => true
      }
    }
  }

  // string succeeds on matching string
  property("string succeeds on match") = Prop.forAll { (s: String) =>
    s.nonEmpty ==> {
      string(s).run(s) match {
        case PResult.Success(value, consumed) => value == s && consumed == s.length
        case PResult.Failure(_, _) => false
      }
    }
  }

  // or tries alternatives
  property("or tries both alternatives") = Prop.forAll { (s: String) =>
    s.nonEmpty ==> {
      val p = char('a') | char(s.head)
      p.run(s) match {
        case PResult.Success(value, _) => value == s.head
        case PResult.Failure(_, _) => s.head != 'a'
      }
    }
  }

  // many accumulates results
  property("many accumulates zero or more") = {
    val input = "aaab"
    char('a').many.run(input) match {
      case PResult.Success(value, consumed) => value == List('a', 'a', 'a') && consumed == 3
      case PResult.Failure(_, _) => false
    }
  }

  // many1 requires at least one
  property("many1 requires at least one") = {
    val success = char('a').many1.run("aaa") match {
      case PResult.Success(value, _) => value == List('a', 'a', 'a')
      case PResult.Failure(_, _) => false
    }

    val failure = char('a').many1.run("bbb") match {
      case PResult.Success(_, _) => false
      case PResult.Failure(_, _) => true
    }

    success && failure
  }

  // map transforms result
  property("map transforms result") = Prop.forAll { (n: Int) =>
    (n >= 0 && n <= 9) ==> {
      digit.map(_.toString.toInt).run(n.toString) match {
        case PResult.Success(value, _) => value == n
        case PResult.Failure(_, _) => false
      }
    }
  }

  // flatMap sequences parsers
  property("flatMap sequences parsers") = {
    val p = for {
      c <- char('a')
      d <- char('b')
    } yield s"$c$d"

    p.run("ab") match {
      case PResult.Success(value, consumed) => value == "ab" && consumed == 2
      case PResult.Failure(_, _) => false
    }
  }

  // optional always succeeds
  property("optional always succeeds") = Prop.forAll { (input: String) =>
    char('a').optional.run(input) match {
      case PResult.Success(Some('a'), 1) => input.headOption.contains('a')
      case PResult.Success(None, 0) => input.headOption.forall(_ != 'a')
      case _ => false
    }
  }
}

// ============================================================================
// COMBINATOR LAWS - Algebraic properties
// ============================================================================

object CombinatorLaws extends Properties("Combinator Laws") {

  // or is associative: (a | b) | c == a | (b | c)
  property("or is associative") = {
    val p1 = (char('a') | char('b')) | char('c')
    val p2 = char('a') | (char('b') | char('c'))

    val inputs = List("a", "b", "c", "d")
    inputs.forall { input =>
      val r1 = p1.run(input)
      val r2 = p2.run(input)

      (r1, r2) match {
        case (PResult.Success(v1, c1), PResult.Success(v2, c2)) => v1 == v2 && c1 == c2
        case (PResult.Failure(_, _), PResult.Failure(_, _)) => true
        case _ => false
      }
    }
  }

  // many is greedy
  property("many is greedy") = {
    val input = "aaaa"
    char('a').many.run(input) match {
      case PResult.Success(value, consumed) => {
        value.length == 4 && consumed == 4
      }
      case PResult.Failure(_, _) => false
    }
  }

  // sepBy with zero elements
  property("sepBy accepts empty") = {
    digit.sepBy(char(',')).run("") match {
      case PResult.Success(value, consumed) => value.isEmpty && consumed == 0
      case PResult.Failure(_, _) => false
    }
  }

  // sepBy with elements
  property("sepBy parses separated elements") = {
    digit.sepBy(char(',')).run("1,2,3") match {
      case PResult.Success(value, consumed) => {
        value == List('1', '2', '3') && consumed == 5
      }
      case PResult.Failure(_, _) => false
    }
  }

  // zipLeft discards right
  property("zipLeft discards right") = {
    val p = char('a') <* char('b')
    p.run("ab") match {
      case PResult.Success(value, consumed) => value == 'a' && consumed == 2
      case PResult.Failure(_, _) => false
    }
  }

  // zipRight discards left
  property("zipRight discards left") = {
    val p = char('a') *> char('b')
    p.run("ab") match {
      case PResult.Success(value, consumed) => value == 'b' && consumed == 2
      case PResult.Failure(_, _) => false
    }
  }

  // zip combines both
  property("zip combines both") = {
    val p = char('a') ~ char('b')
    p.run("ab") match {
      case PResult.Success(value, consumed) => value == ('a', 'b') && consumed == 2
      case PResult.Failure(_, _) => false
    }
  }
}
