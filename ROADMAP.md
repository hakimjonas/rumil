# Rumil Development Roadmap

## Strategic Vision: Core / Adapter Architecture

Rumil is built on a **two-layer architecture** that serves dual purposes:

### **The Core Layer: Structural-First Design**
A pure, principled parser combinator library using:
- **Enums** for sum types (`Parser[E, A]`, `Result[E, A]`)
- **Named Tuples** for product types (`Location`, `Span`)
- **Controlled mutation** only in the interpreter shell (`ParserState`)

This layer represents a **portable, minimal design** that prioritizes:
- Correctness over convenience
- Explicit structure over implicit magic
- Type safety without ceremony

**Philosophy:** "Structural-First Design" - building parsers from first principles using only the essential type constructs.

### **The Adapter Layer: Idiomatic Scala Interop**
An ergonomic compatibility layer (`rumil-interop`) that provides:
- Automatic `Parser.derived[CaseClass]` for Scala case classes
- `Decoder[A]` typeclass for JSON/XML/etc. → case class conversion
- Seamless integration with the Scala ecosystem (Circe, Cats, etc.)

**Philosophy:** "Meet users where they are" - provide idiomatic Scala ergonomics without compromising core purity.

### **Why This Matters**
This architecture enables:
1. **Portfolio Value:** A production-ready Scala library with automatic derivation
2. **Design Validation:** Testing principled architecture patterns in a real library
3. **Thought Leadership:** Establishing "Structural-First Design" as a coherent philosophy
4. **Maximum Reach:** Serving both functional purists and pragmatic Scala developers

---

## Priority 1: Public Launch & Core Prototype (Must Have)

**Goal:** Deliver a complete, production-ready library that serves both as a useful Scala tool and a validation of Structural-First Design principles.

### 1.1 Lossless, Resilient Parsing (Core Prototype)
**Status:** Not Started
**Impact:** Critical

**Combines:** Original items 1.3 (Position-Aware) + 3.3 (Error Recovery)

This is the **true core prototype**. A resilient, GreenNode-producing parser that never panics and preserves all source information.

