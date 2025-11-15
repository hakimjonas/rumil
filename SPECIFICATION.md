# 🎯 FINAL SPECIFICATION - Parser Combinator Library

**Complete, Production-Ready Specification for Claude Code**

---f

## 📋 Project Overview

A pure functional parser combinator library for Scala 3.7.4, designed from first principles with Fungal portability as the primary goal.

**Core Philosophy:**
- Enums for sum types
- Named tuples for product types
- Regular classes ONLY for controlled mutation (Ref pattern)
- Zero case classes
- Zero sealed traits
- Top-level functions over objects

---

## 📂 Project Structure

```
parser-combinators/
├── build.sbt
├── project/
│   └── build.properties
├── README.md
├── MANIFESTO.md
├── .scalafmt.conf
├── .jvmopts
├── src/
│   └── main/
│       └── scala/
│           └── parser/
│               ├── core/
│               │   ├── Types.scala
│               │   ├── Combinators.scala
│               │   └── Primitives.scala
│               ├── runtime/
│               │   ├── ParserState.scala
│               │   └── Interpreter.scala
│               ├── syntax/
│               │   └── Extensions.scala
│               └── typeclasses/
│                   ├── Abstractions.scala
│                   └── Instances.scala
└── test/
    └── scala/
        └── parser/
            ├── laws/
            │   └── MonadLaws.scala
            └── examples/
                ├── ArithmeticParser.scala
                └── JsonParser.scala
```

---

## 📋 build.sbt

```scala
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "dev.fungal"

javacOptions ++= Seq(
  "--release", "25"
)

lazy val root = (project in file("."))
  .settings(
    name := "parser-combinators",
    
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
      "org.scalameta" %% "munit" % "1.0.3" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
    ),
    
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wsafe-init",
      "-Wunused:all",
      "-Wvalue-discard",
      "-explain",
      "-no-indent",        // Braces only - no significant indentation
      "-old-syntax"        // Prefer braces
    ),
    
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled"
    ),
    
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-Xmx2G"
    )
  )
```

---

## 📋 project/build.properties

```properties
sbt.version=1.10.7
```

---

## 📋 .scalafmt.conf

```conf
version = 3.8.3
runner.dialect = scala3

maxColumn = 100
indent.main = 2
indent.callSite = 2
indent.defnSite = 2

# ENFORCE BRACES - no significant indentation
rewrite.scala3.convertToNewSyntax = false
rewrite.scala3.removeOptionalBraces = no
rewrite.scala3.insertEndMarkerMinLines = 0

indent.significant = 0
danglingParentheses.preset = false

align.preset = most
align.multiline = false

rewrite.rules = [
  RedundantBraces,
  RedundantParens,
  SortModifiers,
  PreferCurlyFors
]

newlines.beforeCurlyLambdaParams = never
newlines.afterCurlyLambdaParams = never

docstrings.style = Asterisk
docstrings.wrap = no
```

---

## 📋 .jvmopts

```
-Xmx4G
-Xss4M
-XX:+UseG1GC
-XX:MaxGCPauseMillis=50
-XX:+UseStringDeduplication
-XX:ReservedCodeCacheSize=512m
-XX:MaxMetaspaceSize=1G
-Dfile.encoding=UTF-8
```

---

## 📖 MANIFESTO.md

```markdown
# Parser Combinator Library Manifesto

**Version**: 0.1.0  
**Target**: Scala 3.7.4 + Java 25 LTS → Fungal  
**Philosophy**: Pure Core + Imperative Shell

---

## Vision

A pure functional parser combinator library built from first principles. Every design decision prioritizes Fungal portability and radical simplicity.

---

## The Three Types

We use exactly three kinds of types:

### 1. Enums (Sum Types)
```scala
enum Parser[+E, +A] {
  case Succeed[A](value: A) extends Parser[Nothing, A]
  case Fail[E](error: E) extends Parser[E, Nothing]
  // ...
}
```

### 2. Named Tuples (Product Types)
```scala
type Location = (line: Int, column: Int, offset: Int)
type StateSnapshot = (offset: Int, line: Int, column: Int)
```

### 3. Regular Classes (Controlled Mutation Only)
```scala
// Ref - encapsulates var (Eru pattern)
private final class Ref[A](private var value: A) {
  def get: A = value
  def set(newValue: A): Unit = { value = newValue }
  def update(f: A => A): Unit = { value = f(value) }
}

// ParserState - uses Refs for controlled mutation
final class ParserState(/* ... */) {
  private val offsetRef: Ref[Int]
  def advance(): Unit = offsetRef.update(_ + 1)
}
```

---

## What We Never Use

❌ **Case classes** - Use named tuples instead  
❌ **Sealed traits** - Use enums instead  
❌ **Naked vars** - Use Ref for controlled mutation  
❌ **Opaque types** - Keep it simple  
❌ **Objects as namespaces** - Use top-level functions

---

## Architecture: Pure Core + Imperative Shell

### Pure Core (User API)
- Enums (Parser, Result, ParseError)
- Named tuples (Location, Span)
- Top-level functions (combinators, primitives)
- Extensions (ergonomic syntax)
- Type classes (lawful abstractions)

### Imperative Shell (Internal)
- Ref class (encapsulated mutation)
- ParserState class (controlled mutation via Refs)
- Interpreter (executes pure descriptions with mutation)

**Clear Boundary**: Users never see mutation. The imperative shell is isolated.

---

## Design Principles

### Foundational Correctness
- Parsers are pure data (descriptions, not procedures)
- Type-safe (union types, GADTs via enums)
- Lawful (Functor, Monad laws verified)

### Radical Ergonomics
- For-comprehension native
- Extension methods for fluency
- Clear error messages

### Guided Correctness
- Stack-safe by default (via Ref-based state)
- Explicit backtracking (save/restore)
- Type errors prevent common mistakes

### Exceptional Observability
- Structured errors (ParseError enum)
- Location tracking (line, column, offset)
- Pretty printing via Show type class

---

## Fungal Portability

Every design choice translates directly to Fungal:

```fungal
// Enums → Fungal ADTs
type Parser[E, A] = Succeed(A) | Fail(E) | ...

