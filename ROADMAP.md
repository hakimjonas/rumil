# Rumil Development Roadmap

## Priority 1: Core Usability (Must Have)

These features directly impact day-to-day user experience and make Rumil significantly more practical.

### 1.1 Better Error Messages
**Status:** Not Started
**Effort:** Medium (1-2 weeks)
**Impact:** High

- Error accumulation across multiple parse failures
- Context stack showing parse path (e.g., "while parsing object key in JSON value")
- Helpful suggestions based on common mistakes
- Color-coded error output
- Show snippet of input around error location

**Example:**
```
Error: Expected digit but found 'x'
  while parsing number
  in JSON array element
  at line 3, column 12

  10 |   "items": [
  11 |     42,
  12 |     5x7
     |      ^ unexpected character
  13 |   ]

Suggestion: Did you mean '5.7' or '507'?
```

### 1.2 Parser Debugging Tools
**Status:** Not Started
**Effort:** Small (2-3 days)
**Impact:** High

- `trace(label: String)` combinator for execution visibility
- `debug` mode that prints entire parse tree
- Optional logging levels (INFO, DEBUG, TRACE)
- Integration with standard logging frameworks

**Usage:**
```scala
val number = digit.many1.trace("number").map(_.mkString.toInt)
val expr = (number ~ operator ~ number).debug("expression")
```

### 1.3 Position-Aware Parsing
**Status:** Not Started
**Effort:** Small (3-4 days)
**Impact:** Medium

- `withPosition` combinator that captures span information
- Return `(value: A, span: Span)` tuples
- Enable source-mapped ASTs
- Critical for IDE integration, syntax highlighting

**Usage:**
```scala
val identifier = letter.many1.withPosition
// Returns: Result[(String, Span)]
```

## Priority 2: Performance & Scalability (Should Have)

These features make Rumil viable for production use with real-world data.

### 2.1 Streaming/Incremental Parsing
**Status:** Not Started
**Effort:** Large (3-4 weeks)
**Impact:** High

- Parse large files without loading entire string into memory
- `parseStream` function working with `Iterator[Char]` or chunks
- Backtracking with limited lookahead buffer
- Critical for parsing logs, large JSON/XML files

### 2.2 Memoization/Packrat Parsing
**Status:** Not Started
**Effort:** Medium (1-2 weeks)
**Impact:** Medium

- Cache parser results to avoid redundant work
- `.memoize` combinator for opt-in memoization
- Configurable cache size/eviction strategy
- Trade memory for speed (especially valuable for recursive grammars)

### 2.3 Left Recursion Support
**Status:** Not Started
**Effort:** Large (2-3 weeks)
**Impact:** Medium

- Detect left-recursive grammars
- Automatic handling or clear error messages
- Currently `chainl1`/`chainr1` work around this, but not general solution
- Common pain point for expression grammars

### 2.4 Performance Benchmarks Suite
**Status:** Basic benchmarks exist
**Effort:** Medium (1 week)
**Impact:** Medium

- Comprehensive benchmark suite
- Comparison against fastparse, cats-parse, parsley
- Regression testing for performance
- Identify optimization opportunities
- Document performance characteristics

## Priority 3: Developer Experience (Nice to Have)

These features improve documentation and learning curve.

### 3.1 Tutorial Documentation
**Status:** Not Started
**Effort:** Medium (1 week)
**Impact:** High

- Step-by-step tutorial building a realistic parser from scratch
- Common patterns cookbook (expressions, lists, recursive structures)
- Migration guides from other libraries (fastparse, cats-parse, Parsec)
- Video tutorials or interactive examples

### 3.2 ScalaDoc API Documentation
**Status:** Inline comments exist
**Effort:** Small (2-3 days)
**Impact:** Medium

- Generate comprehensive API documentation
- Publish to GitHub Pages
- Link from main README
- Include examples in ScalaDoc

### 3.3 Error Recovery Combinators
**Status:** Not Started
**Effort:** Medium (1 week)
**Impact:** Medium

- `skipUntil(delimiter)` - skip malformed input
- `recoverWith` improvements for better error handling
- Partial parsing with error collection
- Useful for fault-tolerant parsers (IDE use case)

## Priority 4: Advanced Features (Future)

These are specialized features for advanced use cases.

