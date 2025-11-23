# Rumil: Path to Excellence

## Philosophy

**Excellence before release.** The first public impression matters. We will not publish until every aspect of Rumil meets world-class standards.

---

## Current State: Strong Foundation

### Completed Features
- **40+ Parser Combinators** - Complete combinator library
- **Left Recursion** - Both direct and indirect (Warth et al. algorithm)
- **Error Recovery** - Result.Partial with multi-error accumulation
- **GreenNode** - Lossless syntax trees preserving all source information
- **6 Format Parsers** - JSON, XML, TOML, CSV, YAML, Protobuf
- **Decoders** - JSON, XML, TOML, YAML with automatic case class derivation
- **Type Safety** - No naked type casts in production code (except 2 isolated in LR seed handling)
- **Property Tests** - 23 functor/monad laws verified
- **190+ Tests** - Comprehensive test coverage
- **JMH Benchmarks** - Complete benchmark suite with cats-parse comparison

---

## Excellence Roadmap

### TIER 1: Feature Completeness
*What users will expect from a complete library*

| Feature | Status | Priority |
|---------|--------|----------|
| XML Decoder (XmlNode -> case class) | **Complete** | HIGH |
| TOML Decoder (TomlValue -> case class) | **Complete** | HIGH |
| YAML Decoder (YamlValue -> case class) | **Complete** | HIGH |
| Field Annotations (@JsonKey, @JsonIgnore) | Pending | MEDIUM |
| Streaming Parsing (large files) | Pending | MEDIUM |
| Memoization Combinator (.memoize) | Pending | LOW |

### TIER 2: Proof of Quality
*Evidence that backs up our claims*

| Item | Status | Priority |
|------|--------|----------|
| JMH Benchmark Suite | **Complete** | HIGH |
| vs cats-parse comparison | **Complete** | HIGH |
| vs fastparse comparison | Pending | MEDIUM |
| Memory profiling | Pending | MEDIUM |
| Real-world workloads | Pending | MEDIUM |

### TIER 3: Documentation Excellence
*Users should understand everything without reading source*

| Document | Status | Priority |
|----------|--------|----------|
| Getting Started (15-min guide) | Incomplete | HIGH |
| API Reference (Scaladoc) | Exists, unpublished | HIGH |
| Cookbook (10 common patterns) | Pending | HIGH |
| Error Handling Guide | Partial | MEDIUM |
| Performance Guide | Sparse | MEDIUM |
| Comparison Matrix (vs competitors) | Pending | MEDIUM |
| Troubleshooting Guide | Pending | LOW |

### TIER 4: Platform Reach
*Where can Rumil run?*

| Platform | Status | Priority |
|----------|--------|----------|
| JVM | Complete | - |
| Scala.js (browser/Node) | Future/Community | LOW |
| Scala Native | Future/Community | LOW |

---

## Benchmark Results (vs cats-parse)

### Parser Combinators

| Benchmark | cats-parse | Rumil | Ratio |
|-----------|-----------|-------|-------|
| choice10 (10-way alternative) | 174,798 ops/ms | 2,799 ops/ms | **62x** |
| stringMatch ("hello world") | 302,200 ops/ms | 24,132 ops/ms | **12.5x** |
| parseDigits1000 (1000 digits) | 417 ops/ms | 103 ops/ms | **4x** |
| parseCommaSep100 (100 numbers) | 395 ops/ms | 134 ops/ms | **2.9x** |

### JSON Parsing

| Benchmark | cats-parse | Rumil | Ratio |
|-----------|-----------|-------|-------|
| jsonTiny (`{"x":1}`) | 2,334 ops/ms | 407 ops/ms | **5.7x** |
| jsonSmall (3 fields) | 903 ops/ms | 162 ops/ms | **5.6x** |
| jsonMedium (nested) | 183 ops/ms | 29 ops/ms | **6.3x** |
| jsonNested10 (10 levels) | 208 ops/ms | 43 ops/ms | **4.8x** |
| jsonArray100 (100 objects) | 12 ops/ms | 2.1 ops/ms | **5.7x** |

**Summary:** Rumil is approximately **4-6x slower** than cats-parse for most operations, with the notable exception of choice/alternative where the gap widens significantly.

---

## Performance Analysis & Optimization Opportunities

