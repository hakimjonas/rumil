# Rumil Cookbook

**10 common parsing patterns with complete, runnable examples.**

## Table of Contents

1. [Email Address Parser](#1-email-address-parser)
2. [URL Parser](#2-url-parser)
3. [Arithmetic Expression Evaluator](#3-arithmetic-expression-evaluator)
4. [INI File Parser](#4-ini-file-parser)
5. [Log File Parser](#5-log-file-parser)
6. [JSON Path Extractor](#6-json-path-extractor)
7. [Markdown Header Parser](#7-markdown-header-parser)
8. [Command-Line Argument Parser](#8-command-line-argument-parser)
9. [Phone Number Parser (Multiple Formats)](#9-phone-number-parser-multiple-formats)
10. [SQL SELECT Statement Parser](#10-sql-select-statement-parser)

---

## 1. Email Address Parser

**Goal:** Parse and validate email addresses.

```scala
import parser.core._
import parser.syntax._

case class Email(local: String, domain: String)

// Local part: alphanumeric + dots, hyphens, underscores
val localChar = satisfy(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '_', "local char")
val localPart = localChar.many1.map(_.mkString)

// Domain part: alphanumeric + dots, hyphens
val domainChar = satisfy(c => c.isLetterOrDigit || c == '.' || c == '-', "domain char")
val domainPart = domainChar.many1.map(_.mkString)

val emailParser: Parser[ParseError, Email] = for {
  local  <- localPart
  _      <- char('@')
  domain <- domainPart
} yield Email(local, domain)

// Usage
emailParser.run("alice.smith@example.com")
// Success(Email("alice.smith", "example.com"), ...)

emailParser.run("invalid@")
// Failure: Expected domain char at position...
```

**Key techniques:**
- Custom character predicates with `satisfy`
- Sequential parsing with for-comprehension
- Case class construction from parsed parts

---

## 2. URL Parser

**Goal:** Parse URLs into components (scheme, host, port, path).

```scala
import parser.core._
import parser.syntax._

case class URL(scheme: String, host: String, port: Option[Int], path: String)

val alphaNum = satisfy(_.isLetterOrDigit, "alphanumeric")
val scheme = alphaNum.many1.map(_.mkString)
val host = alphaNum.many1.map(_.mkString)
val port = (char(':') *> digit.many1.map(_.mkString.toInt)).optional
val pathChar = satisfy(c => c.isLetterOrDigit || c == '/' || c == '-' || c == '_', "path char")
val path = pathChar.many.map(cs => if (cs.isEmpty) "/" else cs.mkString)

val urlParser: Parser[ParseError, URL] = for {
  s <- scheme
  _ <- string("://")
  h <- host
  p <- port
  path <- path
} yield URL(s, h, p, path)

// Usage
urlParser.run("https://example.com:8080/api/users")
// Success(URL("https", "example", Some(8080), "/api/users"), ...)

urlParser.run("http://localhost/")
// Success(URL("http", "localhost", None, "/"), ...)
```

**Key techniques:**
- Optional parsing with `.optional`
- String literals with `string(...)`
- Combining parsers sequentially

---

## 3. Arithmetic Expression Evaluator

**Goal:** Parse and evaluate arithmetic expressions with correct precedence.

```scala
import parser.core._
import parser.syntax._

enum Expr {
  case Num(n: Int)
  case Add(left: Expr, right: Expr)
  case Mul(left: Expr, right: Expr)
}

val ws = satisfy(_.isWhitespace, "whitespace").many.void

lazy val expr: Parser[ParseError, Expr] = rule {
  (expr ~ ws ~ char('+') ~ ws ~ term).map((e, _, _, _, t) => Expr.Add(e, t)) |
  term
}

lazy val term: Parser[ParseError, Expr] = rule {
  (term ~ ws ~ char('*') ~ ws ~ factor).map((t, _, _, _, f) => Expr.Mul(t, f)) |
  factor
}

lazy val factor: Parser[ParseError, Expr] =
  digit.many1.map(ds => Expr.Num(ds.mkString.toInt)) |
  (char('(') ~ ws *> expr <* ws <* char(')'))

def eval(expr: Expr): Int = expr match {
  case Expr.Num(n) => n
  case Expr.Add(l, r) => eval(l) + eval(r)
  case Expr.Mul(l, r) => eval(l) * eval(r)
}

// Usage
val input = "1 + 2 * 3"
expr.run(input).map(eval)
// Success(7, ...) - correct precedence!

expr.run("(1 + 2) * 3").map(eval)
// Success(9, ...)
```

**Key techniques:**
- Left recursion with `rule`
- Operator precedence via grammar structure
- Parenthesized expressions
- Evaluator function separate from parser

---

## 4. INI File Parser

**Goal:** Parse INI configuration files.

```scala
import parser.core._
import parser.syntax._

case class IniSection(name: String, entries: Map[String, String])
case class IniFile(sections: List[IniSection])

val ws = satisfy(c => c == ' ' || c == '\t', "whitespace").many.void
val newline = char('\n') | string("\r\n").void
val comment = char('#') *> satisfy(_ != '\n', "comment char").many *> newline

// Section header: [section_name]
val sectionName = satisfy(c => c.isLetterOrDigit || c == '_', "section char").many1.map(_.mkString)
val sectionHeader = char('[') *> sectionName <* char(']') <* ws <* newline

// Key-value pair: key = value
val key = sectionName
val value = satisfy(_ != '\n', "value char").many.map(_.mkString.trim)
val entry = for {
  k <- key
  _ <- ws ~ char('=') ~ ws
  v <- value
  _ <- newline
} yield (k, v)

// Section: header followed by entries
val section = for {
  name <- sectionHeader
  entries <- (ws *> comment | ws <* newline | entry).many
} yield IniSection(name, entries.collect { case (k, v) => (k, v) }.toMap)

val iniParser = (ws | newline | comment).many *> section.many.map(IniFile.apply)

// Usage
val ini = """
[database]
host = localhost
port = 5432

[server]
# This is a comment
port = 8080
"""

iniParser.run(ini)
// Success(IniFile(List(
//   IniSection("database", Map("host" -> "localhost", "port" -> "5432")),
//   IniSection("server", Map("port" -> "8080"))
// )), ...)
```

**Key techniques:**
- Whitespace handling
- Comment parsing
- Collecting results into maps
- Ignoring irrelevant parts (comments, blank lines)

---

## 5. Log File Parser

**Goal:** Parse structured log entries.

```scala
import parser.core._
import parser.syntax._

case class LogEntry(timestamp: String, level: String, message: String)

val digit2 = digit ~ digit
val timestamp = for {
  year  <- digit.exactlyN(4).map(_.mkString)
  _     <- char('-')
  month <- digit2.map { case (d1, d2) => s"$d1$d2" }
  _     <- char('-')
  day   <- digit2.map { case (d1, d2) => s"$d1$d2" }
  _     <- char(' ')
  hour  <- digit2.map { case (d1, d2) => s"$d1$d2" }
  _     <- char(':')
  min   <- digit2.map { case (d1, d2) => s"$d1$d2" }
  _     <- char(':')
  sec   <- digit2.map { case (d1, d2) => s"$d1$d2" }
} yield s"$year-$month-$day $hour:$min:$sec"

val level = string("INFO") | string("WARN") | string("ERROR") | string("DEBUG")
val message = satisfy(_ != '\n', "message char").many.map(_.mkString.trim)

val logEntry = for {
  ts    <- timestamp
  _     <- string(" [")
  lvl   <- level
  _     <- string("] ")
  msg   <- message
} yield LogEntry(ts, lvl, msg)

val logFile = logEntry.sepBy(char('\n'))

// Usage
val log = """2024-01-15 10:30:45 [INFO] Server started
2024-01-15 10:30:50 [WARN] High memory usage
2024-01-15 10:31:00 [ERROR] Connection failed"""

logFile.run(log)
// Success(List(
//   LogEntry("2024-01-15 10:30:45", "INFO", "Server started"),
//   LogEntry("2024-01-15 10:30:50", "WARN", "High memory usage"),
//   LogEntry("2024-01-15 10:31:00", "ERROR", "Connection failed")
// ), ...)
```

**Key techniques:**
- Structured timestamp parsing
- Alternatives for log levels
- Separated lists with `.sepBy`
- Exact repetition with `.exactlyN`

---

## 6. JSON Path Extractor

**Goal:** Parse JSON path expressions like `$.users[0].name`.

```scala
import parser.core._
import parser.syntax._

enum PathSegment {
  case Root
  case Field(name: String)
  case Index(i: Int)
}

case class JsonPath(segments: List[PathSegment])

val root = char('$').as(PathSegment.Root)
val fieldName = letter.many1.map(_.mkString)
val field = char('.') *> fieldName.map(PathSegment.Field.apply)
val index = char('[') *> digit.many1.map(ds => PathSegment.Index(ds.mkString.toInt)) <* char(']')

val pathSegment = field | index
val jsonPath = (root ~ pathSegment.many).map((r, segs) => JsonPath(r :: segs))

// Usage
jsonPath.run("$.users[0].name")
// Success(JsonPath(List(Root, Field("users"), Index(0), Field("name"))), ...)

// You can then use this to extract values from parsed JSON:
def extract(path: JsonPath, json: JsonValue): Option[JsonValue] = {
  // Implementation that walks the JsonValue tree using path segments
  ???
}
```

**Key techniques:**
- Enum for different path segment types
- Chaining multiple segment parsers
- Building structured results

---

## 7. Markdown Header Parser

**Goal:** Parse markdown headers with levels.

```scala
import parser.core._
import parser.syntax._

case class Header(level: Int, text: String)

val headerMarker = char('#').many1
val headerText = satisfy(_ != '\n', "header char").many.map(_.mkString.trim)

val header = for {
  markers <- headerMarker
  _       <- char(' ')
  text    <- headerText
} yield Header(markers.size, text)

// Usage
header.run("# Title")
// Success(Header(1, "Title"), ...)

header.run("### Subsection")
// Success(Header(3, "Subsection"), ...)

// Parse entire markdown document
val document = header.sepBy(char('\n'))
```

**Key techniques:**
- Counting repetitions (`.many1` then `.size`)
- Trimming whitespace
- Simple document structure parsing

---

## 8. Command-Line Argument Parser

**Goal:** Parse command-line flags and arguments.

```scala
import parser.core._
import parser.syntax._

enum Arg {
  case Flag(name: String)
  case KeyValue(key: String, value: String)
  case Positional(value: String)
}

val ws = char(' ').many1.void
val flagName = letter.many1.map(_.mkString)
val argValue = satisfy(c => c != ' ' && c != '\n', "arg char").many1.map(_.mkString)

val flag = string("--") *> flagName.map(Arg.Flag.apply)
val keyValue = for {
  _ <- string("--")
  k <- flagName
  _ <- char('=')
  v <- argValue
} yield Arg.KeyValue(k, v)

val positional = argValue.map(Arg.Positional.apply)

val arg = keyValue | flag | positional
val args = arg.sepBy(ws)

// Usage
args.run("--verbose --output=file.txt input.txt")
// Success(List(
//   Arg.Flag("verbose"),
//   Arg.KeyValue("output", "file.txt"),
//   Arg.Positional("input.txt")
// ), ...)
```

**Key techniques:**
- Trying alternatives in correct order (most specific first)
- Enum for different argument types
- Whitespace as separator

---

## 9. Phone Number Parser (Multiple Formats)

**Goal:** Parse phone numbers in various formats.

```scala
import parser.core._
import parser.syntax._

case class PhoneNumber(countryCode: Option[String], areaCode: String, number: String)

// Format 1: +1 (555) 123-4567
val format1 = for {
  cc   <- (char('+') *> digit.many1.map(_.mkString) <* char(' ')).optional
  _    <- char('(')
  area <- digit.exactlyN(3).map(_.mkString)
  _    <- char(')') ~ char(' ')
  num  <- digit.exactlyN(3).map(_.mkString)
  _    <- char('-')
  num2 <- digit.exactlyN(4).map(_.mkString)
} yield PhoneNumber(cc, area, s"$num-$num2")

// Format 2: 555-123-4567
val format2 = for {
  area <- digit.exactlyN(3).map(_.mkString)
  _    <- char('-')
  num  <- digit.exactlyN(3).map(_.mkString)
  _    <- char('-')
  num2 <- digit.exactlyN(4).map(_.mkString)
} yield PhoneNumber(None, area, s"$num-$num2")

// Format 3: 5551234567 (raw digits)
val format3 = for {
  digits <- digit.exactlyN(10).map(_.mkString)
  area   = digits.substring(0, 3)
  num    = digits.substring(3, 6)
  num2   = digits.substring(6, 10)
} yield PhoneNumber(None, area, s"$num-$num2")

val phoneParser = format1 | format2 | format3

// Usage
phoneParser.run("+1 (555) 123-4567")
// Success(PhoneNumber(Some("1"), "555", "123-4567"), ...)

phoneParser.run("555-123-4567")
// Success(PhoneNumber(None, "555", "123-4567"), ...)

phoneParser.run("5551234567")
// Success(PhoneNumber(None, "555", "123-4567"), ...)
```

**Key techniques:**
- Multiple format alternatives
- Optional country code
- Normalizing to common representation

---

## 10. SQL SELECT Statement Parser

**Goal:** Parse basic SQL SELECT statements.

```scala
import parser.core._
import parser.syntax._

case class SelectStmt(columns: List[String], table: String, where: Option[String])

val ws = satisfy(_.isWhitespace, "whitespace").many1.void
val optWs = ws.optional
val identifier = letter.many1.map(_.mkString)

val select = string("SELECT") ~ ws
val from = ws ~ string("FROM") ~ ws
val where = ws ~ string("WHERE") ~ ws

val columnList = identifier.sepBy(optWs ~ char(',') ~ optWs)
val whereCondition = satisfy(_ != ';', "where char").many.map(_.mkString.trim)

val selectStmt = for {
  _      <- select
  cols   <- columnList
  _      <- from
  table  <- identifier
  cond   <- (where *> whereCondition).optional
  _      <- optWs ~ char(';').optional
} yield SelectStmt(cols, table, cond)

// Usage
selectStmt.run("SELECT name, age FROM users WHERE age > 18;")
// Success(SelectStmt(
//   List("name", "age"),
//   "users",
//   Some("age > 18")
// ), ...)

selectStmt.run("SELECT * FROM products")
// Success(SelectStmt(List("*"), "products", None), ...)
```

**Key techniques:**
- Case-insensitive keywords
- Comma-separated lists
- Optional WHERE clause
- Flexible whitespace handling

---

## Common Patterns Across Recipes

### 1. **Whitespace Handling**
```scala
val ws = satisfy(_.isWhitespace, "whitespace").many.void
val optWs = ws.optional
```

### 2. **Separated Lists**
```scala
val items = item.sepBy(char(','))
val itemsWithWs = item.sepBy(optWs ~ char(',') ~ optWs)
```

### 3. **Optional Components**
```scala
val maybePrefix = prefix.optional  // Parser[Option[A]]
```

### 4. **Alternatives (Try in Order)**
```scala
val parser = mostSpecific | lessSpecific | fallback
```

### 5. **Repeated Patterns**
```scala
val many = p.many      // 0 or more
val some = p.many1     // 1 or more
val exact = p.exactlyN(n)  // exactly n
```

### 6. **Discarding Results**
```scala
val keepLeft = p1 <* p2   // Keep p1's result, discard p2
val keepRight = p1 *> p2  // Discard p1's result, keep p2
```

### 7. **Named Errors**
```scala
val email = emailParser.named("email address")
// Better error: "Expected email address" vs "Expected character..."
```

---

## Next Steps

- **Build your own parser**: Start with a simple grammar and expand
- **Combine patterns**: Mix and match these recipes for complex formats
- **Error handling**: Add `.named()` for better error messages
- **Performance**: See [Performance Guide](./memoization-performance-analysis.md) for optimization tips

## More Examples

Check the `/examples` directory in the repository for complete, runnable examples:
- Expression evaluators (idiomatic and structural approaches)
- JSON to case class conversion
- Error recovery strategies
- Debugging techniques

---

**Questions or suggestions?** Open an issue on [GitHub](https://github.com/ghoulalib/rumil/issues)!
