//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:0.2.0"

package examples.expressionevaluator

import parser.core._
import parser.syntax._

/**
 * Example: Expression Evaluator (AST Building Approach)
 *
 * This example parses arithmetic expressions into an Abstract Syntax Tree (AST),
 * then evaluates the AST. This two-stage approach is more flexible and idiomatic.
 *
 * Benefits:
 * - Separate parsing from evaluation
 * - Can optimize the AST before evaluation
 * - Can pretty-print, analyze, or transform expressions
 * - Better error reporting (can point to specific AST nodes)
 * - More testable (can test parsing and evaluation separately)
 */
@main def idiomaticExpressionExample(): Unit = {
  println("=== Expression Evaluator (AST Building) ===\n")

  // Define AST using Scala 3 enums
  enum Expr {
    case Num(value: Int)
    case Add(left: Expr, right: Expr)
    case Sub(left: Expr, right: Expr)
    case Mul(left: Expr, right: Expr)
    case Div(left: Expr, right: Expr)
  }

  import Expr._

  // Parsers that build the AST using defer for recursion
  lazy val expr: Parser[ParseError, Expr] =
    defer(term).chainl1(
      char('+').as((a: Expr, b: Expr) => Add(a, b)) |
      char('-').as((a: Expr, b: Expr) => Sub(a, b))
    )

  lazy val term: Parser[ParseError, Expr] =
    defer(factor).chainl1(
      char('*').as((a: Expr, b: Expr) => Mul(a, b)) |
      char('/').as((a: Expr, b: Expr) => Div(a, b))
    )

  lazy val factor: Parser[ParseError, Expr] = {
    val number = digit.many1.map(digits => Num(digits.mkString.toInt))
    number | (char('(') *> defer(expr) <* char(')'))
  }

  // Evaluator: Traverse the AST and compute the result
  def eval(expr: Expr): Int = expr match {
    case Num(value) => value
    case Add(left, right) => eval(left) + eval(right)
    case Sub(left, right) => eval(left) - eval(right)
    case Mul(left, right) => eval(left) * eval(right)
    case Div(left, right) => eval(left) / eval(right)
  }

  // Pretty printer: Convert AST back to string
  def prettyPrint(expr: Expr): String = expr match {
    case Num(value) => value.toString
    case Add(left, right) => s"(${prettyPrint(left)} + ${prettyPrint(right)})"
    case Sub(left, right) => s"(${prettyPrint(left)} - ${prettyPrint(right)})"
    case Mul(left, right) => s"(${prettyPrint(left)} * ${prettyPrint(right)})"
    case Div(left, right) => s"(${prettyPrint(left)} / ${prettyPrint(right)})"
  }

  // Optimizer: Constant folding
  def optimize(expr: Expr): Expr = expr match {
    case Add(Num(a), Num(b)) => Num(a + b)
    case Sub(Num(a), Num(b)) => Num(a - b)
    case Mul(Num(a), Num(b)) => Num(a * b)
    case Div(Num(a), Num(b)) => Num(a / b)
    case Add(left, right) => Add(optimize(left), optimize(right))
    case Sub(left, right) => Sub(optimize(left), optimize(right))
    case Mul(left, right) => Mul(optimize(left), optimize(right))
    case Div(left, right) => Div(optimize(left), optimize(right))
    case num @ Num(_) => num
  }

  // Test cases
  val testCases = List(
    "2+3*4",
    "(2+3)*4",
    "10-2-3",
    "2*3+4*5",
  )

  testCases.foreach { input =>
    println(s"Input: '$input'")

    expr.run(input) match {
      case Result.Success(ast, _) =>
        println(s"  AST:        $ast")
        println(s"  Pretty:     ${prettyPrint(ast)}")

        val optimized = optimize(ast)
        if (optimized != ast) {
          println(s"  Optimized:  $optimized")
        }

        val result = eval(ast)
        println(s"  Result:     $result")

      case Result.Failure(errors, _) =>
        println(s"  Error: $errors")

      case Result.Partial(ast, errors, _) =>
        println(s"  AST:    $ast (partial)")
        println(s"  Errors: $errors")
    }

    println()
  }

  // Example: Constant folding optimization
  println("--- Constant Folding Optimization ---")

  val complexInput = "1+2+3+4"
  expr.run(complexInput) match {
    case Result.Success(ast, _) =>
      println(s"Input:      '$complexInput'")
      println(s"Original:   $ast")
      println(s"Pretty:     ${prettyPrint(ast)}")
      println(s"Optimized:  ${optimize(ast)}")
      println(s"Result:     ${eval(ast)}")

    case _ => ()
  }

  println("\n--- AST Inspection ---")

  // You can analyze the AST structure
  def countOperations(expr: Expr): Int = expr match {
    case Num(_) => 0
    case Add(left, right) => 1 + countOperations(left) + countOperations(right)
    case Sub(left, right) => 1 + countOperations(left) + countOperations(right)
    case Mul(left, right) => 1 + countOperations(left) + countOperations(right)
    case Div(left, right) => 1 + countOperations(left) + countOperations(right)
  }

  def maxDepth(expr: Expr): Int = expr match {
    case Num(_) => 0
    case Add(left, right) => 1 + Math.max(maxDepth(left), maxDepth(right))
    case Sub(left, right) => 1 + Math.max(maxDepth(left), maxDepth(right))
    case Mul(left, right) => 1 + Math.max(maxDepth(left), maxDepth(right))
    case Div(left, right) => 1 + Math.max(maxDepth(left), maxDepth(right))
  }

  val inspectInput = "(1+2)*(3+4)"
  expr.run(inspectInput) match {
    case Result.Success(ast, _) =>
      println(s"Expression:  '$inspectInput'")
      println(s"AST:         $ast")
      println(s"Operations:  ${countOperations(ast)}")
      println(s"Max depth:   ${maxDepth(ast)}")
      println(s"Result:      ${eval(ast)}")

    case _ => ()
  }
}