### 4.1 Grammar Validation
**Status:** Not Started
**Effort:** Large (3-4 weeks)
**Impact:** Low

- Static analysis to detect infinite loops
- Warn about ambiguous grammars
- LL(k) grammar checking
- Helpful for library authors, not end users

### 4.2 Parser Generators
**Status:** Not Started
**Effort:** Large (4-6 weeks)
**Impact:** Medium

- EBNF/BNF to Rumil parser converter
- Generate parsers from grammar files
- Lower barrier to entry for non-Scala experts
- Could be separate tool/project

### 4.3 Custom Input Types
**Status:** Currently `String` only
**Effort:** Large (2-3 weeks)
**Impact:** Low

- Generic input type: `Parser[I, E, A]` instead of `Parser[E, A]`
- Support `Array[Byte]`, custom token streams
- More flexible but adds complexity
- Breaking change to API

## Priority 5: Platform Support (Ecosystem)

Expand Rumil to other platforms and ecosystems.

### 5.1 Scala.js Support
**Status:** Not Started
**Effort:** Small (1 week)
**Impact:** Medium

- Port to Scala.js for browser use
- Minimal Java dependencies (only `java.time`) makes this feasible
- Browser-based parser playgrounds
- Expands audience significantly

### 5.2 Scala Native Support
**Status:** Not Started
**Effort:** Medium (2 weeks)
**Impact:** Low

- Compile to native binaries
- Fast startup, low memory footprint
- Perfect for CLI tools
- Requires eliminating `java.time` dependency

### 5.3 Native Image Support (GraalVM)
**Status:** Unknown
**Effort:** Small (3-5 days)
**Impact:** Medium

- Test and document GraalVM native-image compatibility
- Configuration files for reflection if needed
- Fast startup for command-line tools
- Low effort, high value if it works

## Priority 6: Publishing & Distribution (Final Release)

These are saved for when the library is production-ready.

### 6.1 Publishing to Maven Central
**Status:** Not Started
**Effort:** Medium (1 week including setup)
**Impact:** Critical

- Sonatype account setup
- GPG signing
- Proper versioning (semantic versioning)
- Release process documentation
- CI/CD pipeline for releases

### 6.2 Continuous Integration
**Status:** Not Started
**Effort:** Small (2-3 days)
**Impact:** High

- GitHub Actions for testing on every commit
- Multi-version testing (Scala 3.7, 3.6, future versions)
- Code coverage reporting
- Automated release builds

### 6.3 Community & Governance
**Status:** Not Started
**Effort:** Ongoing
**Impact:** Medium

- Contribution guidelines (CONTRIBUTING.md)
- Code of conduct
- Issue templates
- Discussion forum or Discord
- Release notes for each version

## Milestones

### v0.1.0 - Initial Release ✅
- Core parser combinator library
- 40+ combinators
- Monadic interface
- Property-based tests
- Basic examples
- **Status:** COMPLETE

### v0.2.0 - Usability Release
- Better error messages (1.1)
- Parser debugging tools (1.2)
- Position-aware parsing (1.3)
- Tutorial documentation (3.1)

### v0.3.0 - Performance Release
- Streaming/incremental parsing (2.1)
- Memoization/packrat parsing (2.2)
- Performance benchmarks (2.4)

### v0.4.0 - Advanced Features
- Left recursion support (2.3)
- Error recovery combinators (3.3)
- Grammar validation (4.1)

### v0.5.0 - Platform Expansion
- Scala.js support (5.1)
- Native image support (5.3)

### v1.0.0 - Production Release
- Publishing to Maven Central (6.1)
- CI/CD pipeline (6.2)
- ScalaDoc published to GitHub Pages (3.2)
- Complete documentation
- Stable API guarantees

## Quick Wins

These can be implemented quickly for immediate value:

1. **`trace` combinator** (1-2 days) - See debugging section 1.2
2. **Basic position tracking** (2-3 days) - Extend `withPosition`
3. **ScalaDoc generation** (1 day) - Already have good comments
4. **Example parsers** (1 week) - More realistic examples beyond JSON/arithmetic

## Notes

- Priorities may shift based on user feedback and community needs
- Some features may be split into separate libraries (e.g., parser generators)
- Breaking changes should be minimized and well-documented
- Each major feature should include comprehensive tests and documentation
