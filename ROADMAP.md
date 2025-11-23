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
- **Type Safety** - No naked type casts in production code
- **Property Tests** - 23 functor/monad laws verified
- **550+ Tests** - Comprehensive test coverage

---

## Excellence Roadmap

### TIER 1: Feature Completeness
*What users will expect from a complete library*

| Feature | Status | Priority |
|---------|--------|----------|
| XML Decoder (XmlNode → case class) | Complete | HIGH |
| TOML Decoder (TomlValue → case class) | Complete | HIGH |
| YAML Decoder (YamlValue → case class) | Complete | HIGH |
| Field Annotations (@JsonKey, @JsonIgnore) | Pending | MEDIUM |
| Streaming Parsing (large files) | Pending | MEDIUM |
| Memoization Combinator (.memoize) | Pending | LOW |

### TIER 2: Proof of Quality
*Evidence that backs up our claims*

| Item | Status | Priority |
|------|--------|----------|
| JMH Benchmark Suite | Pending | HIGH |
| vs cats-parse comparison | Pending | HIGH |
| vs fastparse comparison | Pending | HIGH |
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

## Recommended Work Order

1. **Benchmarks** - Prove performance with JMH
2. **Documentation** - Getting Started + Cookbook
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
- No type casts in production code
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
