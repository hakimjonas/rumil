# Rumil

A pure functional parser combinator library for Scala 3.

## Overview

Rumil is a parser combinator library for Scala 3, designed for correctness, efficiency, and ease of use. Parsers are pure, immutable descriptions that can be combined using composable operators to handle complex grammars.

## Features

- Parsers are immutable, composable values
- 40+ combinators for building complex parsers
- Error tracking with line, column, and offset information
- Tail-recursive interpreter
- Monadic interface with for-comprehension support
- Type-safe parsing with compile-time guarantees

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "com.example" %% "rumil" % "0.1.0"
```

## Quick Start

```scala
import parser.syntax.*
import parser.core.*

// Parse a simple number
val number = digit.many1.map(_.mkString.toInt)
number.run("42")  // Success(42, 2)

// Parse arithmetic expressions
val expr = number.chainl1(char('+').as((a: Int, b: Int) => a + b))
expr.run("1+2+3")  // Success(6, 5)

// Combine parsers with operators
val pair = char('(') *> number ~ (char(',') *> number) <* char(')')
pair.run("(1,2)")  // Success((1, 2), 7)
```

## Core Concepts

### Parsers

A `Parser[E, A]` consumes input and either:
- Succeeds with a value of type `A`
- Fails with an error of type `E`

Parsers are pure descriptions - they don't execute until you call `.run(input)`.

### Results

Running a parser produces a `Result[E, A]`:

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Failure(errors: List[E], furthest: Location)
}
```

### Combinators

Build complex parsers from simple ones:

```scala
// Sequence two parsers
val ab = char('a') ~ char('b')  // Parser[(Char, Char)]

// Alternative parsers
val aOrB = char('a') | char('b')  // Parser[Char]

// Repetition
val many = char('a').many   // Parser[List[Char]]
val some = char('a').many1  // Parser[List[Char]] (at least one)

// Transform results
val upper = letter.map(_.toUpper)  // Parser[Char]

// Sequence with for-comprehension
val parser = for {
  a <- char('a')
  b <- char('b')
} yield (a, b)
```

## Examples

### JSON Parser

```scala
import parser.syntax.*
import parser.core.*

enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

val jsonNull = string("null").as(JsonValue.Null)
val jsonBool =
  string("true").as(JsonValue.Bool(true)) |
  string("false").as(JsonValue.Bool(false))

val jsonNumber = digit.many1.map { chars =>
  JsonValue.Number(chars.mkString.toDouble)
}

val jsonString =
  (char('"') *> satisfy(_ != '"', "string char").many <* char('"'))
    .map(chars => JsonValue.Str(chars.mkString))
```

### Arithmetic with Precedence

```scala
import parser.syntax.*
import parser.core.*

lazy val expr: Parser[ParseError, Int] = {
  Parser.Custom { state =>
    parser.runtime.interpret(
      term.chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
        (char('-').as((a: Int, b: Int) => a - b))
      ),
      state
    )
  }
}

lazy val term: Parser[ParseError, Int] = {
  Parser.Custom { state =>
    parser.runtime.interpret(
      factor.chainl1(
        (char('*').as((a: Int, b: Int) => a * b)) |
        (char('/').as((a: Int, b: Int) => a / b))
      ),
      state
    )
  }
}

lazy val factor: Parser[ParseError, Int] = {
  val number = digit.many1.map(_.mkString.toInt)
  number | Parser.Custom { state =>
    parser.runtime.interpret(char('(') *> expr <* char(')'), state)
  }
}

// Correctly handles precedence
expr.run("2+3*4")    // Success(14, 5)   // 2 + (3*4)
expr.run("(2+3)*4")  // Success(20, 7)   // (2+3) * 4
```

### CSV Parser

```scala
import parser.syntax.*
import parser.core.*

val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.sepBy(char(','))
val csv = row.endBy(char('\n'))

val input = """name,age,city
alice,30,nyc
bob,25,sf
"""

csv.run(input)
// Success(
//   List(
//     List("name", "age", "city"),
//     List("alice", "30", "nyc"),
//     List("bob", "25", "sf")
//   ),
//   consumed
// )
```

## API Reference

### Primitive Parsers

| Function | Description |
|----------|-------------|
| `char(c)` | Matches a specific character |
| `string(s)` | Matches an exact string |
| `satisfy(pred, expected)` | Matches characters satisfying a predicate |
| `digit` | Matches 0-9 |
| `letter` | Matches a-z, A-Z |
| `alphaNum` | Matches letters or digits |
| `whitespace` | Matches whitespace characters |
| `anyChar` | Matches any single character |
| `eof` | Succeeds only at end of input |

### Combinators

| Combinator | Description |
|------------|-------------|
| `p1 ~ p2` | Sequence: parse p1 then p2, return both |
| `p1 <* p2` | Parse both, keep left result |
| `p1 *> p2` | Parse both, keep right result |
| `p1 \| p2` | Alternative: try p1, if it fails try p2 |
| `p.map(f)` | Transform parser result |
| `p.flatMap(f)` | Monadic sequencing |
| `p.many` | Zero or more repetitions |
| `p.many1` | One or more repetitions |
| `p.optional` | Zero or one occurrence |
| `p.sepBy(sep)` | Parse p separated by sep |
| `p.sepBy1(sep)` | Parse p separated by sep (at least one) |
| `p.endBy(end)` | Parse p terminated by end |
| `p.count(n)` | Exactly n repetitions |
| `p.chainl1(op)` | Left-associative operator chain |
| `p.chainr1(op)` | Right-associative operator chain |

### Error Handling

| Function | Description |
|----------|-------------|
| `p.attempt` | Capture result as value (never fails) |
| `p.recover(f)` | Provide fallback value on failure |
| `p.recoverWith(f)` | Provide fallback parser on failure |
| `p.named(name)` | Label parser for better error messages |
| `lookAhead(p)` | Parse without consuming input |
| `notFollowedBy(p)` | Succeed only if p fails |

## Performance

Implementation characteristics:

- Tail-recursive to prevent stack overflow
- State snapshots for backtracking
- String slicing for substring operations

Benchmark results (100 iterations):
- Parse 1000 digits: ~20ms
- Parse 100 separated numbers: ~25ms
- Arithmetic expression: ~15ms
- Deeply nested structures: ~5ms

## Testing

The library includes:
- Property-based tests verifying monad and functor laws
- Example parsers (JSON, arithmetic, CSV)
- Performance benchmarks

Run tests:
```bash
sbt test
```

## Requirements

- Scala 3.7.4 or later
- Java 11 or later (Java 25 recommended)

## License

[Your License Here]

## Contributing

Contributions are welcome. Please:
1. Ensure all tests pass
2. Add tests for new features
3. Follow the existing code style
4. Update documentation

## Acknowledgments

Inspired by parser combinator libraries including Parsec, Megaparsec, and Cats-Parse.
