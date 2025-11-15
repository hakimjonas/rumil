# Review of `claude/read-documentation-019FDTDy6nZSh7BxGKaHqzZJ`

## Summary

This PR adds a comprehensive parser suite (`parsers/`) with implementations for CSV, JSON, XML, YAML, TOML, and Protocol Buffers. While the ambition is appreciated, **the code does not compile** and has several design issues that need to be addressed.

## Compilation Errors (12 total)

### 1. Missing `isHexDigit` method (Common.scala:19-22)
```scala
// ERROR: Char.isHexDigit does not exist in Scala 3
d1 <- satisfy(_.isHexDigit, "hex digit")
```

**Fix Required:** Implement hex digit check manually:
```scala
satisfy(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'), "hex digit")
```

### 2. Strict Equality Violations (JsonParser.scala:289, XmlParser.scala:313)
```scala
// ERROR: Values cannot be compared with == or != under -language:strictEquality
case JsonValue.Null => "null"  // pattern matching on enum
if (name == closeName)  // comparing named tuples
```

**Fix Required:** Implement `CanEqual` instances for:
- `JsonValue` enum cases
- Named tuples used in XML parser
- All other enum types (TomlValue, XmlNode, etc.)

Add to respective Types.scala files:
```scala
// Example for JsonValue
given CanEqual[JsonValue, JsonValue] = CanEqual.derived
```

### 3. Type Error in Common.scala:79
```scala
// ERROR: Trying to call .map on Double instead of Parser
}.map(_ * s)
```

**Fix Required:** Parentheses issue - the `.map` is being applied to the wrong expression. The for-comprehension needs proper closing.

### 4. Missing `isEmpty` method (TomlParser.scala:159)
```scala
// ERROR: Char | String union type doesn't have isEmpty
escape.map(c => if (c.isEmpty) "" else c.toString)
```

**Fix Required:** Handle the union type properly or avoid creating Char | String type.

### 5. Missing `ws` definition (YamlParser.scala:93, 106, 117, 189)
```scala
// ERROR: ws is not defined in YamlParser
char('[') *> ws
```

**Fix Required:** Define `ws` function in YamlParser object (likely copy from other parsers).

## Design Issues

### 1. Object Definitions (All Parsers)

**Current:**
```scala
object JsonParser {
  def parse(input: String): Result[ParseError, JsonValue] = {
    jsonValue.run(input)
  }
}
```

**Issue:** Using `object` is unnecessary. Per the MANIFESTO, we prefer top-level functions over objects/companion objects.

**Fix Required:** Convert all parsers to top-level functions:
```scala
def parseJson(input: String): Result[ParseError, JsonValue] = {
  jsonValue.run(input)
}
```

Similarly for CSV, XML, YAML, TOML, Protobuf parsers.

### 2. README Tone Issues (parsers/README.md)

**Current:**
```markdown
A collection of production-ready parsers...

All parsers follow Rumil's manifesto:
- ✅ **Enums** for AST nodes (not case classes)
- ✅ **Named tuples** for simple data structures
```

**Issues:**
1. "production-ready" - Promotional language (code doesn't even compile)
2. Checkbox style is self-congratulatory
3. References "Rumil's manifesto" - internal design philosophy shouldn't be in user-facing docs

**Fix Required:** Rewrite to be factual and descriptive:
```markdown
# Rumil Parsers

Parser implementations for common data formats, built with the Rumil parser combinator library.

## Formats

- CSV - RFC 4180 compliant
- JSON - RFC 8259 compliant
- XML - Well-formed XML with namespace support
- YAML - YAML 1.2
- TOML - TOML v1.0.0
- Protocol Buffers - Proto3 syntax for .proto files
```

## Requirements Before Merge

1. **All compilation errors must be fixed**
   - Run `cd parsers && sbt compile` successfully
   - No errors, no warnings (we use `-Xfatal-warnings`)

2. **All tests must pass**
   - Run `cd parsers && sbt test` successfully
   - All property-based tests must pass
   - All example files must parse correctly

3. **Convert objects to top-level functions**
   - Remove all `object XParser` definitions
   - Use top-level `def parseX(...)` functions instead
   - Update all tests to use new function signatures

4. **Fix README tone**
   - Remove promotional language
   - Remove manifesto references
   - Be factual and descriptive

5. **Implement CanEqual instances**
   - Add for all enum types
   - Add for all named tuples that need equality
   - Ensure strictEquality compliance

6. **Code should follow existing Rumil patterns**
   - Look at main library for reference
   - Use same documentation style
   - Same code organization principles

## Testing Checklist

Before requesting re-review:
- [ ] `sbt compile` passes with no errors or warnings
- [ ] `sbt test` passes all tests
- [ ] All parsers are top-level functions (no objects)
- [ ] README is factual and user-focused
- [ ] All equality comparisons work with strict equality
- [ ] Example files in test/resources all parse correctly

## Positive Aspects

To be fair, there are good things here:
- Comprehensive test coverage with property-based tests
- RFC compliance documentation in comments
- Good separation of Types and Parser logic
- Decent use of enums and named tuples (when they compile)

The foundation is promising, but the execution needs significant fixes before this can be merged.