// Named tuples → Fungal records
type Location = (line: Int, column: Int, offset: Int)

// Ref → Fungal Ref (like Haskell's IORef)
type Ref[A] = ... // Built-in mutable reference

// Top-level functions → Fungal top-level functions
fun succeed[A](value: A): Parser[Nothing, A] = Succeed(value)
```

Zero impedance mismatch!

---

## Technical Stack

**Scala 3.7.4** - Latest features:
- Enhanced named tuples
- Improved inline optimization
- Safe initialization checking
- Better union type handling

**Java 25 LTS** - Optimal runtime:
- G1GC (best for parsing workloads)
- String deduplication (saves memory on tokens)
- Modern JVM optimizations

**Braces-only syntax** - Enforced via compiler flags for consistency and Fungal portability.

---

## Success Metrics

1. ✅ Zero case classes in the codebase
2. ✅ Zero sealed traits (use enums)
3. ✅ All monad laws verified (ScalaCheck)
4. ✅ Arithmetic parser in <50 lines
5. ✅ JSON parser in <100 lines
6. ✅ Ports to Fungal in <1 week

---

## Non-Goals

- ❌ Backwards compatibility with Scala 2
- ❌ Parser generation from grammars
- ❌ Packrat parsing (opt-in, not built-in)
- ❌ Competing with parser generators on raw speed

---

**This manifesto is law. Every line of code must align with these principles.**
```

---

## 📖 README.md

```markdown
# Parser Combinator Library

A pure functional parser combinator library for Scala 3, designed from first principles with Fungal portability as the primary goal.

## Status

🚧 **Phase 1: Core Library Implementation**

## Philosophy

Three types only:
1. **Enums** for sum types (ADTs)
2. **Named tuples** for product types (records)
3. **Regular classes** for controlled mutation only

Zero case classes. Zero sealed traits. Pure FP throughout.

Read [MANIFESTO.md](MANIFESTO.md) for complete design philosophy.

## Quick Example

```scala
import parser.core.*
import parser.core.given
import parser.syntax.*

// Simple arithmetic parser
val number: Parser[ParseError, Int] = {
  digit.many1.map(_.mkString.toInt)
}

val expr: Parser[ParseError, Int] = {
  for {
    n1 <- number
    _ <- char('+')
    n2 <- number
  } yield n1 + n2
}

val result = run(expr, "40+2")
// Success(42, consumed=4)
```

## Requirements

- **Scala**: 3.7.4
- **Java**: 25 LTS
- **sbt**: 1.10.7+

## Building

```bash
sbt compile
sbt test
```

## Design Highlights

- ✅ Enums for ADTs (not sealed traits)
- ✅ Named tuples for records (not case classes)
- ✅ Ref-based controlled mutation (Eru pattern)
- ✅ Top-level functions (not objects)
- ✅ Type classes (Functor, Monad, Show, Eq)
- ✅ Braces-only syntax (no indentation)

## Relationship to Eru

Eru is a world-class effect system for concurrent, async, resource-safe programs.

This parser library is a specialized tool for sequential text parsing. Parsing doesn't need:
- Concurrency (parsing is sequential)
- Async I/O (parsing is synchronous)
- Resource management (pure parsing has no resources)

This library demonstrates patterns that may be adopted in Eru:
- Named tuples over case classes
- Ref-based controlled mutation
- Pure Core + Imperative Shell

## License

MIT
```

---

## 🔧 IMPLEMENTATION FILES

### File 1: src/main/scala/parser/core/Types.scala

```scala
package parser.core

import scala.collection.immutable.Vector

// ============================================================================
// NAMED TUPLES - Product Types
// ============================================================================

type Location = (line: Int, column: Int, offset: Int)

type Span = (start: Location, end: Location)

// ============================================================================
// ENUMS - Sum Types
// ============================================================================

enum Parser[+E, +A] {
  case Succeed[A](value: A) extends Parser[Nothing, A]
  case Fail[E](error: E) extends Parser[E, Nothing]
  case Satisfy(pred: Char => Boolean, expected: String) 
    extends Parser[ParseError, Char]
  case Map[E, A, B](source: Parser[E, A], f: A => B) 
    extends Parser[E, B]
  case FlatMap[E, A, B](source: Parser[E, A], f: A => Parser[E, B]) 
    extends Parser[E, B]
  case Or[E, A](left: Parser[E, A], right: Parser[E, A]) 
    extends Parser[E, A]
  case Many[E, A](parser: Parser[E, A]) 
    extends Parser[E, List[A]]
  case Many1[E, A](parser: Parser[E, A]) 
    extends Parser[E, List[A]]
  case Optional[E, A](parser: Parser[E, A]) 
    extends Parser[E, Option[A]]
  case Attempt[E, A](parser: Parser[E, A]) 
    extends Parser[Nothing, Result[E, A]]
  case LookAhead[E, A](parser: Parser[E, A]) 
    extends Parser[E, A]
  case NotFollowedBy[E, A](parser: Parser[E, A]) 
    extends Parser[E, Unit]
  case Named[E, A](parser: Parser[E, A], name: String) 
    extends Parser[E, A]
  case Custom[E, A](run: runtime.ParserState => Result[E, A]) 
    extends Parser[E, A]
}

enum ParseError {
  case Unexpected(found: String, expected: Set[String], location: Location)
  case EndOfInput(expected: String, location: Location)
  case Custom(message: String, location: Location)
}

enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Failure(errors: List[E], furthest: Location)
}

enum TokenKind {
  case Identifier, Number, String, Keyword, Operator
  case LeftParen, RightParen, LeftBrace, RightBrace
  case Comma, Semicolon, Colon, Arrow
  case Whitespace, Comment, EOF
}

enum SyntaxKind {
  case SourceFile, Function, TypeDef, Expression
  case Block, Statement, Pattern, Literal
}

enum GreenNode {
  case Token(kind: TokenKind, text: String, span: Span)
  case Tree(kind: SyntaxKind, children: Vector[GreenNode])
}
```

