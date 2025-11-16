# Getting Started with Rumil

## Learning Objectives

By the end of this guide, you will be able to:

1. Install Rumil and set up your project
2. Write your first parser
3. Understand the core concepts (Parser, Result, combinators)
4. Parse simple structured data
5. Handle errors gracefully

## Installation

### SBT

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "rumil-core" % "0.2.0",      // Core parser combinators
  "net.ghoula" %% "rumil-interop" % "0.2.0",   // Decoder typeclass (optional)
  "net.ghoula" %% "rumil-parsers" % "0.2.0"    // JSON/CSV parsers (optional)
)
```

### scala-cli

Add to your `.scala` file:

```scala
//> using dep "net.ghoula::rumil-core:0.2.0"
//> using dep "net.ghoula::rumil-interop:0.2.0"
//> using dep "net.ghoula::rumil-parsers:0.2.0"
```

### Requirements

- **Scala**: 3.7.4 or later
- **Java**: 11 or later (Java 25 recommended)

## Your First Parser

Let's start with the simplest possible parser: matching a single character.

```scala
import parser.core._
import parser.syntax._

// Match the letter 'a'
val parseA = char('a')

// Run the parser
val result = parseA.run("abc")
// Success('a', 1)  - matched 'a', consumed 1 character
```

### Understanding the Result

Running a parser produces a `Result[E, A]`:

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Failure(errors: List[E], furthest: Location)
  case Partial(value: A, errors: List[E], consumed: Int)
}
```

- **Success**: Parser succeeded, returning a value and how many characters were consumed
- **Failure**: Parser failed completely, returning a list of errors
- **Partial**: Parser succeeded but encountered errors (resilient parsing)

## Core Concepts

### 1. Parsers are Values

Parsers are immutable, composable values:

```scala
val digit = satisfy(_.isDigit, "digit")
val letter = satisfy(_.isLetter, "letter")

// These are just descriptions - nothing has executed yet!
```

### 2. Running Parsers

Execute a parser with `.run(input)`:

```scala
val result = digit.run("5")
// Success('5', 1)

val result2 = digit.run("a")
// Failure(List(ParseError(...)), ...)
```

### 3. Combining Parsers

Build complex parsers from simple ones:

```scala
// Sequence: parse A then B
val ab = char('a') ~ char('b')
ab.run("ab")  // Success(('a', 'b'), 2)

// Alternative: try A, if it fails try B
val aOrB = char('a') | char('b')
aOrB.run("b")  // Success('b', 1)

// Repetition: zero or more
val manyA = char('a').many
manyA.run("aaa")  // Success(List('a', 'a', 'a'), 3)
```

## Example: Parsing Numbers

Let's build a parser for positive integers:

```scala
import parser.core._
import parser.syntax._

// A digit is any character from '0' to '9'
val digit = satisfy(_.isDigit, "digit")

// A number is one or more digits
val number = digit.many1.map(_.mkString.toInt)

// Test it
number.run("42")     // Success(42, 2)
number.run("123")    // Success(123, 3)
number.run("abc")    // Failure(...)
```

### Breaking it Down

1. `digit` matches a single digit character
2. `.many1` means "one or more repetitions"
3. `.map(_.mkString.toInt)` transforms the list of chars into an Int

## Example: Parsing Key-Value Pairs

Let's parse simple configuration like `name=value`:

```scala
import parser.core._
import parser.syntax._

// A key is one or more letters
val key = letter.many1.map(_.mkString)

// A value is one or more non-newline characters
val value = satisfy(_ != '\n', "value char").many1.map(_.mkString)

// A pair is: key '=' value
val pair = for {
  k <- key
  _ <- char('=')
  v <- value
} yield (k, v)

// Test it
pair.run("name=Alice")
// Success(("name", "Alice"), 10)

pair.run("port=8080")
// Success(("port", "8080"), 9)
```

## Example: Parsing JSON (Simple)

Let's parse a simple JSON string:

```scala
import parser.core._
import parser.syntax._

// JSON string: "hello"
val jsonString =
  char('"') *>
  satisfy(_ != '"', "string char").many.map(_.mkString) <*
  char('"')

jsonString.run("\"hello\"")
// Success("hello", 7)
```

### Understanding the Operators

- `*>`: Parse both, keep right result (discard opening quote)
- `<*`: Parse both, keep left result (discard closing quote)

## Error Handling

Rumil provides detailed error information:

```scala
val number = digit.many1.map(_.mkString.toInt)

number.run("abc") match {
  case Result.Success(n, _) =>
    println(s"Parsed: $n")

  case Result.Failure(errors, location) =>
    println(s"Failed at line ${location.line}, col ${location.column}")
    errors.foreach(err => println(s"  - $err"))

  case Result.Partial(n, errors, _) =>
    println(s"Partially parsed: $n")
    println(s"Warnings: $errors")
}
```

## Common Patterns

### Optional Values

```scala
// Make a parser optional (returns Option[A])
val optionalSign = char('-').optional

val signedNumber = for {
  sign <- optionalSign
  digits <- digit.many1.map(_.mkString.toInt)
} yield if (sign.isDefined) -digits else digits

signedNumber.run("-42")  // Success(-42, 3)
signedNumber.run("42")   // Success(42, 2)
```

### Separated Lists

```scala
// Parse comma-separated numbers: "1,2,3"
val numbers = number.sepBy(char(','))

numbers.run("1,2,3")
// Success(List(1, 2, 3), 5)
```

### Whitespace Handling

```scala
// Skip whitespace
val ws = whitespace.many

// Parse a number surrounded by whitespace
val paddedNumber = ws *> number <* ws

paddedNumber.run("  42  ")
// Success(42, 6)
```

## Next Steps

Now that you understand the basics, explore:

- **[Structural Approach](structural-approach.md)** - Deep dive on pure combinators
- **[Idiomatic Approach](idiomatic-approach.md)** - Automatic case class derivation
- **[Error Handling](error-handling.md)** - Resilient parsing and recovery
- **[Debugging](debugging.md)** - Using `.trace()` and `.debug()`
- **[Examples](../examples/)** - Runnable example programs

## Common Mistakes

### 1. Forgetting to run the parser

```scala
// ✗ Wrong - this is just a parser value
val result = number

// ✓ Correct - run the parser on input
val result = number.run("42")
```

### 2. Not handling all Result cases

```scala
// ✗ Risky - might crash on Failure or Partial
val Success(n, _) = number.run(input)

// ✓ Safe - handle all cases
number.run(input) match {
  case Success(n, _) => ...
  case Failure(errors, _) => ...
  case Partial(n, errors, _) => ...
}
```

### 3. Infinite recursion without Parser.Custom

```scala
// ✗ Wrong - will cause stack overflow
lazy val expr: Parser[E, A] = expr | term

// ✓ Correct - use Parser.Custom for recursive parsers
lazy val expr: Parser[E, A] = Parser.Custom { state =>
  parser.runtime.interpret(expr | term, state)
}
```

## Summary

You've learned:

- ✓ How to install Rumil
- ✓ Basic parser syntax and combinators
- ✓ How to run parsers and handle results
- ✓ Common patterns (optional, lists, whitespace)
- ✓ Error handling basics

**Next**: Choose your path:
- For maximum control → [Structural Approach](structural-approach.md)
- For maximum convenience → [Idiomatic Approach](idiomatic-approach.md)
