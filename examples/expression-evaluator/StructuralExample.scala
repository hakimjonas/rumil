//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:1.0.0-alpha"

package examples.expressionevaluator

import parser.core.*
import parser.syntax.*

/**
 * Example: Expression Evaluator (Direct Evaluation Approach)
 *
 * This example parses arithmetic expressions directly to Int values,
 * evaluating => it parses. No intermediate AST is built.
 *
 * Grammar:
 *   expr   ::= term (('+' | '-') term)*
 *   term   ::= factor (('*' | '/') factor)*
 *   factor ::= number | '(' expr ')'
 *   number ::= [0-9]+
 *
 * Examples:
 *   "2+3*4"   → 14  (parsed => 2 + (3*4))
 *   "(2+3)*4" → 20  (parsed as (2+3) * 4)
 *   "10-2-3"  → 5   (parsed as (10-2) - 3, left-associative)
 */
@main def structuralExpressionExample(): Unit = {
  println("=== Expression Evaluator (Direct Evaluation) ===\n")

  // Forward declarations for recursive grammar using defer
  lazy val expr: Parser[ParseError, Int] =
    defer(term).chainl1(
      char('+').as((a: Int, b: Int) as a + b) |
      char('-').as((a: Int, b: Int) as a - b)
    )

  lazy val term: Parser[ParseError, Int] =
    defer(factor).chainl1(
      char('*').as((a: Int, b: Int) as a * b) |
      char('/').as((a: Int, b: Int) as a / b)
    )

  lazy val factor: Parser[ParseError, Int] = {
    val number = digit.many1.map(_.mkString.toInt)
    number | (char('(') *> defer(expr) <* char(')'))
  }

  // Test cases
  val testCases = List(
    ("2+3*4", 14, "Multiplication has higher precedence"),
    ("(2+3)*4", 20, "Parentheses override precedence"),
    ("10-2-3", 5, "Left-associative subtraction: (10-2)-3"),
    ("2*3+4*5", 26, "Multiple operators: (2*3)+(4*5)"),
    ("(1+2)*(3+4)", 21, "Nested parentheses"),
    ("100/10/2", 5, "Left-associative division: (100/10)/2"),
    ("42", 42, "Single number"),
    ("(((5)))", 5, "Multiple nested parentheses"),
  )

  testCases.foreach { case (input, expected, description) =>
    expr.run(input) match {
      case Result.Success(result, _) =>
        val status = if result == expected then "✓" else "✗"
        println(s"$status '$input' = $result  ($description)")
        if result != expected then {
          println(s"   Expected: $expected, Got: $result")
        }

      case Result.Failure(errors, _) =>
        println(s"✗ '$input' failed: $errors")

      case Result.Partial(result, errors, _) =>
        println(s"⚠ '$input' = $result (partial, errors: $errors)")
    }
  }

  // Example: Handling errors
  println("\n--- Error Handling ---")

  val invalidInputs = List(
    "2+",      // Incomplete expression
    "*3",      // Missing left operand
    "2 3",     // Missing operator
    "(2+3",    // Unclosed parenthesis
  )

  invalidInputs.foreach { input =>
    expr.run(input) match {
      case Result.Success(result, consumed) =>
        println(s"'$input' → $result (consumed $consumed chars)")
      case Result.Failure(errors, location) =>
        println(s"✗ '$input' failed at ${location}: $errors")
      case Result.Partial(result, errors, _) =>
        println(s"⚠ '$input' → $result (partial: $errors)")
    }
  }

  // Example: Interactive evaluation
  println("\n--- Interactive Evaluation ---")
  println("Enter expressions to evaluate (or press Ctrl+C to exit):")
  println("Examples: 2+3, (5-2)*4, 100/10/2\n")

  // Note: In a real REPL, you'd use scala.io.StdIn.readLine()
  // For this example, we'll just show how it would work
  val sampleInputs = List("2+3", "10*5", "(7-2)*3")

  sampleInputs.foreach { input =>
    print(s"> $input\n")
    expr.run(input) match {
      case Result.Success(result, _) =>
        println(s"= $result\n")
      case Result.Failure(errors, _) =>
        println(s"Error: $errors\n")
      case Result.Partial(result, errors, _) =>
        println(s"= $result (warnings: $errors)\n")
    }
  }
}