---

### File 2: src/main/scala/parser/core/Combinators.scala

```scala
package parser.core

// ============================================================================
// TOP-LEVEL FUNCTIONS - Core Combinators
// ============================================================================

// Construction
inline def succeed[A](value: A): Parser[Nothing, A] = {
  Parser.Succeed(value)
}

inline def fail[E](error: E): Parser[E, Nothing] = {
  Parser.Fail(error)
}

// Sequencing
inline def map[E, A, B](p: Parser[E, A], f: A => B): Parser[E, B] = {
  Parser.Map(p, f)
}

inline def flatMap[E, A, B](p: Parser[E, A], f: A => Parser[E, B]): Parser[E, B] = {
  Parser.FlatMap(p, f)
}

def zip[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, (A, B)] = {
  for {
    a <- p1
    b <- p2
  } yield (a, b)
}

def zipLeft[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, A] = {
  for {
    a <- p1
    _ <- p2
  } yield a
}

def zipRight[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, B] = {
  for {
    _ <- p1
    b <- p2
  } yield b
}

// Choice
inline def or[E, A](left: Parser[E, A], right: Parser[E, A]): Parser[E, A] = {
  Parser.Or(left, right)
}

def choice[E, A](parsers: List[Parser[E, A]]): Parser[E, A] = {
  parsers.reduceLeft(or)
}

// Repetition
inline def many[E, A](p: Parser[E, A]): Parser[E, List[A]] = {
  Parser.Many(p)
}

inline def many1[E, A](p: Parser[E, A]): Parser[E, List[A]] = {
  Parser.Many1(p)
}

inline def optional[E, A](p: Parser[E, A]): Parser[E, Option[A]] = {
  Parser.Optional(p)
}

def sepBy[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] = {
  sepBy1(p, sep) or succeed(List.empty)
}

def sepBy1[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] = {
  for {
    head <- p
    tail <- many(zipRight(sep, p))
  } yield head :: tail
}

def endBy[E, A, End](p: Parser[E, A], end: Parser[E, End]): Parser[E, List[A]] = {
  many(zipLeft(p, end))
}

def count[E, A](n: Int, p: Parser[E, A]): Parser[E, List[A]] = {
  if (n <= 0) then succeed(List.empty)
  else {
    for {
      head <- p
      tail <- count(n - 1, p)
    } yield head :: tail
  }
}

// Lookahead
inline def lookAhead[E, A](p: Parser[E, A]): Parser[E, A] = {
  Parser.LookAhead(p)
}

inline def notFollowedBy[E, A](p: Parser[E, A]): Parser[E, Unit] = {
  Parser.NotFollowedBy(p)
}

// Error handling
inline def attempt[E, A](p: Parser[E, A]): Parser[Nothing, Result[E, A]] = {
  Parser.Attempt(p)
}

def recover[E, A](p: Parser[E, A])(f: E => A): Parser[Nothing, A] = {
  attempt(p).map {
    case Result.Success(value, _) => value
    case Result.Failure(errors, _) => f(errors.head)
  }
}

def recoverWith[E, E2, A](p: Parser[E, A])(f: E => Parser[E2, A]): Parser[E2, A] = {
  attempt(p).flatMap {
    case Result.Success(value, _) => succeed(value)
    case Result.Failure(errors, _) => f(errors.head)
  }
}

inline def named[E, A](p: Parser[E, A], name: String): Parser[E, A] = {
  Parser.Named(p, name)
}

// Operators
def chainl1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] = {
  def rest(acc: A): Parser[E, A] = {
    (for {
      f <- op
      right <- p
      result <- rest(f(acc, right))
    } yield result) or succeed(acc)
  }
  
  for {
    initial <- p
    result <- rest(initial)
  } yield result
}

def chainr1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] = {
  for {
    left <- p
    result <- (for {
      f <- op
      right <- chainr1(p, op)
    } yield f(left, right)) or succeed(left)
  } yield result
}
```

---

### File 3: src/main/scala/parser/core/Primitives.scala