### Why Rumil is Slower

1. **Interpreter-Based Design**: Rumil evaluates parser ASTs at runtime via pattern matching. cats-parse compiles parsers to optimized code paths.

2. **Error Infrastructure**: Every operation tracks position (line, column, offset), accumulates errors, and maintains furthest-failure location.

3. **State Management**: ParserState saves/restores snapshots for backtracking, adding allocation overhead.

4. **List Building**: `many` and `many1` build `List[A]` via prepend-then-reverse, while cats-parse uses builder patterns.

### Optimization Opportunities (Preserving Design Principles)

These optimizations maintain Rumil's structural design, type safety, and error reporting quality:

#### HIGH IMPACT (Preserves all ideals)

| Optimization | Expected Gain | Complexity | Principle Impact |
|--------------|---------------|------------|------------------|
| **Specialized Many/Many1** - Use ArrayBuffer internally, convert to List at end | 2-3x for repetition | Low | None |
| **String Parsing Optimization** - Use `regionMatches` instead of manual loop | 1.5-2x for strings | Low | None |
| **Inline State Checks** - Avoid Option wrapping in `state.current` | 1.2-1.5x overall | Medium | None |
| **Lazy Error Construction** - Only build error objects on failure | 1.5-2x for success paths | Medium | None |

#### MEDIUM IMPACT (Preserves most ideals)

| Optimization | Expected Gain | Complexity | Principle Impact |
|--------------|---------------|------------|------------------|
| **Choice Optimization** - Compile static choice to jump table | 5-10x for choice | High | Minor: adds compilation step |
| **Satisfy Specialization** - Special cases for common predicates (isDigit, isLetter) | 1.3-1.5x for char parsing | Medium | None |
| **State Pool** - Reuse snapshot objects | 1.2x for backtracking | Medium | None |

#### REQUIRES DESIGN TRADE-OFFS

| Optimization | Expected Gain | Trade-off |
|--------------|---------------|-----------|
| **Compilation Phase** - Compile parser AST to bytecode | 5-10x overall | Adds complexity, longer startup |
| **Remove Line/Column Tracking** - Only track offset | 1.3-1.5x | Worse error messages |
| **Strict Mode** - Disable error accumulation | 1.5-2x | Loses resilient parsing |

### Recommended Optimization Path

1. **Phase 1** (Low-hanging fruit): ArrayBuffer for Many, lazy error construction
2. **Phase 2** (Medium effort): Choice optimization, satisfy specialization
3. **Phase 3** (If needed): Compilation phase as opt-in feature

### Performance Philosophy

Rumil prioritizes:
1. **Correctness** - Type-safe, principled design
2. **Error Quality** - Rich, helpful error messages
3. **Ergonomics** - Pleasant API, IDE-friendly ASTs
4. **Performance** - "Fast enough" for most use cases

For applications requiring maximum throughput on hot paths, users can:
- Use specialized parsers for critical sections
- Consider cats-parse for pure performance needs
- Mix libraries (parse structure with Rumil, then optimize bottlenecks)

---

## Recommended Work Order

1. **Documentation** - Getting Started + Cookbook
2. **Performance Phase 1** - ArrayBuffer, lazy errors
3. **Field Annotations** - Polish decoder API
4. **Streaming** - Large file handling

---

## Design Principles

### Structural-First Design
- **Enums** for sum types (Parser, Result, ParseError)
- **Named Tuples** for product types (Location, Span)
- **Controlled mutation** only in interpreter shell

### Two-Layer Architecture
- **Core Layer** - Pure, principled parser combinators
- **Adapter Layer** - Idiomatic Scala interop (Decoder, derived)

### Quality Standards
- No type casts in production code (2 isolated casts with safety proofs)
- Property-based testing for all laws
- Comprehensive documentation before release
- Benchmark evidence for performance claims

---

## What "World-Class" Means

A world-class parser combinator library has:

1. **Complete Feature Set** - All expected combinators + extras
2. **Proven Performance** - Benchmarks vs industry standards
3. **Excellent Documentation** - Learn without reading source
4. **Multi-Platform** - JVM, JS, Native
5. **Type Safety** - Principled design, no escape hatches
6. **Production Ready** - Battle-tested with real workloads

Rumil will meet all these criteria before public release.
