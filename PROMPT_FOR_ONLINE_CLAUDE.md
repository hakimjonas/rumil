# Task: Case Class Derivation POC for Rumil

I need your help implementing automatic parser derivation for Scala case classes using Scala 3 macros. This is a proof of concept for the `rumil-interop` module.

## Context

Rumil is a Scala 3 parser combinator library. You can see a working example of Scala 3 derivation in the Valar validation library at `../valar/valar-core/src/main/scala/net/ghoula/valar/internal/Derivation.scala`. Study that implementation - it shows exactly how to use `Mirror.ProductOf`, extract field labels, summon typeclass instances, and use macros.

## Goal

Create automatic derivation so users can write:
```scala
case class Person(name: String, age: Int)
val parser = Parser.derived[Person]
parser.run("Person(Alice,30)")  // Success(Person("Alice", 30))
```

## Implementation Steps

### 1. Add Interop Module

Add to `build.sbt`:
```scala
lazy val interop = project
  .in(file("interop"))
  .settings(
    name := "rumil-interop",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test
    )
  )
  .dependsOn(core % "compile->compile;test->test")
```

### 2. Create Primitive Parsers

File: `interop/src/main/scala/parser/interop/Primitives.scala`

Before derivation works, we need given instances for primitive types:

```scala
package parser.interop

import parser.core.*
import parser.syntax.*

object Primitives {
  given Parser[ParseError, String] =
    letter.many1.map(_.mkString).named("String")

  given Parser[ParseError, Int] =
    digit.many1.map(_.mkString.toInt).named("Int")

  given Parser[ParseError, Boolean] =
    (string("true").as(true) | string("false").as(false)).named("Boolean")

  given Parser[ParseError, Long] =
    digit.many1.map(_.mkString.toLong).named("Long")

  given Parser[ParseError, Double] =
    (digit.many1 ~ char('.') ~ digit.many1)
      .map { case ((whole, _), decimal) => s"$whole.$decimal".toDouble }
      .named("Double")
}
```

### 3. Create Derivation Implementation

File: `interop/src/main/scala/parser/interop/Derivation.scala`

**Study Valar first:** Look at `../valar/valar-core/src/main/scala/net/ghoula/valar/internal/Derivation.scala` to see the pattern.

Key techniques you'll need:

1. **Extract field labels from tuple type:**
```scala
def getLabels[Labels <: Tuple: Type](using q: Quotes): List[String] = {
  import q.reflect.*
  def loop(tpe: TypeRepr): List[String] = tpe.dealias match {
    case AppliedType(_, List(head, tail)) =>
      head match {
        case ConstantType(StringConstant(label)) => label :: loop(tail)
        case _ => report.errorAndAbort("Invalid field label")
      }
    case t if t =:= TypeRepr.of[EmptyTuple] => Nil
    case _ => report.errorAndAbort("Invalid tuple structure")
  }
  loop(TypeRepr.of[Labels])
}
```

2. **Summon parsers for each field:**
```scala
def summonParsers[Elems <: Tuple: Type](using q: Quotes): List[Expr[Parser[ParseError, Any]]] =
  Type.of[Elems] match {
    case '[EmptyTuple] => Nil
    case '[h *: t] =>
      val parser = Expr.summon[Parser[ParseError, h]].getOrElse {
        report.errorAndAbort(s"No parser for ${Type.show[h]}")
      }
      parser.asExprOf[Parser[ParseError, Any]] :: summonParsers[t]
  }
```

3. **Generate parser code with quotes/splices:**
```scala
'{
  // Build parser that:
  // 1. Parses class name
  // 2. Parses '('
  // 3. Parses each field separated by ','
  // 4. Parses ')'
  // 5. Constructs case class using mirror.fromProduct
}
```

**Format:** Parse as `ClassName(field1,field2,field3)`

### 4. Create Comprehensive Tests

File: `interop/src/test/scala/parser/interop/DerivationTests.scala`

Look at `../valar/valar-core/src/test/scala/net/ghoula/valar/ValidationSpec.scala` for testing patterns.

Minimum 10 tests:
- Simple case class (2 fields)
- Different field types
- Multiple case classes
- Parsing failures
- Edge cases

Example structure:
```scala
package parser.interop

import munit.FunSuite
import parser.core.*
import parser.interop.Primitives.given

class DerivationTests extends FunSuite {
  test("derive parser for simple case class") {
    case class Person(name: String, age: Int)
    given Parser[ParseError, Person] = Parser.derived[Person]

    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,30)") match {
      case Result.Success(person, _) =>
        assertEquals(person.name, "Alice")
        assertEquals(person.age, 30)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  // Add 9+ more tests
}
```

## POC Scope

**Support:**
- ✅ Simple case classes with String, Int, Long, Boolean, Double fields
- ✅ Format: `ClassName(field1,field2)`

**Out of Scope:**
- ❌ Nested case classes
- ❌ Option[A] fields
- ❌ List[A] fields
- ❌ JSON/XML syntax

## Key Resources

**Study these files first:**
1. `../valar/valar-core/src/main/scala/net/ghoula/valar/internal/Derivation.scala` - Macro implementation
2. `../valar/valar-core/src/test/scala/net/ghoula/valar/ValidationSpec.scala` - Testing patterns
3. `core/src/main/scala/parser/core/Types.scala` - Rumil's Parser type
4. `core/src/main/scala/parser/syntax/Combinators.scala` - Available combinators

**Existing Rumil combinators:**
- `char(c)`, `string(s)` - Basic matching
- `p1 ~ p2` - Sequence parsers
- `p1 | p2` - Alternative
- `p.map(f)` - Transform result
- `p.many1` - One or more
- `p.named(name)` - Label for errors

## Success Criteria

- [ ] `sbt compile` succeeds
- [ ] `sbt test` passes (all tests)
- [ ] Can derive parsers for simple case classes
- [ ] 10+ tests all passing
- [ ] Clear code documentation

---

## GIT REQUIREMENTS (CRITICAL)

### Branch
Create: `feature/case-class-derivation-poc`

### Author
**DO NOT configure git!** It's already set.

Just commit normally:
```bash
git commit -m "Your message"
```

**DO NOT:**
- Run `git config user.name` or `git config user.email`
- Use "Claude" as author
- Add attribution footers to commit messages

**Verify after committing:**
```bash
git log -1 --format='%an %ae'
```
Expected: `Hakim Jonas Ghoula hakim@ghoula.net`

If you see "Claude" - DO NOT PUSH!

### Commit Message Format
```
Title (imperative mood, max 50 chars)

- Bullet points
- Explain what and why
- Keep it concise
```

NO footers, NO attribution lines.

---

## Getting Started

1. Read Valar's derivation code first (it's the template)
2. Create the interop module in build.sbt
3. Implement primitive parsers
4. Implement derivation following Valar's pattern
5. Add comprehensive tests
6. Verify git author is correct

Good luck! Let me know if you have questions.