```scala
package parser.core

// ============================================================================
// TOP-LEVEL FUNCTIONS - Primitive Parsers
// ============================================================================

// Character-level
def char(c: Char): Parser[ParseError, Char] = {
  Parser.Satisfy(_ == c, s"'$c'")
}

def satisfy(pred: Char => Boolean, expected: String): Parser[ParseError, Char] = {
  Parser.Satisfy(pred, expected)
}

def anyChar: Parser[ParseError, Char] = {
  satisfy(_ => true, "any character")
}

def oneOf(chars: String): Parser[ParseError, Char] = {
  satisfy(chars.contains, s"one of '$chars'")
}

def noneOf(chars: String): Parser[ParseError, Char] = {
  satisfy(!chars.contains(_), s"none of '$chars'")
}

// String-level
def string(s: String): Parser[ParseError, String] = {
  if (s.isEmpty) then succeed("")
  else {
    s.toList match {
      case head :: tail => {
        for {
          h <- char(head)
          t <- string(tail.mkString)
        } yield s"$h$t"
      }
      case Nil => succeed("")
    }
  }
}

// Common character classes
def digit: Parser[ParseError, Char] = {
  satisfy(_.isDigit, "digit")
}

def letter: Parser[ParseError, Char] = {
  satisfy(_.isLetter, "letter")
}

def alphaNum: Parser[ParseError, Char] = {
  satisfy(_.isLetterOrDigit, "letter or digit")
}

def whitespace: Parser[ParseError, Char] = {
  satisfy(_.isWhitespace, "whitespace")
}

def spaces: Parser[ParseError, List[Char]] = {
  whitespace.many
}

def spaces1: Parser[ParseError, List[Char]] = {
  whitespace.many1
}

// Lexeme combinators
def lexeme[E, A](p: Parser[E, A]): Parser[E | ParseError, A] = {
  for {
    result <- p
    _ <- spaces
  } yield result
}

def symbol(s: String): Parser[ParseError, String] = {
  lexeme(string(s))
}

// End of input
def eof: Parser[ParseError, Unit] = {
  Parser.Custom { state =>
    if (state.atEnd) then Result.Success((), 0)
    else {
      Result.Failure(
        List(ParseError.Custom("Expected end of input", state.location)),
        state.location
      )
    }
  }
}
```

---

### File 4: src/main/scala/parser/runtime/ParserState.scala

```scala
package parser.runtime

import parser.core.*

// ============================================================================
// Ref - Controlled Mutation (Eru Pattern)
// ============================================================================

private final class Ref[A](private var value: A) {
  def get: A = value
  def set(newValue: A): Unit = { value = newValue }
  def update(f: A => A): Unit = { value = f(value) }
}

// ============================================================================
// ParserState - Controlled Mutation via Refs
// ============================================================================

final class ParserState private (
  val input: String,
  private val offsetRef: Ref[Int],
  private val lineRef: Ref[Int],
  private val columnRef: Ref[Int]
) {
  
  def offset: Int = offsetRef.get
  def line: Int = lineRef.get
  def column: Int = columnRef.get
  
  def location: Location = {
    (line = lineRef.get, column = columnRef.get, offset = offsetRef.get)
  }
  
  def atEnd: Boolean = offsetRef.get >= input.length
  
  def current: Option[Char] = {
    if (atEnd) then None else Some(input(offsetRef.get))
  }
  
  def peek(n: Int): Option[Char] = {
    val idx = offsetRef.get + n
    if (idx >= input.length) then None else Some(input(idx))
  }
  
  def advance(): Unit = {
    if (!atEnd) then {
      if (input(offsetRef.get) == '\n') then {
        lineRef.update(_ + 1)
        columnRef.set(1)
      } else {
        columnRef.update(_ + 1)
      }
      offsetRef.update(_ + 1)
    }
  }
  
  def advanceN(n: Int): Unit = {
    (0 until n).foreach(_ => advance())
  }
  
  def save: StateSnapshot = {
    (offset = offsetRef.get, line = lineRef.get, column = columnRef.get)
  }
  
  def restore(snapshot: StateSnapshot): Unit = {
    offsetRef.set(snapshot.offset)
    lineRef.set(snapshot.line)
    columnRef.set(snapshot.column)
  }
  
  def remaining: String = input.substring(offsetRef.get)
  
  def slice(start: Int, end: Int): String = input.substring(start, end)
}

// Named tuple - not case class!
type StateSnapshot = (offset: Int, line: Int, column: Int)

object ParserState {
  def apply(input: String): ParserState = {
    new ParserState(input, Ref(0), Ref(1), Ref(1))
  }
}
```

---

### File 5: src/main/scala/parser/runtime/Interpreter.scala

