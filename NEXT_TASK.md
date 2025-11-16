# Task: Case Class Derivation POC

## Overview
Implement a proof of concept for automatic parser derivation for Scala case classes. This validates the approach for the `rumil-interop` module (ROADMAP section 1.2).

## Objectives
1. Create a basic `Parser.derived[A]` mechanism using Scala 3 macros
2. Support simple case classes with primitive fields (String, Int, Boolean)
3. Demonstrate the concept with working examples
4. Add comprehensive tests

## Technical Approach

### Step 1: Create Interop Module Structure
Create a new subproject in `build.sbt`:
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

### Step 2: Implement Basic Derivation
Create `interop/src/main/scala/parser/interop/Derivation.scala`:

```scala
package parser.interop

import scala.deriving.*
import scala.compiletime.*
import parser.core.*
import parser.syntax.*

trait Derivation {
  /**
   * Derives a parser for a case class.
   *
   * Currently supports:
   * - Case classes with String, Int, Long, Boolean, Double fields
   * - Simple product types (no nested case classes yet)
   */
  inline def derived[A](using m: Mirror.ProductOf[A]): Parser[ParseError, A] = {
    // POC: Start with simple field parsing
    // Real implementation would use Mirror to inspect fields
    ???
  }
}

object Parser extends Derivation
```

### Step 3: Create Examples
Create `interop/src/test/scala/parser/interop/DerivationTests.scala`:

```scala
package parser.interop

import munit.FunSuite
import parser.core.*

class DerivationTests extends FunSuite {
  test("derive parser for simple case class") {
    case class Person(name: String, age: Int)

    val parser = Parser.derived[Person]
    // Example: expects format "Person(Alice,30)"
    val result = parser.run("Person(Alice,30)")

    assert(result.isInstanceOf[Result.Success[?, ?]])
    // Add proper assertions
  }

  test("derive parser for case class with multiple field types") {
    case class Config(host: String, port: Int, enabled: Boolean)

    val parser = Parser.derived[Config]
    val result = parser.run("Config(localhost,8080,true)")

    assert(result.isInstanceOf[Result.Success[?, ?]])
  }
}
```

### Step 4: POC Limitations (Document These)
This POC should:
- ✅ Work with simple case classes
- ✅ Support basic field types (String, Int, Boolean, Double, Long)
- ❌ NOT support nested case classes yet
- ❌ NOT support Option[A] fields yet
- ❌ NOT support List[A] fields yet
- ❌ NOT have custom field name mapping yet

Document these as "Future Work" in the implementation.

## Expected Deliverables

1. **New Module:** `interop/` directory with proper build.sbt configuration
2. **Primitive Parsers:** Basic parsers for String, Int, Boolean, etc.
3. **Derivation Implementation:** `Derivation.scala` with macro-based `Parser.derived[A]`
4. **Tests:** Comprehensive test suite (10+ tests)
5. **Documentation:** Comments explaining the approach and limitations

### Primitive Parsers to Implement

Before derivation works, you need basic parsers for primitive types. Create in `interop/src/main/scala/parser/interop/Primitives.scala`:

```scala
package parser.interop

import parser.core.*
import parser.syntax.*

object Primitives {
  // Basic primitive parsers
  given Parser[ParseError, String] =
    letter.many1.map(_.mkString).named("String")

  given Parser[ParseError, Int] =
    digit.many1.map(_.mkString.toInt).named("Int")

  given Parser[ParseError, Boolean] =
    (string("true").as(true) | string("false").as(false)).named("Boolean")

  given Parser[ParseError, Long] =
    digit.many1.map(_.mkString.toLong).named("Long")

  given Parser[ParseError, Double] =
    // Simple implementation - improve as needed
    (digit.many1 ~ char('.') ~ digit.many1)
      .map { case ((whole, _), decimal) => s"$whole.$decimal".toDouble }
      .named("Double")
}
```

## Testing Requirements
- Minimum 10 tests covering different case class shapes
- Tests should validate correct parsing AND correct field values
- Include failure cases (malformed input, type mismatches)

## Success Criteria
- All tests pass
- `sbt test` succeeds for both core and interop modules
- Code demonstrates Scala 3 derivation concepts
- Clear path forward for full implementation

---

## GIT REQUIREMENTS (ABSOLUTELY CRITICAL - READ CAREFULLY)

### BRANCH NAMING
Create branch: `feature/case-class-derivation-poc`
- Use "feature/" prefix (not "claude/")
- Use descriptive name with task purpose

