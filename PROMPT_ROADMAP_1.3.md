# Complete ROADMAP 1.3: "Two-Faced" Documentation & Examples

## Objective

Complete the **last remaining item from Priority 1** before v0.2.0 public launch: comprehensive documentation that establishes Rumil's "Structural-First Design" philosophy while showcasing both the pure combinator approach and the idiomatic Scala interop layer.

## Context: What's Already Built

### ✅ Completed Features (Priority 1)

1. **Resilient Parsing (1.1)** - Error recovery, multi-error accumulation, position tracking
2. **Decoder Typeclass (1.2)** - Automatic case class derivation from JsonValue
3. **Debug Combinators (1.4)** - `.trace()` and `.debug()` methods for parser debugging

### Current Documentation State

- **README.md exists** with good structure, dual-approach philosophy, and examples
- **BUT**: Multiple sections say "Coming in v0.2.0" even though Decoder is now implemented
- **BUT**: Decoder examples use hypothetical API, not the actual implementation
- **No examples/ directory** with runnable code
- **No docs/ directory** with detailed tutorials

## Requirements from ROADMAP.md

From section 1.3:

> The documentation must be the **public manifesto** for Structural-First Design.

### Public Branding
- Establish **"Structural-First Design"** as a coherent philosophy
- Explain it without mentioning private implementation details
- Position as thought leadership, not just product features

### Dual-Track Tutorials
Every tutorial must show **both approaches** side-by-side:
- **Structural Way**: Pure combinators, named tuples, maximum control
- **Idiomatic Way**: Case class derivation, automatic decoders, maximum convenience
- Clear "When to use" guidance for each

### Advanced Topics to Cover
- Error recovery and resilient parsing (feature 1.1)
- Debug combinators (feature 1.4)
- Performance characteristics
- Migration from other libraries (fastparse, cats-parse)

## Task Breakdown

### Task 1: Update README.md

**Current Issues:**
1. Lines 37, 67, 78, 164, 228, 275 say "Coming in v0.2.0" or "Coming Soon"
2. Decoder examples show wrong API (e.g., `Decoder[User]` instead of `Decoder[JsonValue, User]`)
3. Missing proper imports for JsonDecoders

**Required Changes:**

1. **Remove all "Coming in v0.2.0" text** - The features are NOW available

2. **Fix Decoder API examples** - Use actual implementation:
   ```scala
   // Current (WRONG):
   val user = parseJson(input).flatMap(Decoder[User].decode)

   // Correct (RIGHT):
   import parser.interop.Decoder
   import parser.interop.JsonDecoders.given

   case class User(name: String, age: Int)
   given Decoder[JsonValue, User] = Decoder.derived

   val jsonResult: Result[ParseError, JsonValue] = JsonParser.parseValue.run(input)
   val userResult: Result[DecodeError, User] = jsonResult.flatMap(json =>
     Decoder[JsonValue, User].decode(json)
   )
   ```

3. **Add real imports** to all code examples:
   - `import parser.core._`
   - `import parser.syntax._` (if using operator syntax)
   - `import parser.interop._` (for Decoder)
   - `import parser.interop.JsonDecoders.given` (for primitive decoders)

4. **Add "Installation" section** with actual Maven coordinates (once published)

5. **Enhance "Choosing an Approach" table** - Add rows for:
   - Error recovery / resilient parsing → Structural
   - Debugging parsers → Both (debug combinators work everywhere)
   - IDE tooling / lossless trees → Structural

### Task 2: Create examples/ Directory

Create **runnable example programs** demonstrating both approaches.

**Structure:**
```
examples/
├── json-to-case-class/
│   ├── README.md
│   ├── StructuralExample.scala    # Pure combinators
│   └── IdiomaticExample.scala     # Decoder.derived
├── nested-structures/
│   ├── README.md
│   └── Example.scala              # Nested case classes with Decoder
├── error-recovery/
│   ├── README.md
│   └── Example.scala              # Show resilient parsing (1.1)
├── debugging-parsers/
│   ├── README.md
│   └── Example.scala              # Show .trace() and .debug() (1.4)
└── expression-evaluator/
    ├── README.md
    ├── StructuralExample.scala
    └── IdiomaticExample.scala
```

**Each example must:**
- Be a complete, runnable program with `@main def` entry point
- Include expected input and output in comments
- Demonstrate one clear concept
- Work with copy-paste (no missing imports)