```scala
package parser.runtime

import parser.core.*

// ============================================================================
// INTERPRETER - Executes Parser Descriptions
// ============================================================================

def run[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = ParserState(input)
  interpret(parser, state)
}

private def interpret[E, A](parser: Parser[E, A], state: ParserState): Result[E, A] = {
  parser match {
    
    case Parser.Succeed(value) => {
      Result.Success(value, 0)
    }
    
    case Parser.Fail(error) => {
      Result.Failure(List(error), state.location)
    }
    
    case Parser.Satisfy(pred, expected) => {
      state.current match {
        case Some(c) if pred(c) => {
          state.advance()
          Result.Success(c, 1)
        }
        case Some(c) => {
          Result.Failure(
            List(ParseError.Unexpected(c.toString, Set(expected), state.location)),
            state.location
          )
        }
        case None => {
          Result.Failure(
            List(ParseError.EndOfInput(expected, state.location)),
            state.location
          )
        }
      }
    }
    
    case Parser.Map(source, f) => {
      interpret(source, state) match {
        case Result.Success(value, consumed) => {
          Result.Success(f(value), consumed)
        }
        case failure @ Result.Failure(_, _) => failure
      }
    }
    
    case Parser.FlatMap(source, f) => {
      interpret(source, state) match {
        case Result.Success(value, consumed1) => {
          interpret(f(value), state) match {
            case Result.Success(value2, consumed2) => {
              Result.Success(value2, consumed1 + consumed2)
            }
            case failure @ Result.Failure(_, _) => failure
          }
        }
        case failure @ Result.Failure(_, _) => failure
      }
    }
    
    case Parser.Or(left, right) => {
      val snapshot = state.save
      interpret(left, state) match {
        case success @ Result.Success(_, _) => success
        case Result.Failure(leftErrors, leftFurthest) => {
          state.restore(snapshot)
          interpret(right, state) match {
            case success @ Result.Success(_, _) => success
            case Result.Failure(rightErrors, rightFurthest) => {
              if (leftFurthest.offset > rightFurthest.offset) then {
                Result.Failure(leftErrors, leftFurthest)
              } else if (rightFurthest.offset > leftFurthest.offset) then {
                Result.Failure(rightErrors, rightFurthest)
              } else {
                Result.Failure(leftErrors ++ rightErrors, leftFurthest)
              }
            }
          }
        }
      }
    }
    
    case Parser.Many(p) => {
      val buffer = scala.collection.mutable.ListBuffer.empty[A]
      var totalConsumed = 0
      var continue = true
      
      while (continue) {
        val snapshot = state.save
        interpret(p, state) match {
          case Result.Success(value, consumed) => {
            buffer += value
            totalConsumed += consumed
          }
          case Result.Failure(_, _) => {
            state.restore(snapshot)
            continue = false
          }
        }
      }
      
      Result.Success(buffer.toList, totalConsumed)
    }
    
    case Parser.Many1(p) => {
      interpret(p, state) match {
        case Result.Success(head, consumed1) => {
          interpret(Parser.Many(p), state) match {
            case Result.Success(tail, consumed2) => {
              Result.Success(head :: tail, consumed1 + consumed2)
            }
            case failure @ Result.Failure(_, _) => failure
          }
        }
        case failure @ Result.Failure(_, _) => failure
      }
    }
    
    case Parser.Optional(p) => {
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(value, consumed) => {
          Result.Success(Some(value), consumed)
        }
        case Result.Failure(_, _) => {
          state.restore(snapshot)
          Result.Success(None, 0)
        }
      }
    }
    
    case Parser.Attempt(p) => {
      val snapshot = state.save
      interpret(p, state) match {
        case success @ Result.Success(_, _) => {
          Result.Success(success, 0)
        }
        case failure @ Result.Failure(_, _) => {
          state.restore(snapshot)
          Result.Success(failure, 0)
        }
      }
    }
    
    case Parser.LookAhead(p) => {
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(value, _) => {
          state.restore(snapshot)
          Result.Success(value, 0)
        }
        case failure @ Result.Failure(_, _) => {
          state.restore(snapshot)
          failure
        }
      }
    }
    
    case Parser.NotFollowedBy(p) => {
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(_, _) => {
          state.restore(snapshot)
          Result.Failure(
            List(ParseError.Custom("Unexpected success", state.location)),
            state.location
          )
        }
        case Result.Failure(_, _) => {
          state.restore(snapshot)
          Result.Success((), 0)
        }
      }
    }
    
    case Parser.Named(p, name) => {
      interpret(p, state) match {
        case success @ Result.Success(_, _) => success
        case Result.Failure(errors, furthest) => {
          val enhanced = errors.map {
            case ParseError.Unexpected(found, expected, loc) => {
              ParseError.Unexpected(found, expected + name, loc)
            }
            case other => other
          }
          Result.Failure(enhanced, furthest)
        }
      }
    }
    
    case Parser.Custom(runFn) => {
      runFn(state)
    }
  }
}
```

---

### File 6: src/main/scala/parser/syntax/Extensions.scala

```scala
package parser.syntax

import parser.core.*

// ============================================================================
// EXTENSION METHODS - Ergonomic API
// ============================================================================

extension [E, A](p: Parser[E, A]) {
  
  // Functor
  inline def map[B](f: A => B): Parser[E, B] = {
    parser.core.map(p, f)
  }
  
  inline def as[B](value: B): Parser[E, B] = {
    p.map(_ => value)
  }
  
  inline def void: Parser[E, Unit] = {
    p.as(())
  }
  
  // Monad
  inline def flatMap[B](f: A => Parser[E, B]): Parser[E, B] = {
    parser.core.flatMap(p, f)
  }
  
  inline def >>[B](f: A => Parser[E, B]): Parser[E, B] = {
    flatMap(f)
  }
  
  // Choice
  inline def |(other: Parser[E, A]): Parser[E, A] = {
    parser.core.or(p, other)
  }
  
  inline def or(other: Parser[E, A]): Parser[E, A] = {
    parser.core.or(p, other)
  }
  
  // Sequencing
  inline def ~[B](that: Parser[E, B]): Parser[E, (A, B)] = {
    parser.core.zip(p, that)
  }
  
  inline def *>[B](that: Parser[E, B]): Parser[E, B] = {
    parser.core.zipRight(p, that)
  }
  
  inline def <*[B](that: Parser[E, B]): Parser[E, A] = {
    parser.core.zipLeft(p, that)
  }
  
  // Repetition
  inline def many: Parser[E, List[A]] = {
    parser.core.many(p)
  }
  
  inline def many1: Parser[E, List[A]] = {
    parser.core.many1(p)
  }
  
  inline def optional: Parser[E, Option[A]] = {
    parser.core.optional(p)
  }
  
  inline def sepBy[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] = {
    parser.core.sepBy(p, sep)
  }
  
  inline def sepBy1[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] = {
    parser.core.sepBy1(p, sep)
  }
  
  inline def endBy[End](end: Parser[E, End]): Parser[E, List[A]] = {
    parser.core.endBy(p, end)
  }
  
  inline def count(n: Int): Parser[E, List[A]] = {
    parser.core.count(n, p)
  }
  
  // Operators
  inline def chainl1(op: Parser[E, (A, A) => A]): Parser[E, A] = {
    parser.core.chainl1(p, op)
  }
  
  inline def chainr1(op: Parser[E, (A, A) => A]): Parser[E, A] = {
    parser.core.chainr1(p, op)
  }
  
  // Lookahead
  inline def lookAhead: Parser[E, A] = {
    parser.core.lookAhead(p)
  }
  
  inline def notFollowedBy: Parser[E, Unit] = {
    parser.core.notFollowedBy(p)
  }
  
  // Error handling
  inline def attempt: Parser[Nothing, Result[E, A]] = {
    parser.core.attempt(p)
  }
  
  inline def recover(f: E => A): Parser[Nothing, A] = {
    parser.core.recover(p)(f)
  }
  
  inline def recoverWith[E2](f: E => Parser[E2, A]): Parser[E2, A] = {
    parser.core.recoverWith(p)(f)
  }
  
  inline def named(name: String): Parser[E, A] = {
    parser.core.named(p, name)
  }
  
  inline def label(name: String): Parser[E, A] = {
    named(name)
  }
  
  inline def <?>(name: String): Parser[E, A] = {
    named(name)
  }
  
  // Execution
  inline def run(input: String): Result[E, A] = {
    runtime.run(p, input)
  }
}

extension [E, A](result: Result[E, A]) {
  inline def isSuccess: Boolean = result match {
    case Result.Success(_, _) => true
    case Result.Failure(_, _) => false
  }
  
  inline def isFailure: Boolean = !isSuccess
  
  inline def toEither: Either[List[E], A] = result match {
    case Result.Success(value, _) => Right(value)
    case Result.Failure(errors, _) => Left(errors)
  }
  
  inline def toOption: Option[A] = result match {
    case Result.Success(value, _) => Some(value)
    case Result.Failure(_, _) => None
  }
}
```

