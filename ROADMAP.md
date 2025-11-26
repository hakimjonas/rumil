# Rumil: Path to v1.0

## Philosophy

**Excellence before release.** The first public impression matters. We will not publish until every aspect of Rumil meets world-class standards. No intermediate v0.x releases - we go straight to v1.0 when ready.

---

## Current State: Strong Foundation

### Completed Features
- **40+ Parser Combinators** - Complete combinator library
- **Left Recursion** - Both direct and indirect (Warth et al. algorithm)
- **Error Recovery** - Result.Partial with multi-error accumulation
- **GreenNode/RedTree** - Lossless syntax trees (Rowan-style architecture)
- **6 Format Parsers** - JSON, XML, TOML, CSV, YAML, Protobuf
- **Decoders** - JSON, XML, TOML, YAML with automatic case class derivation
- **Type Safety** - Minimal casts, all localized with safety proofs
- **Property Tests** - 23 functor/monad laws verified
- **249+ Tests** - Comprehensive test coverage
- **JMH Benchmarks** - Complete benchmark suite with cats-parse comparison

### Recent Optimizations
- **LazyFailure** - Deferred error construction for failure paths
- **LazyPartial** - Deferred error construction for recovery paths (2.65x faster on error-heavy workloads)
- **ListBuffer for Many** - Efficient accumulation in repetition combinators

---

## v1.0 Release Criteria

### TIER 1: Core Excellence ✅ MOSTLY COMPLETE

| Feature | Status | Notes |
|---------|--------|-------|
| Complete combinator library | ✅ Complete | 40+ combinators |
| Left recursion support | ✅ Complete | Warth et al. algorithm |
| Error recovery (resilient parsing) | ✅ Complete | Result.Partial + multi-error |
| Lossless syntax trees | ✅ Complete | GreenNode/RedTree |
| Type safety | ✅ Complete | Minimal localized casts |
| Property-based tests | ✅ Complete | 23 laws verified |

### TIER 2: Performance Parity ⚠️ IN PROGRESS

Goal: Within 2-3x of cats-parse on common workloads.

| Optimization | Status | Impact |
|--------------|--------|--------|
| LazyFailure (deferred errors) | ✅ Complete | Improved failure paths |
| LazyPartial (deferred recovery errors) | ✅ Complete | 2.65x faster on error-heavy |
| **orElse/recover separation** | 🔄 Next | 8-10x faster on alternation |
| ListBuffer for Many | ✅ Complete | Faster repetition |

#### Current Bottleneck: orElse Error Tracking

The biggest remaining performance gap is `orElse` tracking errors even when not needed:

| Benchmark | Rumil | cats-parse | Gap |
|-----------|-------|------------|-----|
| Many with 90% errors | 3650ms | 9ms | 405x |
| Choice (10 alternatives) | 30ms | 1ms | 30x |

**Solution**: Separate `orElse` (fast alternation) from `recover` (error tracking):
- `orElse` → `Parser.Or` (no error tracking, matches cats-parse)
- `recover` → `Parser.RecoverWith` (error tracking for resilient parsing)

See: `docs/OPTION_5_ORELSE_SEMANTICS_DESIGN.md`

### TIER 3: API Polish ⚠️ IN PROGRESS

| Item | Status | Priority |
|------|--------|----------|
| orElse/recover semantic clarity | 🔄 Next | HIGH |
| Consistent naming conventions | Needs review | MEDIUM |
| Extension method organization | Needs review | MEDIUM |
| Deprecation of old APIs | As needed | LOW |

### TIER 4: Documentation Excellence

| Document | Status | Priority |
|----------|--------|----------|
| Getting Started (15-min guide) | In Progress | HIGH |
| Cookbook (10 common patterns) | In Progress | HIGH |
| Performance Guide | ✅ Complete | HIGH |
| API Reference (Scaladoc) | Exists, needs polish | MEDIUM |
| Error Handling Guide | Partial | MEDIUM |
| Migration Guide (if breaking changes) | As needed | HIGH |

### TIER 5: Platform Reach (Post-1.0)

| Platform | Status | Priority |
|----------|--------|----------|
| JVM | ✅ Complete | - |
| Scala.js | Future | LOW |
| Scala Native | Future | LOW |

---

## Immediate Priorities

### 1. Implement orElse/recover Separation

**Breaking Change** - but essential for performance parity.

```scala
// Current (always tracks errors)
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)

// New (fast alternation, no error tracking)
inline def orElse[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.Or(p, fallback)

// New (explicit error recovery)
inline def recover[E, A](p: Parser[E, A], fallback: Parser[E, A]): Parser[E, A] =
  Parser.RecoverWith(p, fallback)
```

**Migration**: Users who need error tracking change `.orElse` to `.recover`.

### 2. Complete Documentation

- Finish Getting Started guide
- Finish Cookbook with 10 patterns
- Update all examples for new orElse/recover semantics

### 3. Final Polish

- Review all public APIs
- Clean up any remaining dead code
- Ensure consistent naming

---

## Performance Philosophy

Rumil prioritizes (in order):
1. **Correctness** - Type-safe, principled design
2. **Error Quality** - Rich, helpful error messages
3. **Ergonomics** - Pleasant API, IDE-friendly ASTs
4. **Performance** - Competitive with alternatives

### Design Trade-offs

| Feature | Rumil | cats-parse | Trade-off |
|---------|-------|------------|-----------|
| Error tracking | Optional (orElse vs recover) | Never | User chooses |
| Line/column tracking | Always | Never | Better errors, some overhead |
| Lossless trees | GreenNode/RedTree | Not supported | IDE features, memory overhead |
| Left recursion | Built-in | Not supported | Unique capability |

---

## Benchmark Targets for v1.0

### Happy-Path Performance

| Benchmark | Target | Current | Status |
|-----------|--------|---------|--------|
| String matching | Within 3x of cats-parse | ~2x | ✅ |
| Many (1K chars) | Within 3x of cats-parse | ~3x | ✅ |
| Sequential (100 parsers) | Within 3x of cats-parse | ~3x | ✅ |
| Number parsing | Within 2x of cats-parse | ~1x | ✅ |

### Error-Path Performance (after orElse/recover split)

| Benchmark | Target | Current | After Split |
|-----------|--------|---------|-------------|
| Choice (10 alternatives) | Within 3x | 30x | ~3x expected |
| Many with recovery | Within 3x | 405x | ~3x expected |

---

## What "Ready for v1.0" Means

1. ✅ **Complete Feature Set** - All expected combinators + unique features
2. ⚠️ **Competitive Performance** - Within 2-3x of cats-parse (needs orElse/recover)
3. ⚠️ **Excellent Documentation** - Learn without reading source (in progress)
4. ✅ **Type Safety** - Principled design, minimal escape hatches
5. ✅ **Tested** - 249+ tests, property-based verification
6. ⚠️ **API Stability** - Breaking changes addressed before release

---

## Non-Goals for v1.0

- Scala.js / Scala Native (post-1.0)
- Streaming parsing (post-1.0)
- Maximum raw performance (we optimize for correctness + ergonomics first)
- Feature parity with every competitor (we have unique strengths)

---

## Timeline

No fixed dates. Release when ready:
- All v1.0 criteria met
- Documentation complete
- Performance targets achieved
- API stable

**Quality over speed.** Better to release later with excellence than earlier with compromise.