### AUTHOR CONFIGURATION
**CRITICAL:** DO NOT set git author yourself!

The git config is already set correctly on this machine:
- Name: Hakim Jonas Ghoula
- Email: hakim@ghoula.net

**DO NOT:**
- Set git user.name or user.email
- Use "Claude" as author name
- Use "noreply@anthropic.com" as email
- Run ANY git config commands
- Add any attribution footers to commit messages

**DO:**
- Just commit normally - git will use the existing config automatically
- Write clear commit messages without footers
- Verify author after committing

### COMMIT MESSAGE FORMAT
```
Title in imperative mood (max 50 chars)

- Bullet points explaining what changed
- Focus on WHY not just WHAT
- Keep it concise

NO FOOTERS - NO attribution text - NO "Generated with" lines
```

### VERIFICATION STEP
After committing, ALWAYS verify:
```bash
git log -1 --format='%an %ae'
```

Expected output: `Hakim Jonas Ghoula hakim@ghoula.net`

If you see "Claude <noreply@anthropic.com>" - DO NOT PUSH! You made a mistake.

---

## Implementation Notes

### Scala 3 Derivation Approach (Inspired by Valar)

**Reference:** See Valar's `Derivation.scala` at `../valar/valar-core/src/main/scala/net/ghoula/valar/internal/Derivation.scala`

Use macros with `scala.deriving.Mirror` for compile-time introspection:

```scala
import scala.deriving.Mirror
import scala.quoted.{Expr, Quotes, Type}

inline def derived[A](using m: Mirror.ProductOf[A]): Parser[ParseError, A] =
  ${ deriveParserImpl[A]('m) }

def deriveParserImpl[A: Type](m: Expr[Mirror.ProductOf[A]])(using q: Quotes): Expr[Parser[ParseError, A]] = {
  import q.reflect.*

  // Extract field labels (similar to Valar's getLabels)
  // Extract field types
  // Summon Parser[E, T] for each field type
  // Generate parser code using quotes/splices
  // Reconstruct case class using mirror.fromProduct
}
```

**Key Techniques from Valar:**

1. **Extract field labels** - Recursively process `Mirror.MirroredElemLabels`:
   ```scala
   def getLabels[Labels <: Tuple: Type](using q: Quotes): List[String] = {
     // Pattern match on tuple types: EmptyTuple | h *: t
     // Extract ConstantType(StringConstant(label))
   }
   ```

2. **Summon parsers for each field** - Use `Expr.summon`:
   ```scala
   Type.of[Elems] match {
     case '[EmptyTuple] => Nil
     case '[h *: t] =>
       val parser = Expr.summon[Parser[ParseError, h]].getOrElse {
         report.errorAndAbort(s"No parser for ${Type.show[h]}")
       }
       parser :: summonParsers[t]
   }
   ```

3. **Generate code with quotes/splices**:
   ```scala
   '{
     new Parser[ParseError, A] {
       // Use ${ parsersExpr } to splice in summoned parsers
       // Use ${ m } to splice in mirror
     }
   }
   ```

4. **Reconstruct product**:
   ```scala
   mirror.fromProduct(Tuple.fromArray(values.toArray))
   ```

### Parser Building Strategy
For a case class `Person(name: String, age: Int)`, generate parser equivalent to:
```scala
val personParser =
  string("Person") *> char('(') *>
  summon[Parser[ParseError, String]] <* char(',') ~
  summon[Parser[ParseError, Int]] <* char(')') map { case (name, age) =>
    Person(name, age)
  }
```

### References
- **Valar derivation:** `../valar/valar-core/src/main/scala/net/ghoula/valar/internal/Derivation.scala`
- **Valar tests:** `../valar/valar-core/src/test/scala/net/ghoula/valar/ValidationSpec.scala`
- Scala 3 macros: https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html

---

## Questions to Consider (Optional)

1. Should we support JSON-style syntax or custom format?
2. What should field separators be? (comma, whitespace, etc.)
3. Should we generate parsers for companion object apply methods?

For POC, make reasonable choices and document them.

---

## Final Checklist
- [ ] New interop module in build.sbt
- [ ] Derivation.scala with Parser.derived
- [ ] Minimum 10 tests all passing
- [ ] Author is "Hakim Jonas Ghoula <hakim@ghoula.net>"
- [ ] NO commit message footers
- [ ] Branch name is "feature/case-class-derivation-poc"
- [ ] Code compiles with `sbt compile`
- [ ] Tests pass with `sbt test`
- [ ] Clear comments explaining the approach