---

### File 7: src/main/scala/parser/typeclasses/Abstractions.scala

```scala
package parser.typeclasses

// ============================================================================
// TYPE CLASS DEFINITIONS
// ============================================================================

trait Functor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

trait Applicative[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]
  def ap[A, B](ff: F[A => B])(fa: F[A]): F[B]
  
  override def map[A, B](fa: F[A])(f: A => B): F[B] = {
    ap(pure(f))(fa)
  }
}

trait Monad[F[_]] extends Applicative[F] {
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  
  override def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] = {
    flatMap(ff)(f => map(fa)(f))
  }
  
  override def map[A, B](fa: F[A])(f: A => B): F[B] = {
    flatMap(fa)(a => pure(f(a)))
  }
}

trait Show[A] {
  def show(a: A): String
}

trait Eq[A] {
  def eqv(a: A, b: A): Boolean
}
```

---

### File 8: src/main/scala/parser/typeclasses/Instances.scala

```scala
package parser.typeclasses

import parser.core.*

// ============================================================================
// TYPE CLASS INSTANCES
// ============================================================================

// Parser is a Monad (for fixed error type)
given [E]: Monad[[A] =>> Parser[E, A]] with {
  def pure[A](a: A): Parser[E, A] = {
    Parser.Succeed(a)
  }
  
  def flatMap[A, B](fa: Parser[E, A])(f: A => Parser[E, B]): Parser[E, B] = {
    Parser.FlatMap(fa, f)
  }
}

// Result is a Monad (for fixed error type)
given [E]: Monad[[A] =>> Result[E, A]] with {
  def pure[A](a: A): Result[E, A] = {
    Result.Success(a, 0)
  }
  
  def flatMap[A, B](fa: Result[E, A])(f: A => Result[E, B]): Result[E, B] = {
    fa match {
      case Result.Success(value, consumed1) => {
        f(value) match {
          case Result.Success(value2, consumed2) => {
            Result.Success(value2, consumed1 + consumed2)
          }
          case Result.Failure(errors, furthest) => {
            Result.Failure(errors, furthest)
          }
        }
      }
      case Result.Failure(errors, furthest) => {
        Result.Failure(errors, furthest)
      }
    }
  }
}

// Show instances
given Show[Location] with {
  def show(loc: Location): String = {
    s"line ${loc.line}, column ${loc.column} (offset ${loc.offset})"
  }
}

given Show[ParseError] with {
  def show(error: ParseError): String = {
    error match {
      case ParseError.Unexpected(found, expected, loc) => {
        val expStr = expected.mkString(", ")
        s"Unexpected '$found' (expected: $expStr) at ${summon[Show[Location]].show(loc)}"
      }
      case ParseError.EndOfInput(expected, loc) => {
        s"Unexpected end of input (expected: $expected) at ${summon[Show[Location]].show(loc)}"
      }
      case ParseError.Custom(message, loc) => {
        s"$message at ${summon[Show[Location]].show(loc)}"
      }
    }
  }
}

given [E: Show, A: Show]: Show[Result[E, A]] with {
  def show(result: Result[E, A]): String = {
    result match {
      case Result.Success(value, consumed) => {
        s"Success(${summon[Show[A]].show(value)}, consumed=$consumed)"
      }
      case Result.Failure(errors, furthest) => {
        val errStrs = errors.map(summon[Show[E]].show).mkString("\n  ")
        s"Failure at ${summon[Show[Location]].show(furthest)}:\n  $errStrs"
      }
    }
  }
}

// Eq instances
given Eq[Location] with {
  def eqv(a: Location, b: Location): Boolean = {
    a.line == b.line && a.column == b.column && a.offset == b.offset
  }
}
```

---

### File 9: test/scala/parser/examples/ArithmeticParser.scala