**Example template:**
```scala
//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:0.1.0"
//> using dep "net.ghoula::rumil-interop:0.1.0"

package examples.jsontocaseclass

import parser.core._
import parser.interop._
import parser.interop.JsonDecoders.given
import parsers.json.{JsonParser, JsonValue}

/**
 * Example: Parsing JSON to Case Classes (Idiomatic Way)
 *
 * Input:  {"name": "Alice", "age": 30, "admin": true}
 * Output: User("Alice", 30, true)
 */
@main def idiomaticJsonExample(): Unit = {
  case class User(name: String, age: Int, admin: Boolean)

  // Derive decoder automatically
  given Decoder[JsonValue, User] = Decoder.derived

  val input = """{"name": "Alice", "age": 30, "admin": true}"""

  // Parse JSON
  val jsonResult = JsonParser.parseValue.run(input)

  // Decode to case class
  val userResult = jsonResult.flatMap(json =>
    Decoder[JsonValue, User].decode(json)
  )

  userResult match {
    case Result.Success(user, _) =>
      println(s"Parsed user: $user")
    case Result.Failure(errors, _) =>
      println(s"Failed to parse: $errors")
    case Result.Partial(user, errors, _) =>
      println(s"Partially parsed: $user, errors: $errors")
  }
}
```

### Task 3: Create docs/ Directory

Create **detailed tutorial documentation**.

**Structure:**
```
docs/
├── getting-started.md       # Installation, first parser, basic concepts
├── structural-approach.md   # Deep dive on pure combinators
├── idiomatic-approach.md    # Deep dive on Decoder.derived
├── error-handling.md        # Resilient parsing, error recovery
├── debugging.md             # Using .trace() and .debug()
├── performance.md           # Benchmarks, optimization tips
└── migration-guide.md       # From fastparse, cats-parse, etc.
```

**Each doc must:**
- Start with a clear learning objective
- Show complete, working examples
- Explain WHY, not just HOW
- Link to relevant API docs
- Include a "Next Steps" section

**Example structure for `idiomatic-approach.md`:**
```markdown
# The Idiomatic Approach: Automatic Case Class Derivation

## Learning Objective

Learn how to use Rumil's `Decoder.derived` macro to automatically parse structured
data into Scala case classes with zero boilerplate.

## When to Use This Approach

✅ Use when:
- Parsing JSON/XML/TOML to domain models
- Building REST API clients
- Reading configuration files
- You want concise, maintainable code

❌ Don't use when:
- Building language tooling (use Structural approach)
- Need lossless syntax trees
- Require custom data representations

## Basic Example

[Complete working code here]

## How It Works

[Explanation of macro derivation, field name mapping, etc.]

## Advanced Usage

### Nested Case Classes
[Example]

### Optional Fields
[Example]

### Collections
[Example]

### Custom Field Names (Coming in v0.3.0)
[Future feature preview]

## Comparison with Structural Approach

[Side-by-side comparison]

## Next Steps

- [Error Handling Guide](error-handling.md)
- [Debugging Guide](debugging.md)
- [Examples Directory](../examples/)
```

### Task 4: Enhance API Documentation (Scaladoc)

Add comprehensive scaladoc comments to key APIs:

**Files to enhance:**
1. `interop/src/main/scala/parser/interop/Decoder.scala`
   - Add detailed trait-level doc explaining Decoder vs Parser
   - Document `derived` method with examples
   - Explain covariance and why it matters

2. `interop/src/main/scala/parser/interop/JsonDecoders.scala`
   - Document each given decoder
   - Show usage examples
   - Explain error cases

3. `core/src/main/scala/parser/core/Parser.scala` (if missing)
   - Document debug combinators (`.trace`, `.debug`)
   - Document error recovery combinators (`.recover`, `.recoverWith`, `.attempt`)

**Example scaladoc:**
```scala
/**
 * Typeclass for decoding structured data into Scala types.
 *
 * Unlike Parser which operates on raw strings, Decoder operates on
 * pre-parsed structured data (JsonValue, XmlNode, TomlValue, etc.).
 *
 * This separation of concerns:
 * - Makes error messages clearer (parse errors vs decode errors)
 * - Allows reusing parsed data with different decoders
 * - Follows the pattern used by Circe, upickle, etc.
 *
 * = Automatic Derivation =
 *
 * Use `Decoder.derived` to automatically generate decoders for case classes:
 *
 * {{{
 * import parser.interop.Decoder
 * import parser.interop.JsonDecoders.given
 *
 * case class Person(name: String, age: Int)
 * given Decoder[JsonValue, Person] = Decoder.derived
 *
 * val json: JsonValue = JsonValue.Object(Map(
 *   "name" -> JsonValue.Str("Alice"),
 *   "age" -> JsonValue.Number(30)
 * ))
 * val person: Result[DecodeError, Person] =
 *   Decoder[JsonValue, Person].decode(json)
 * // Success(Person("Alice", 30), 0)
 * }}}
 *
 * = Manual Implementation =
 *
 * You can also implement decoders manually for custom types:
 *
 * {{{
 * given Decoder[JsonValue, LocalDate] = new Decoder[JsonValue, LocalDate] {
 *   def decode(value: JsonValue): Result[DecodeError, LocalDate] = value match {
 *     case JsonValue.Str(s) =>
 *       Try(LocalDate.parse(s)).toResult
 *     case other =>
 *       Result.Failure(List(DecodeError.TypeMismatch("ISO date string", ...)))
 *   }
 * }
 * }}}
 *
 * @tparam From The source type (JsonValue, XmlNode, etc.)
 * @tparam To The target type to decode into (covariant)
 */
trait Decoder[From, +To] {
  // ...
}
```

