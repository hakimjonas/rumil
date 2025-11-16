# The Structural Approach: Pure Combinators and Maximum Control

## When to Use This Approach

✅ **Use when:**
- Building language tooling (compilers, formatters, IDEs, language servers)
- Need lossless syntax trees with all whitespace/comments preserved
- Require custom data representations
- Maximum transparency and control
- Performance-critical parsing where every byte matters

❌ **Don't use when:**
- Standard JSON/XML/CSV to case classes (use Idiomatic approach)
- Quick prototyping where boilerplate is annoying
- REST API clients (use Idiomatic approach)

## Philosophy: Structural-First Design

Rumil's core philosophy is **Structural-First Design**:

> Parse structured data into explicit, composable data structures before converting to domain models.

This approach:
- Separates **syntax** (structure) from **semantics** (meaning)
- Enables **lossless round-tripping** (parse → modify → print)
- Provides **transparent control** over every parsing decision
- Facilitates **tooling** (formatters, linters, refactoring tools)

## Core Combinators

### Sequencing

```scala
// Parse A then B, return both
val ab = char('a') ~ char('b')  // Parser[(Char, Char)]

// Parse both, keep left
val left = char('a') <* char('b')  // Parser[Char]

// Parse both, keep right
val right = char('a') *> char('b')  // Parser[Char]
```

### Alternatives

```scala
// Try A, if fails try B
val aOrB = char('a') | char('b')

// With multiple alternatives
val vowel = char('a') | char('e') | char('i') | char('o') | char('u')
```

### Repetition

```scala
// Zero or more
val manyA = char('a').many  // Parser[List[Char]]

// One or more
val someA = char('a').many1  // Parser[List[Char]]

// Exactly n times
val threeA = char('a').count(3)  // Parser[List[Char]]

// Optional (zero or one)
val optA = char('a').optional  // Parser[Option[Char]]
```

### Separated Lists

```scala
// Parse comma-separated numbers: "1,2,3"
val numbers = number.sepBy(char(','))

// At least one
val numbers1 = number.sepBy1(char(','))

// Terminated by separator: "a;b;c;"
val terminated = letter.endBy(char(';'))
```

## Example: Building a JSON Parser

This shows the full structural approach for parsing JSON:

```scala
enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

// Primitive parsers
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

// Recursive parsers
lazy val jsonValue: Parser[ParseError, JsonValue] = Parser.Custom { state =>
  parser.runtime.interpret(
    jsonNull | jsonBool | jsonNumber | jsonString | jsonArray | jsonObject,
    state
  )
}

lazy val jsonArray: Parser[ParseError, JsonValue] = Parser.Custom { state =>
  parser.runtime.interpret(
    (char('[') *> jsonValue.sepBy(char(',')) <* char(']'))
      .map(JsonValue.Array(_)),
    state
  )
}

lazy val jsonObject: Parser[ParseError, JsonValue] = Parser.Custom { state =>
  val pair = for {
    key <- jsonString
    _ <- char(':')
    value <- jsonValue
  } yield {
    key match {
      case JsonValue.Str(k) => (k, value)
    }
  }

  parser.runtime.interpret(
    (char('{') *> pair.sepBy(char(',')) <* char('}'))
      .map(pairs => JsonValue.Object(pairs.toMap)),
    state
  )
}
```

## Transformation and Extraction

Once you have the structural representation, extract domain models:

```scala
// Parse JSON to structural representation
val jsonResult: Result[ParseError, JsonValue] =
  jsonValue.run(input)

// Extract domain model
case class User(name: String, age: Int)

def extractUser(json: JsonValue): Option[User] = json match {
  case JsonValue.Object(fields) =>
    for {
      JsonValue.Str(name) <- fields.get("name")
      JsonValue.Number(age) <- fields.get("age")
    } yield User(name, age.toInt)
  case _ => None
}

val user: Option[User] = jsonResult.toOption.flatMap(extractUser)
```

## Named Tuples for Clarity

Use Scala 3 named tuples for self-documenting parsers:

```scala
// Parse HTTP request line: "GET /path HTTP/1.1"
val requestLine = for {
  method <- letter.many1.map(_.mkString)
  _ <- whitespace.many1
  path <- satisfy(_ != ' ', "path char").many1.map(_.mkString)
  _ <- whitespace.many1
  protocol <- satisfy(_ != '\n', "protocol char").many.map(_.mkString)
} yield (method = method, path = path, protocol = protocol)

// Result has type: (method: String, path: String, protocol: String)
```

## Advanced Techniques

### Operator Precedence

Use `chainl1` and `chainr1` for operator precedence:

```scala
// expr = term (('+' | '-') term)*
// term = factor (('*' | '/') factor)*
// factor = number | '(' expr ')'

lazy val expr = term.chainl1(
  char('+').as((a: Int, b: Int) => a + b) |
  char('-').as((a: Int, b: Int) => a - b)
)

lazy val term = factor.chainl1(
  char('*').as((a: Int, b: Int) => a * b) |
  char('/').as((a: Int, b: Int) => a / b)
)
```

### Lookahead and Negative Lookahead

```scala
// Lookahead: peek without consuming
val peek = lookAhead(digit)

// Negative lookahead: succeed only if pattern fails
val notFollowedByDigit = notFollowedBy(digit)
```

### Labeling for Better Errors

```scala
val number = digit.many1.map(_.mkString.toInt).named("number")
val email = (letter.many1 ~ char('@') ~ letter.many1).named("email address")
```

## Next Steps

- **[Error Handling](error-handling.md)** - Resilient parsing with recovery
- **[Debugging](debugging.md)** - Using `.trace()` and `.debug()`
- **[Examples](../examples/)** - Runnable code samples
- **[Performance Guide](performance.md)** - Optimization techniques