```scala
package parser.examples

import munit.FunSuite
import parser.core.*
import parser.core.given
import parser.syntax.*

class ArithmeticParserTests extends FunSuite {
  
  // Simple arithmetic expression parser
  val number: Parser[ParseError, Int] = {
    digit.many1.map(_.mkString.toInt).named("number")
  }
  
  lazy val factor: Parser[ParseError, Int] = {
    number | (char('(') *> expr <* char(')'))
  }
  
  lazy val term: Parser[ParseError, Int] = {
    factor.chainl1(
      char('*').as((a: Int, b: Int) => a * b) |
      char('/').as((a: Int, b: Int) => a / b)
    )
  }
  
  lazy val expr: Parser[ParseError, Int] = {
    term.chainl1(
      char('+').as((a: Int, b: Int) => a + b) |
      char('-').as((a: Int, b: Int) => a - b)
    )
  }
  
  test("parse single number") {
    val result = number.run("42")
    assert(result.isSuccess)
    assertEquals(result.toOption, Some(42))
  }
  
  test("parse addition") {
    val result = expr.run("1+2")
    assertEquals(result.toOption, Some(3))
  }
  
  test("parse multiplication") {
    val result = expr.run("2*3")
    assertEquals(result.toOption, Some(6))
  }
  
  test("parse complex expression") {
    val result = expr.run("1+2*3")
    assertEquals(result.toOption, Some(7))
  }
  
  test("parse parentheses") {
    val result = expr.run("(1+2)*3")
    assertEquals(result.toOption, Some(9))
  }
  
  test("parse nested expression") {
    val result = expr.run("2*(3+4)")
    assertEquals(result.toOption, Some(14))
  }
  
  test("error on invalid input") {
    val result = expr.run("abc")
    assert(result.isFailure)
  }
}
```

---

### File 10: test/scala/parser/examples/JsonParser.scala

```scala
package parser.examples

import munit.FunSuite
import parser.core.*
import parser.core.given
import parser.syntax.*

// Simple JSON ADT
enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

class JsonParserTests extends FunSuite {
  import JsonValue.*
  
  val jsonNull: Parser[ParseError, JsonValue] = {
    string("null").as(Null)
  }
  
  val jsonBool: Parser[ParseError, JsonValue] = {
    string("true").as(Bool(true)) |
    string("false").as(Bool(false))
  }
  
  val jsonNumber: Parser[ParseError, JsonValue] = {
    (digit.many1 ~ (char('.') *> digit.many1).optional)
      .map {
        case (whole, None) => Number(whole.mkString.toDouble)
        case (whole, Some(frac)) => {
          Number(s"${whole.mkString}.${frac.mkString}".toDouble)
        }
      }
  }
  
  val jsonString: Parser[ParseError, JsonValue] = {
    (char('"') *> satisfy(_ != '"', "string char").many <* char('"'))
      .map(chars => Str(chars.mkString))
  }
  
  lazy val jsonValue: Parser[ParseError, JsonValue] = {
    jsonNull | jsonBool | jsonNumber | jsonString | jsonArray | jsonObject
  }
  
  lazy val jsonArray: Parser[ParseError, JsonValue] = {
    (char('[') *> jsonValue.sepBy(char(',')) <* char(']'))
      .map(Array.apply)
  }
  
  lazy val jsonObject: Parser[ParseError, JsonValue] = {
    val pair = for {
      _ <- char('"')
      key <- satisfy(_ != '"', "key char").many.map(_.mkString)
      _ <- char('"')
      _ <- char(':')
      value <- jsonValue
    } yield (key, value)
    
    (char('{') *> pair.sepBy(char(',')) <* char('}'))
      .map(pairs => Object(pairs.toMap))
  }
  
  test("parse null") {
    val result = jsonNull.run("null")
    assertEquals(result.toOption, Some(Null))
  }
  
  test("parse true") {
    val result = jsonBool.run("true")
    assertEquals(result.toOption, Some(Bool(true)))
  }
  
  test("parse false") {
    val result = jsonBool.run("false")
    assertEquals(result.toOption, Some(Bool(false)))
  }
  
  test("parse integer") {
    val result = jsonNumber.run("42")
    assertEquals(result.toOption, Some(Number(42.0)))
  }
  
  test("parse float") {
    val result = jsonNumber.run("3.14")
    assertEquals(result.toOption, Some(Number(3.14)))
  }
  
  test("parse string") {
    val result = jsonString.run("\"hello\"")
    assertEquals(result.toOption, Some(Str("hello")))
  }
  
  test("parse empty array") {
    val result = jsonArray.run("[]")
    assertEquals(result.toOption, Some(Array(List())))
  }
  
  test("parse array with elements") {
    val result = jsonArray.run("[1,2,3]")
    assertEquals(
      result.toOption, 
      Some(Array(List(Number(1), Number(2), Number(3))))
    )
  }
  
  test("parse empty object") {
    val result = jsonObject.run("{}")
    assertEquals(result.toOption, Some(Object(Map())))
  }
  
  test("parse simple object") {
    val result = jsonObject.run("{\"name\":\"Alice\"}")
    assertEquals(
      result.toOption,
      Some(Object(Map("name" -> Str("Alice"))))
    )
  }
}
```

---

### File 11: test/scala/parser/laws/MonadLaws.scala