**Features:**
- `GreenNode` syntax tree with lossless round-trip property
- Error recovery combinators (`skipUntil`, `recoverWith`, `optional`)
- Multi-error accumulation (parse doesn't stop on first error)
- Full position tracking (`Span`, `Location`) for every node
- Fault-tolerant parsing suitable for IDE integration

**Example:**
```scala
// Resilient JSON parser that collects all errors
val jsonParser: Parser[ParseError, (JsonValue, List[ParseError])]

// Produces GreenNode tree preserving whitespace, comments
val syntaxTree: GreenNode = jsonParser.parseToSyntaxTree(input)
```

**Why Critical:**
- Differentiates Rumil from fastparse/cats-parse (most don't have lossless trees)
- Enables IDE tooling (formatters, refactoring, syntax highlighting)
- Validates core architecture under stress (error handling, backtracking)
- Foundation for all advanced features

**Marketing:** "Rumil produces lossless syntax trees like scalameta, making it ideal for building language tooling, not just parsing."

---

### 1.2 Idiomatic Scala Interop (Public Adapter)
**Status:** Not Started
**Impact:** Critical

**NEW ITEM** - This is the key to public adoption.

Create `rumil-interop` module with automatic case class derivation:

**Features:**
```scala
// Automatic parser derivation
case class Person(name: String, age: Int)
val parser: Parser[ParseError, Person] = Parser.derived[Person]

// Decoder typeclass for structured data
trait Decoder[A] {
  def decode(value: JsonValue): Result[ParseError, A]
}

object Decoder {
  inline def derived[A]: Decoder[A] = // automatic derivation
}

// Usage
case class Config(host: String, port: Int, tls: Boolean)
val config: Config = jsonString.parse.flatMap(Decoder[Config].decode)
```

**Implementation:**
- Scala 3 inline/macro derivation
- Automatic field name mapping
- Nested case class support
- Custom field transformations (`@JsonKey`, `@JsonIgnore`)
- Integration with existing codecs (Circe compatibility layer)

**Why Critical:**
- Makes Rumil **100x more useful** to Scala community
- Removes friction for new users ("just use case classes")
- Enables ecosystem integration (JSON/XML → case classes)
- Demonstrates pragmatism, not ideological rigidity

**Marketing:** "Use Rumil with your existing case classes - automatic derivation just works."

---

### 1.3 "Two-Faced" Documentation & Examples (Public Branding)
**Status:** Basic README exists
**Impact:** Critical

**Refocus:** Original item 3.1 (Tutorial) + new branding strategy

The documentation must be the **public manifesto** for Structural-First Design.

**Structure:**

#### **Public Branding:**
- Create clear brand: **"Structural-First Design"** or **"Principled Parsing"**
- Explain philosophy without mentioning private implementation details
- Position as thought leadership, not product feature

#### **Dual-Track Tutorials:**

Every tutorial shows **both approaches** side-by-side:

**Example: Parsing JSON**

```markdown
## The Structural Way (Explicit, Portable)

Uses pure combinators and named tuples. Maximum control.

type JsonObject = List[(key: String, value: JsonValue)]

val jsonObject: Parser[ParseError, JsonObject] =
  (quotedString <* char(':') <* ws) ~ jsonValue

**When to use:** Building language tooling, need lossless trees,
maximum portability.

---

## The Idiomatic Way (Ergonomic, Scala-Friendly)

Uses automatic derivation for case classes. Maximum convenience.

case class User(name: String, age: Int, admin: Boolean)
val userParser = Parser.derived[User]

**When to use:** Standard CRUD apps, data validation,
REST API parsing.
```

#### **Advanced Topics:**
- **GreenNode Feature:** Market as "high-fidelity language tooling"
- **Error Recovery:** Show IDE use case (syntax highlighting with errors)
- **Performance:** Benchmarks vs fastparse/cats-parse
- **Migration Guides:** From other libraries

**Why Critical:**
- First impressions matter - docs are the product
- Establishes thought leadership on design philosophy
- Shows both purist and pragmatist paths
- Differentiates Rumil from competitors

---

### 1.4 Parser Debugging Tools (Development UX)
**Status:** Not Started
**Impact:** High

**Keep from original 1.2** - Essential for debugging complex parsers.

**Features:**
```scala
val number = digit.many1.trace("number").map(_.mkString.toInt)
val expr = (number ~ operator ~ number).debug("expression")

// Output:
// [TRACE] number: trying at offset 5
// [TRACE] number: success, consumed "42" (2 chars)
// [DEBUG] expression: trying at offset 5
// [DEBUG] expression: success, parsed Add(42, 17)
```

**Why Important:**
- Invaluable during resilient parser development
- Users will need this for their own parsers
- Low effort, high value

---

## Priority 2: Grammar Power & Performance (Should Have)

**Goal:** Make Rumil robust for complex, real-world grammars and production workloads.

### 2.1 Left Recursion Support
**Status:** Not Started
**Impact:** High

**Keep from original 2.3**

Common grammars (arithmetic, function calls) are naturally left-recursive. Current workarounds (`chainl1`) are awkward.

**Features:**
- Automatic left-recursion detection
- Seed-growth algorithm (Warth et al.)
- Clear error messages for unhandled cases

**Example:**
```scala
// Currently requires chainl1 workaround
val expr = chainl1(term, addOp)

// With left-recursion support:
lazy val expr: Parser[Expr] =
  expr ~ addOp ~ term | term  // Just works!
```

---

### 2.2 Comprehensive Benchmarks Suite
**Status:** Basic benchmarks exist
**Impact:** High

**Keep from original 2.4**

**Goal:** Publicly prove Rumil is fast.

**Benchmarks:**
- vs fastparse (industry standard)
- vs cats-parse (pure FP)
- vs parsley (Haskell-inspired)
- JSON, XML, TOML, CSV parsing
- Expression evaluation
- Error recovery overhead

**Publish results** in README and docs.

---

### 2.3 Memoization / Packrat Parsing
**Status:** Not Started
**Impact:** Medium

**Keep from original 2.2**

Trade memory for speed in complex grammars.

```scala
val identifier = letter.many1.memoize  // Cache results
```

---

## Priority 3: Advanced & Ecosystem Features (Nice to Have)

**Goal:** Expand Rumil's reach after core product is proven.

### 3.1 Streaming / Incremental Parsing
**Status:** Not Started
**Impact:** High (for specific use cases)

**Keep from original 2.1**

Parse massive files (logs, large JSON) without loading into memory.

```scala
val parser: Parser[Event] = eventParser
val events: Iterator[Event] = parser.parseStream(fileIterator)
```

---

### 3.2 Platform Expansion (Scala.js / Native)
**Status:** Not Started
**Impact:** Medium

**Keep from original 5.1, 5.2**

- **Scala.js:** Browser-based parser playgrounds, web apps
- **Scala Native:** CLI tools with fast startup, low memory

Both expand the library's appeal for portfolio.

---

### 3.3 Publishing & CI/CD
**Status:** Not Started
**Impact:** Critical (for public launch)

**Keep from original 6.1, 6.2**

- Publish to Maven Central
- GitHub Actions CI/CD
- Automated releases
- Code coverage reporting

**Final step** for public release.

---

## Milestones (Revised)

### v0.2.0 - Public Launch Release 🚀

**Delivers:**
- ✅ Lossless, resilient parsing with GreenNode (1.1)
- ✅ Case class derivation in rumil-interop (1.2)
- ✅ "Two-Faced" documentation with Structural-First branding (1.3)
- ✅ Debugging tools (trace, debug) (1.4)
- ✅ Comprehensive benchmarks (2.2)

**Outcome:**
- Production-ready Scala library
- Proven Structural-First Design philosophy
- Thought leadership established
- Portfolio piece complete

---

### v0.3.0 - Performance & Power Release

**Delivers:**
- ✅ Left recursion support (2.1)
- ✅ Memoization/packrat parsing (2.3)
- ✅ Streaming API (3.1)

**Outcome:**
- Handles complex, real-world grammars
- Competitive performance with fastparse

---

### v0.4.0 - Platform Expansion

**Delivers:**
- ✅ Scala.js support (3.2)
- ✅ Scala Native support (3.2)
- ✅ Published to Maven Central (3.3)

**Outcome:**
- Multi-platform library
- Publicly available on Maven Central

---

## Strategic Summary

This roadmap achieves **dual goals** with maximum synergy:

### For Your Portfolio:
- **Public Launch (v0.2.0):** A production-ready Scala library with automatic case class derivation
- **Benchmarks:** Publicly proven performance vs fastparse/cats-parse
- **Documentation:** Establishes you as a thought leader on "Structural-First Design"
- **Maven Central:** Widely available, professionally published

### For Design Validation:
- **Core Layer:** Tests Structural-First principles (enums, named tuples) under stress
- **Resilient Parsing:** Validates architecture with complex error handling
- **GreenNode:** Proves lossless tree concept in practice
- **Multi-Platform:** Demonstrates true portability (JVM, JS, Native)

### Risk Mitigation:
- **Public Brand:** "Structural-First Design" establishes prior art for your philosophy
- **Pragmatic Approach:** Adapter layer shows you're not dogmatic
- **Portfolio First:** Build reputation before revealing high-risk platform plays

---

## Quick Wins (Immediate Value)

These can be implemented quickly for rapid progress:

1. **Basic GreenNode structure** - Foundation for 1.1
2. **trace combinator** - Item 1.4
3. **Case class derivation POC** - Validate 1.2 approach
4. **Restructure docs** - Add "Two-Faced" section template

---

## Notes

- **No Breaking Changes:** Maintain backward compatibility through v0.x series
- **Community-Driven:** Priorities may shift based on user feedback
- **Test Coverage:** Every feature requires comprehensive tests
- **Documentation First:** No feature ships without examples and guides
- **Progressive Disclosure:** Show simple path first, advanced options later