## Output Format

### For README.md Updates
- Preserve existing structure and formatting
- Update in-place, don't rewrite from scratch
- Keep the two-column approach for dual examples

### For New Files (examples/, docs/)
- Use clear, professional markdown
- Include code fencing with language hints: ```scala
- Add navigation links between related docs
- Use emoji sparingly (only for checklists/callouts)

## Project Conventions to Follow

1. **No Claude/Anthropic Attribution** - Documentation is from "Rumil project"
2. **Scala 3 Syntax** - Use `given`/`using`, enum, etc. (no Scala 2)
3. **Code Must Compile** - All examples should actually work
4. **Import Clarity** - Always show full imports, never assume context
5. **Formatting** - Run `sbt prepare` to format new .scala files

## Success Criteria

When this task is complete:

✅ README.md accurately reflects current features (no "coming soon" for implemented features)
✅ All Decoder examples use correct API (`Decoder[JsonValue, T]`)
✅ `examples/` directory exists with 4-5 complete, runnable programs
✅ `docs/` directory exists with comprehensive guides
✅ Key APIs have detailed scaladoc with examples
✅ Documentation establishes "Structural-First Design" as clear philosophy
✅ Both approaches (Structural and Idiomatic) are well-represented

## Validation Steps

After completion:

1. **Compile check**: All example .scala files should compile
   ```bash
   cd examples/json-to-case-class
   scala-cli compile .
   ```

2. **Link check**: All internal markdown links should resolve
3. **Consistency check**: Both approaches shown for major use cases
4. **Clarity check**: A new user can understand the library in 5 minutes

## Estimated Effort

- **Task 1** (README updates): 1-2 hours
- **Task 2** (examples/): 2-3 hours
- **Task 3** (docs/): 2-3 hours
- **Task 4** (scaladoc): 1 hour

**Total**: 6-9 hours for complete implementation

## Notes

- This is the **final Priority 1 item** before v0.2.0 public launch
- Focus on **clarity and professionalism** - this is the first impression
- The documentation is **the product** for open source libraries
- Establish thought leadership on "Structural-First Design" philosophy
- Show pragmatism with idiomatic layer (not dogmatic purism)

## Files to Modify/Create

### Modify:
- `README.md` (major updates)
- `interop/src/main/scala/parser/interop/Decoder.scala` (scaladoc)
- `interop/src/main/scala/parser/interop/JsonDecoders.scala` (scaladoc)

### Create:
- `examples/json-to-case-class/README.md`
- `examples/json-to-case-class/StructuralExample.scala`
- `examples/json-to-case-class/IdiomaticExample.scala`
- `examples/nested-structures/README.md`
- `examples/nested-structures/Example.scala`
- `examples/error-recovery/README.md`
- `examples/error-recovery/Example.scala`
- `examples/debugging-parsers/README.md`
- `examples/debugging-parsers/Example.scala`
- `examples/expression-evaluator/README.md`
- `examples/expression-evaluator/StructuralExample.scala`
- `examples/expression-evaluator/IdiomaticExample.scala`
- `docs/getting-started.md`
- `docs/structural-approach.md`
- `docs/idiomatic-approach.md`
- `docs/error-handling.md`
- `docs/debugging.md`
- `docs/performance.md`
- `docs/migration-guide.md`

## Reference Materials

- **Current README.md**: See file for existing structure
- **ROADMAP.md section 1.3**: For philosophy and vision
- **Decoder implementation**: `interop/src/main/scala/parser/interop/Decoder.scala`
- **Decoder tests**: `interop/src/test/scala/parser/interop/DecoderTests.scala` (shows real usage)
- **JsonParser**: `parsers/src/main/scala/parsers/json/JsonParser.scala`

## Start Here

Begin with Task 1 (README.md updates) as it's the highest-impact change and will clarify the API for the examples you'll write next.