```scala
package parser.laws

import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen, Properties}
import parser.core.*
import parser.typeclasses.*

object MonadLawsSpec extends Properties("Monad Laws") {
  
  // Generators for Parser
  given Arbitrary[Parser[String, Int]] = Arbitrary {
    Gen.oneOf(
      Gen.const(Parser.Succeed(42)),
      Gen.const(Parser.Fail("error")),
      Gen.const(digit.many1.map(_.mkString.toInt))
    )
  }
  
  // TODO: Implement monad law tests
  // These are placeholders - Claude Code should implement
  
  property("left identity") = forAll { (a: Int, f: Int => Parser[String, Int]) =>
    // pure(a).flatMap(f) == f(a)
    true // Placeholder
  }
  
  property("right identity") = forAll { (p: Parser[String, Int]) =>
    // p.flatMap(pure) == p
    true // Placeholder
  }
  
  property("associativity") = forAll { 
    (p: Parser[String, Int], f: Int => Parser[String, Int], g: Int => Parser[String, Int]) =>
    // p.flatMap(f).flatMap(g) == p.flatMap(x => f(x).flatMap(g))
    true // Placeholder
  }
}
```

---

## ✅ IMPLEMENTATION CHECKLIST

### Phase 1: Setup (15 min)
- [ ] Create directory structure
- [ ] Add build.sbt
- [ ] Add project/build.properties
- [ ] Add .scalafmt.conf
- [ ] Add .jvmopts
- [ ] Add MANIFESTO.md
- [ ] Add README.md
- [ ] Verify `sbt compile` works

### Phase 2: Core Types (30 min)
- [ ] Implement Types.scala
    - [ ] Location type (named tuple)
    - [ ] Span type (named tuple)
    - [ ] Parser enum
    - [ ] ParseError enum
    - [ ] Result enum
    - [ ] TokenKind enum
    - [ ] SyntaxKind enum
    - [ ] GreenNode enum
- [ ] Verify compilation

### Phase 3: Combinators (45 min)
- [ ] Implement Combinators.scala
    - [ ] Construction (succeed, fail)
    - [ ] Sequencing (map, flatMap, zip, zipLeft, zipRight)
    - [ ] Choice (or, choice)
    - [ ] Repetition (many, many1, optional, sepBy, sepBy1, endBy, count)
    - [ ] Lookahead (lookAhead, notFollowedBy)
    - [ ] Error handling (attempt, recover, recoverWith, named)
    - [ ] Operators (chainl1, chainr1)
- [ ] Verify compilation

### Phase 4: Primitives (30 min)
- [ ] Implement Primitives.scala
    - [ ] Character-level (char, satisfy, anyChar, oneOf, noneOf)
    - [ ] String-level (string)
    - [ ] Character classes (digit, letter, alphaNum, whitespace, spaces, spaces1)
    - [ ] Lexeme combinators (lexeme, symbol)
    - [ ] End of input (eof)
- [ ] Verify compilation

### Phase 5: Runtime (1 hour)
- [ ] Implement ParserState.scala
    - [ ] Ref class (controlled mutation)
    - [ ] ParserState class
    - [ ] StateSnapshot type (named tuple)
    - [ ] All state operations
- [ ] Implement Interpreter.scala
    - [ ] run function
    - [ ] interpret function
    - [ ] All parser case handling
- [ ] Verify basic parsing works

### Phase 6: Syntax (30 min)
- [ ] Implement Extensions.scala
    - [ ] Parser extensions (map, flatMap, |, ~, *>, <*, many, many1, etc.)
    - [ ] Result extensions (isSuccess, isFailure, toEither, toOption)
- [ ] Verify ergonomic API works

### Phase 7: Type Classes (30 min)
- [ ] Implement Abstractions.scala
    - [ ] Functor trait
    - [ ] Applicative trait
    - [ ] Monad trait
    - [ ] Show trait
    - [ ] Eq trait
- [ ] Implement Instances.scala
    - [ ] Parser Monad instance
    - [ ] Result Monad instance
    - [ ] Show instances (Location, ParseError, Result)
    - [ ] Eq instances (Location)
- [ ] Verify type class resolution

### Phase 8: Tests (1.5 hours)
- [ ] Implement ArithmeticParser.scala
    - [ ] Number parser
    - [ ] Factor parser
    - [ ] Term parser (multiplication/division)
    - [ ] Expression parser (addition/subtraction)
    - [ ] All test cases
- [ ] Implement JsonParser.scala
    - [ ] All JSON value parsers
    - [ ] All test cases
- [ ] Implement MonadLaws.scala
    - [ ] Property-based tests
    - [ ] Law verification
- [ ] Verify all tests pass: `sbt test`

### Phase 9: Documentation (30 min)
- [ ] Add scaladoc to public API
- [ ] Verify README examples work
- [ ] Final code review

---

## 🚀 Getting Started

```bash
# 1. Create project directory
mkdir parser-combinators
cd parser-combinators

# 2. Use Claude Code to implement all files from this spec

# 3. First compilation
sbt compile

# 4. Run tests
sbt test

# 5. Format code
sbt scalafmtAll

# 6. Success!
```

---

## 🎯 Success Criteria

When complete, you should have:

1. ✅ Zero case classes in the entire codebase
2. ✅ Zero sealed traits (only enums)
3. ✅ All files compile without errors
4. ✅ ArithmeticParser tests pass (6+ tests)
5. ✅ JsonParser tests pass (10+ tests)
6. ✅ Clean code (scalafmt formatted)
7. ✅ Braces-only syntax throughout

---

## 📝 Critical Reminders for Claude Code

1. **NO CASE CLASSES** - Use named tuples or regular classes only
2. **NO SEALED TRAITS** - Use enums for all ADTs
3. **BRACES ONLY** - No significant indentation
4. **Ref PATTERN** - Use Ref class for controlled mutation
5. **TOP-LEVEL FUNCTIONS** - Don't wrap in objects
6. **TEST AS YOU GO** - Compile after each file

---