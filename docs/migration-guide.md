# Migration Guide

## From fastparse

### Key Differences

| fastparse | Rumil |
|-----------|-------|
| `P( ... )` macro | No macro needed |
| `~/` (cut) | `.attempt` or `.recover` |
| `.!` (capture) | `.map(_.toString)` |
| `.rep` | `.many` |
| Compiled to bytecode | Interpreted |

### Common Patterns

**Sequencing**:
```scala
// fastparse
P( "GET" ~ " " ~ path )

// Rumil
string("GET") ~ char(' ') ~ path
```

**Repetition**:
```scala
// fastparse
P( digit.rep(1) )

// Rumil
digit.many1
```

**Alternatives**:
```scala
// fastparse
P( "true" | "false" )

// Rumil
string("true") | string("false")
```

## From cats-parse

### Key Differences

| cats-parse | Rumil |
|------------|-------|
| `Parser.char('a')` | `char('a')` |
| `.backtrack` | `.attempt` |
| `Parser.oneOf(...)` | `p1 | p2 | p3` |
| Pure functional (cats) | Pragmatic functional |

### Common Patterns

**Basic parsers**:
```scala
// cats-parse
import cats.parse.Parser
val digit = Parser.charIn('0' to '9')

// Rumil
val digit = satisfy(_.isDigit, "digit")
```

**Repetition**:
```scala
// cats-parse
Parser.charsWhile(_.isDigit)

// Rumil
digit.many.map(_.mkString)
```

## From Standard Library (scala.util.parsing.combinator)

### Key Differences

| scala.util.parsing | Rumil |
|-------------------|-------|
| `~` | `~` (same!) |
| `~>` | `*>` |
| `<~` | `<*` |
| `^^` | `.map` |
| Mutable state | Immutable |

### Common Patterns

**Sequencing**:
```scala
// scala.util.parsing
val pair = "(" ~> number ~ "," ~ number <~ ")"

// Rumil
val pair = char('(') *> number ~ char(',') ~ number <* char(')')
```

**Transformation**:
```scala
// scala.util.parsing
val num = digit.+ ^^ { _.mkString.toInt }

// Rumil
val num = digit.many1.map(_.mkString.toInt)
```

## Key Concepts to Learn

### 1. Result Type

Rumil uses `Result[E, A]` instead of `Option`, `Either`, or custom types:

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Failure(errors: List[E], furthest: Location)
  case Partial(value: A, errors: List[E], consumed: Int)
}
```

### 2. Running Parsers

```scala
// Don't forget to .run()!
val result = parser.run(input)
```

### 3. Recursive Parsers

Must use `Parser.Custom`:

```scala
lazy val expr = Parser.Custom { state =>
  parser.runtime.interpret(term | (expr ~ op ~ term), state)
}
```

### 4. Decoder Typeclass

For JSON/structured data, use the Decoder typeclass:

```scala
case class User(name: String, age: Int)
given Decoder[JsonValue, User] = Decoder.derived
```

## Common Pitfalls

### 1. Forgetting imports

```scala
// Need both!
import parser.core._
import parser.syntax._
```

### 2. Not handling all Result cases

```scala
// ✗ Bad
val Success(value, _) = parser.run(input)

// ✓ Good
parser.run(input) match {
  case Success(value, _) => ...
  case Failure(errors, _) => ...
  case Partial(value, errors, _) => ...
}
```

### 3. Using raw recursion

```scala
// ✗ Stack overflow
lazy val expr = expr | term

// ✓ Use Parser.Custom
lazy val expr = Parser.Custom { state =>
  parser.runtime.interpret(expr | term, state)
}
```

## See Also

- **[Getting Started](getting-started.md)** - Learn Rumil from scratch
- **[Examples](../examples/)** - Runnable example programs
- **[API Reference](../README.md#api-reference)** - Complete combinator list
