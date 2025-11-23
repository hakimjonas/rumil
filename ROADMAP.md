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

## 📊 Current Status Summary (Updated: Nov 2025)

### ✅ Priority 1: COMPLETE (4/4 items)
All critical features for v0.2.0 public launch are implemented:
- [x] 1.1 Lossless, Resilient Parsing - GreenNode, error recovery, Result.Partial
- [x] 1.2 Idiomatic Scala Interop - Decoder.derived, Parser.derived, full interop module
- [x] 1.3 "Two-Faced" Documentation - Comprehensive docs, examples, dual-approach branding
- [x] 1.4 Parser Debugging Tools - .trace(), .debug() combinators

**Bonus Achievements:**
- 6 production-ready parsers (JSON, XML, TOML, CSV, YAML, Protobuf)
- 12 test suites with 100+ passing tests
- Error recovery with multi-error accumulation
- Complete API documentation in code

### ⏳ Priority 2: IN PROGRESS (1/3 items)
- [x] 2.1 Left Recursion Support - ✅ Direct left recursion via `rule` combinator
- [ ] 2.2 Comprehensive Benchmarks - Basic benchmarks exist, needs expansion
- [ ] 2.3 Memoization/Packrat - Integrated with left recursion support

### 🔮 Priority 3: NOT STARTED (3/3 items)
- [ ] 3.1 Streaming/Incremental Parsing
- [ ] 3.2 Platform Expansion (Scala.js/Native)
- [ ] 3.3 Publishing & CI/CD

**Next Steps for v0.2.0:**
1. Merge documentation PR
2. Expand benchmark suite
3. Set up CI/CD and Maven Central publishing

---

## Priority 1: Public Launch & Core Prototype (Must Have)

**Goal:** Deliver a complete, production-ready library that serves both as a useful Scala tool and a validation of Structural-First Design principles.

### 1.1 Lossless, Resilient Parsing (Core Prototype)
**Status:** ✅ COMPLETE (PR #7 merged)
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
**Status:** ✅ COMPLETE (PR #9 merged, PR #6 merged)
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
**Status:** ✅ COMPLETE (Branch ready, awaiting merge)
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
**Status:** ✅ COMPLETE (PR #8 merged)
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

## ✨ Bonus Features (Implemented Beyond Original Plan)

The following features were implemented but weren't in the original Priority 1 roadmap:

### Format-Specific Parsers (Fully Implemented)
**Status:** ✅ COMPLETE
**Files:** `parsers/src/main/scala/parsers/*`

Production-ready parsers for multiple formats:
- **JSON** - Full JSON parser with 61 passing tests
- **XML** - Complete XML parser with attribute support
- **TOML** - TOML configuration parser
- **CSV** - CSV parser with customizable delimiters
- **YAML** - YAML parser (basic support)
- **Protobuf** - Protocol buffer parser

All parsers include:
- Comprehensive test suites
- Type-safe value representations
- Integration with Decoder typeclass

### Parser.derived for Case Classes
**Status:** ✅ COMPLETE
**File:** `interop/src/main/scala/parser/interop/Derivation.scala`

Automatic parser generation for case classes:
```scala
case class Person(name: String, age: Int)
val parser = Parser.derived[Person]
```

This was mentioned in ROADMAP 1.2 but is a separate feature from Decoder.derived.

---

## Priority 2: Grammar Power & Performance (Should Have)

**Goal:** Make Rumil robust for complex, real-world grammars and production workloads.

### 2.1 Left Recursion Support
**Status:** ✅ COMPLETE (Direct left recursion)
**Impact:** High

**Keep from original 2.3**

Common grammars (arithmetic, function calls) are naturally left-recursive. The `rule` combinator now handles direct left recursion automatically.

**Features:**
- ✅ Automatic left-recursion detection via seed-growth algorithm (Warth et al.)
- ✅ Memoization table with cycle detection
- ✅ `rule { }` combinator for declaring left-recursive parsers
- ⚠️ Indirect left recursion (mutually recursive rules) not yet supported

**Example:**
```scala
// Direct left recursion now works with rule:
lazy val expr: Parser[ParseError, Int] = rule {
  (expr ~ char('+') ~ digit).map { case ((a, _), b) => a + (b - '0') } |
  digit.map(_ - '0')
}
expr.run("1+2+3") // Success(6, 5) - left associative!

// chainl1/chainr1 still available for complex grammars:
val expr = digitP.chainl1(addOp)  // Also works
```

**Implementation Notes:**
- `Parser.Memo` case wraps parser with unique identity
- `LR`/`LRHead` track recursive cycles during parsing
- Seed-growth loop re-parses until no more progress

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
**Status:** 🎯 READY (Pending documentation PR merge)

**Delivered:**
- ✅ Lossless, resilient parsing with GreenNode (1.1) - COMPLETE
- ✅ Decoder.derived for automatic case class decoding (1.2) - COMPLETE
- ✅ Parser.derived for automatic case class parsing (1.2+) - COMPLETE
- ✅ "Two-Faced" documentation with Structural-First branding (1.3) - COMPLETE
- ✅ Debugging tools (.trace, .debug) (1.4) - COMPLETE
- ✅ BONUS: 6 production parsers (JSON, XML, TOML, CSV, YAML, Protobuf)

**Remaining for v0.2.0:**
- 📝 Merge documentation PR (#10 pending)
- 📊 Comprehensive benchmarks (2.2) - Basic benchmarks exist, needs expansion
- 🚀 Publishing & CI/CD (3.3) - Setup needed

**Achievement:**
- Production-ready Scala library ✅
- Proven Structural-First Design philosophy ✅
- Thought leadership established ✅
- Portfolio piece complete ✅

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
