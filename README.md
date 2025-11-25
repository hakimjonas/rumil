# Rumil

[![CI](https://github.com/hakimjonas/rumil/workflows/CI/badge.svg)](https://github.com/hakimjonas/rumil/actions)
[![codecov](https://codecov.io/gh/hakimjonas/rumil/branch/main/graph/badge.svg)](https://codecov.io/gh/hakimjonas/rumil)
[![Maven Central](https://img.shields.io/maven-central/v/net.ghoula/rumil-core_3.svg)](https://maven-badges.herokuapp.com/maven-central/net.ghoula/rumil-core_3)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A functional parser combinator library for Scala 3.

## Overview

Rumil is a parser combinator library for Scala 3, designed for correctness, efficiency, and ease of use. Parsers are pure, immutable descriptions that can be combined using composable operators to handle complex grammars.

## Features

- Parsers are immutable, composable values
- 40+ combinators for building complex parsers
- **Left Recursion Support** - Write natural grammars via `rule` combinator (seed-growth algorithm)
- Error tracking with line, column, and offset information
- Tail-recursive interpreter with memoization
- Monadic interface with for-comprehension support
- Type-safe parsing with compile-time guarantees

## Installation

Add to your `build.sbt`:

```scala
// Core parser combinators (required)
libraryDependencies += "net.ghoula" %% "rumil-core" % "0.2.0"

// Interop layer with Decoder typeclass (optional, for case class derivation)
libraryDependencies += "net.ghoula" %% "rumil-interop" % "0.2.0"

// JSON parser (optional, included for convenience)
libraryDependencies += "net.ghoula" %% "rumil-parsers" % "0.2.0"
```

Or with scala-cli:

```scala
//> using dep "net.ghoula::rumil-core:0.2.0"
//> using dep "net.ghoula::rumil-interop:0.2.0"
//> using dep "net.ghoula::rumil-parsers:0.2.0"
```

## Philosophy: Two Paths to Parsing

Rumil offers two complementary approaches:

### The Structural Way

Pure combinators with named tuples and enums. Maximum control and portability.

- **Use when:** Building language tooling, need lossless trees, maximum type safety
- **Style:** Explicit, composable, portable

### The Idiomatic Way

Automatic derivation for Scala case classes. Maximum convenience.

- **Use when:** Standard data parsing, REST APIs, configuration files
- **Style:** Ergonomic, concise, Scala-friendly

Both approaches use the same core library - choose based on your needs.

## Quick Start

### The Structural Way

```scala
import parser.core._
import parser.syntax._

// Parse a simple number
val number = digit.manyNonEmpty.map(_.mkString.toInt)
number.run("42")  // Success(42, 2)

// Parse arithmetic expressions
val expr = number.chainLeft1(char('+').as((a: Int, b: Int) => a + b))
expr.run("1+2+3")  // Success(6, 5)

// Combine parsers with operators
val pair = char('(') *> number ~ (char(',') *> number) <* char(')')
pair.run("(1,2)")  // Success((1, 2), 7)

// Left-recursive grammars
lazy val expr: Parser[ParseError, Expr] = rule {
  (expr ~ char('+') ~ term).map { case ((l, _), r) => Add(l, r) } |
  term
}
```

### The Idiomatic Way

```scala
import parser.core._
import parser.interop.Decoder
import parser.interop.JsonDecoders.given
import parsers.json.{JsonParser, JsonValue}

// Parse JSON to case classes with automatic derivation
case class User(name: String, age: Int, admin: Boolean)
given Decoder[JsonValue, User] = Decoder.derived

val input = """{"name": "Alice", "age": 30, "admin": true}"""

// Parse JSON string to JsonValue
val jsonResult = JsonParser.parseValue.run(input)

// Decode JsonValue to case class
val userResult = jsonResult.flatMap(json =>
  Decoder[JsonValue, User].decode(json)
)
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
  case Partial(value: A, errors: List[E], consumed: Int)  // For resilient parsing
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
val many = char('a').many           // Parser[List[Char]]
val some = char('a').manyNonEmpty   // Parser[List[Char]] (at least one)

// Transform results
val upper = letter.map(_.toUpper)  // Parser[Char]

// Sequence with for-comprehension
val parser = for {
  a <- char('a')
  b <- char('b')
} yield (a, b)
```

## Examples

### Parsing JSON

#### The Structural Way

Explicit control using combinators and named tuples:

```scala
import parser.core._
import parser.syntax._

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

val jsonNumber = digit.manyNonEmpty.map { chars =>
  JsonValue.Number(chars.mkString.toDouble)
}

val jsonString =
  (char('"') *> satisfy(_ != '"', "string char").many <* char('"'))
    .map(chars => JsonValue.Str(chars.mkString))
```

**When to use:** Building JSON tools, need custom representations, maximum control

#### The Idiomatic Way

Automatic parsing to case classes:

```scala
import parser.core._
import parser.interop.Decoder
import parser.interop.JsonDecoders.given
import parsers.json.{JsonParser, JsonValue}

case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived

val input = """{"name": "Alice", "age": 30}"""
val jsonResult = JsonParser.parseValue.run(input)
val personResult = jsonResult.flatMap(json =>
  Decoder[JsonValue, Person].decode(json)
)

// With nested structures
case class Address(street: String, city: String, zip: String)
case class User(name: String, email: String, address: Address)

given Decoder[JsonValue, Address] = Decoder.derived
given Decoder[JsonValue, User] = Decoder.derived

val nestedInput = """{"name": "Bob", "email": "bob@example.com", "address": {"street": "123 Main St", "city": "Springfield", "zip": "12345"}}"""
val nestedJsonResult = JsonParser.parseValue.run(nestedInput)
val userResult = nestedJsonResult.flatMap(json =>
  Decoder[JsonValue, User].decode(json)
)
```

**When to use:** REST APIs, configuration parsing, standard CRUD

### Arithmetic with Precedence

#### The Structural Way

```scala
import parser.core._
import parser.syntax._

// Direct left recursion - "just works" with rule combinator!
lazy val expr: Parser[ParseError, Int] = rule {
  val addSub = for {
    left  <- expr
    op    <- char('+') | char('-')
    right <- term
  } yield if (op == '+') left + right else left - right

  addSub | term
}

lazy val term: Parser[ParseError, Int] = rule {
  val mulDiv = for {
    left  <- term
    op    <- char('*') | char('/')
    right <- factor
  } yield if (op == '*') left * right else left / right

  mulDiv | factor
}

lazy val factor: Parser[ParseError, Int] = {
  val number = digit.manyNonEmpty.map(_.mkString.toInt)
  val parens = char('(') *> defer(expr) <* char(')')
  number | parens
}

// Correctly handles precedence and left-associativity
expr.run("2+3*4")   // Success(14, 5)   // 2 + (3*4)
expr.run("(2+3)*4") // Success(20, 7)   // (2+3) * 4
expr.run("5-3-1")   // Success(1, 5)    // (5-3) - 1, not 5 - (3-1)
```

**When to use:** Building language parsers, need custom AST representation

#### The Idiomatic Way

```scala
import parser.core._
import parser.syntax._

// For now, use the structural approach for expression parsing
// Automatic derivation for expression ASTs is planned for a future release

// See the structural example above for a working implementation
```

**When to use:** Calculator apps, expression evaluators (use structural approach for now)

### CSV Parser

#### The Structural Way

```scala
import parser.core._
import parser.syntax._

val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.separatedBy(char(','))
val csv = row.endedBy(char('\n'))

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

**When to use:** Custom CSV dialects, need raw string data

#### The Idiomatic Way

```scala
// CSV parsing with automatic case class derivation is planned for a future release
// For now, use the structural approach shown above to parse CSV files
// Then manually map the parsed data to case classes

import parser.core._
import parser.syntax._

case class Person(name: String, age: Int, city: String)

// Parse using structural approach
val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.separatedBy(char(','))
val csv = row.endedBy(char('\n'))

// Then map to case classes
val result = csv.run(input).map { rows =>
  rows.tail.map { row =>
    Person(row(0), row(1).toInt, row(2))
  }
}
```

**When to use:** Standard CSV files, database imports, data analysis

### Customizing Field Mapping

The `FieldTransformer` trait provides an extension point for customizing how case class fields map to source data fields. Rumil includes several built-in transformers for common naming conventions:

```scala
import parser.interop.{FieldTransformer, FieldTransformers}

// Built-in transformers for naming conventions
FieldTransformers.SnakeCase         // camelCase → snake_case
FieldTransformers.KebabCase         // camelCase → kebab-case
FieldTransformers.ScreamingSnakeCase  // camelCase → SCREAMING_SNAKE_CASE

// Custom transformer example
val customTransformer = new FieldTransformer {
  def transformFieldName(fieldName: String): String =
    s"api_$fieldName"  // Add prefix to all fields

  def shouldIncludeField(fieldName: String): Boolean =
    !fieldName.startsWith("internal")  // Skip internal fields
}

// Example: Converting names
FieldTransformers.SnakeCase.transformFieldName("userName")  // "user_name"
FieldTransformers.KebabCase.transformFieldName("isAdmin")   // "is-admin"
```

**Why FieldTransformer instead of baked-in annotations?**

Rumil is a parser combinator library, not a serialization framework. FieldTransformer provides:
- ✅ Clean extension point for customization
- ✅ Simple to implement (just two methods)
- ✅ No commitment to specific annotation APIs
- ✅ Users control their own field mapping logic

Example annotations (`@Rename`, `@Ignore`, `@Aliases`) are available in `parser.interop.examples` as templates showing how to implement annotation-based transformers using Scala 3 inline metaprogramming. Similar to the `parsers` module: we provide examples, not prescriptive APIs.

## Choosing an Approach

| Requirement | Recommended Approach |
|-------------|---------------------|
| Parsing JSON to case classes | Idiomatic (Decoder.derived) |
| Building language tools | Structural |
| Lossless syntax trees | Structural |
| REST API parsing | Idiomatic (Decoder.derived) |
| Custom data structures | Structural |
| Maximum type safety | Structural |
| Error recovery / resilient parsing | Structural |
| Debugging parsers | Both (debug combinators work everywhere) |
| IDE tooling / lossless trees | Structural |

## API Reference

### Primitive Parsers

| Function                  | Description                               |
|---------------------------|-------------------------------------------|
| `char(c)`                 | Matches a specific character              |
| `string(s)`               | Matches an exact string                   |
| `satisfy(pred, expected)` | Matches characters satisfying a predicate |
| `digit`                   | Matches 0-9                               |
| `letter`                  | Matches a-z, A-Z                          |
| `alphaNum`                | Matches letters or digits                 |
| `whitespace`              | Matches whitespace characters             |
| `anyChar`                 | Matches any single character              |
| `eof`                     | Succeeds only at end of input             |

### Combinators

| Combinator      | Description                             |
|-----------------|-----------------------------------------|
| `p1 ~ p2`       | Sequence: parse p1 then p2, return both |
| `p1 <* p2`      | Parse both, keep left result            |
| `p1 *> p2`      | Parse both, keep right result           |
| `p1 \| p2`      | Alternative: try p1, if it fails try p2 |
| `p.map(f)`      | Transform parser result                 |
| `p.flatMap(f)`  | Monadic sequencing                      |
| `p.many`        | Zero or more repetitions                |
| `p.manyNonEmpty`       | One or more repetitions                 |
| `p.optional`    | Zero or one occurrence                  |
| `p.separatedBy(sep)`  | Parse p separated by sep                |
| `p.separatedByNonEmpty(sep)` | Parse p separated by sep (at least one) |
| `p.endedBy(end)`  | Parse p terminated by end               |
| `p.count(n)`    | Exactly n repetitions                   |
| `p.chainLeft1(op)` | Left-associative operator chain         |
| `p.chainRight1(op)` | Right-associative operator chain        |
| `rule { p }`    | Memoized parser with left recursion support |
| `defer(p)`      | Lazy evaluation for recursive parsers   |

### Error Handling

| Function           | Description                            |
|--------------------|----------------------------------------|
| `p.attempt`        | Capture result as value (never fails)  |
| `p.recover(f)`     | Provide fallback value on failure      |
| `p.recoverWith(f)` | Provide fallback parser on failure     |
| `p.orElse(fallback)` | Try fallback on failure (preserves errors) |
| `p.expect(msg)`    | Replace errors with custom message     |
| `p.named(name)`    | Label parser for better error messages |
| `lookAhead(p)`     | Parse without consuming input          |
| `notFollowedBy(p)` | Succeed only if p fails                |

### Debugging

| Function        | Description                                    |
|-----------------|------------------------------------------------|
| `p.trace(name)` | Print trace messages showing parse progress    |
| `p.debug(name)` | Print debug messages with parsed values/errors |

The debugging combinators help you understand parser behavior during development:

```scala
import parser.core._
import parser.syntax._

// Trace shows parse attempts and consumption
val number = digit.manyNonEmpty.trace("number").map(_.mkString.toInt)
number.run("42")
// [TRACE] number: trying at offset 0
// [TRACE] number: success, consumed 2 chars

// Debug shows actual parsed values
val expr = (number ~ char('+') ~ number).debug("expression")
expr.run("1+2")
// [DEBUG] expression: trying at offset 0
// [DEBUG] expression: success, parsed ((1,+),2)

// Combine multiple debug points
val complex = char('(').trace("open") *>
  number.debug("left") ~
  char(',').trace("comma") *>
  number.debug("right") <*
  char(')').trace("close")
complex.run("(5,3)")
// [TRACE] open: trying at offset 0
// [TRACE] open: success, consumed 1 chars
// [DEBUG] left: trying at offset 1
// [DEBUG] left: success, parsed 5
// [TRACE] comma: trying at offset 2
// [TRACE] comma: success, consumed 1 chars
// [DEBUG] right: trying at offset 3
// [DEBUG] right: success, parsed 3
// [TRACE] close: trying at offset 4
// [TRACE] close: success, consumed 1 chars
```

Note: Debug output goes to stderr, keeping it separate from normal program output.

## Performance

### Philosophy: Allocation Efficiency Over Raw Speed

Rumil prioritizes **garbage-free parsing** for backtracking-heavy grammars:

**Key Optimizations:**
- **Lazy Error Construction**: Defers error materialization until needed
  - Failed backtracking branches never allocate error objects
  - Significant GC pressure reduction in ambiguous grammars
- **Stack Safety**: Handles 10,000+ sequential parsers without stack overflow
- **Memoization**: Supports both `.memoize` (fast caching) and `rule` (left-recursion)

**Performance Characteristics:**
- **Throughput**: 1.8-3.5x slower than cats-parse (November 2025 benchmarks)
- **Allocation**: Minimal GC pressure during backtracking (lazy errors)
- **Latency**: Better p99 latency in backtracking scenarios (less GC)
- **Memory**: Stable memory usage, no allocation spikes on failed branches

**Benchmark Results (vs cats-parse, JMH):**
| Benchmark | cats-parse | Rumil | Ratio |
|-----------|------------|-------|-------|
| choice (10-way) | 177k ops/ms | 100k ops/ms | 1.8x |
| parseCommaSep100 | 417 ops/ms | 150 ops/ms | 2.8x |
| parseDigits1000 | 423 ops/ms | 122 ops/ms | 3.5x |
| stringMatch | 301k ops/ms | 130k ops/ms | 2.3x |

**When to Use Rumil:**
- Grammars with heavy backtracking (ambiguous or complex rules)
- Long-running parsers where GC pauses matter
- Left-recursive grammars (natural expression syntax)
- Debuggability and error quality are priorities

**When to Use Alternatives:**
- Pure throughput on simple grammars → cats-parse
- Maximum speed with macro compilation → fastparse
- Standard parsing without special requirements → any parser combinator

See `docs/memoization-performance-analysis.md` for detailed benchmarks.

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

MIT License

## Contributing

Contributions are welcome. Please:
1. Ensure all tests pass
2. Add tests for new features
3. Follow the existing code style
4. Update documentation

## Acknowledgments

Inspired by parser combinator libraries including Parsec, Megaparsec, and Cats-Parse.
