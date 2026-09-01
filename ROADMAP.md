# Rumil: Path to v1.0

## Philosophy

**v1.0 is gated on the criteria below.** The 0.3.x line ships incrementally (auto-tagged patch releases from main); 1.0 is the stability statement, cut when every tier here is complete.

---

## Current State

### Completed Features
- **40+ Parser Combinators** — complete combinator library
- **Left Recursion** — direct and indirect (Warth et al. algorithm) via the `rule` combinator
- **Error Recovery** — `Result.Partial` with multi-error accumulation
- **GreenNode/RedTree** — lossless syntax trees (Rowan-style architecture)
- **7 Format Parsers** — JSON, XML, TOML, CSV, YAML, Protobuf, XPath 1.0
- **Codec layer** — JSON/XML/TOML/YAML AST decoding with derivation lives in sarati (`net.ghoula::sarati`); the rumil-interop predecessor is deprecated for 0.4.0
- **Stack Safety** — heap-trampolined execution: sequential chains, deep repetition, and structural recursion are depth-bounded by heap, pinned in the test suite
- **Type Safety** — minimal casts, all localized with safety proofs
- **Property Tests** — monad law property tests (`MonadLaws.scala`) plus ScalaCheck parser tests
- **688 Tests** — across core, parsers, and interop

---

## v1.0 Release Criteria

### TIER 1: Core Expression — COMPLETE

| Feature | Status | Notes |
|---------|--------|-------|
| Complete combinator library | ✅ Complete | 40+ combinators |
| Left recursion support | ✅ Complete | Warth et al. algorithm |
| Error recovery (resilient parsing) | ✅ Complete | Result.Partial + multi-error |
| Lossless syntax trees | ✅ Complete | GreenNode/RedTree |
| Type safety | ✅ Complete | Minimal localized casts |
| Property-based tests | ✅ Complete | Monad laws + parser properties |

### TIER 2: Performance Parity — IN PROGRESS

Goal: competitive with cats-parse on common workloads (within 2–3x).

Completed so far: lazy error construction on failure and recovery paths, orElse/recover
separation with flattened alternation dispatch, skip-combinator fusion, radix-based string
choice with lookahead dispatch, and trampoline integration for deferred recursion.
Remaining: closing the measured gap on fallback-heavy alternation and repetition shapes.

### TIER 3: API Polish — COMPLETE

| Item | Status |
|------|--------|
| orElse/recover semantic clarity | ✅ Complete |
| Consistent naming conventions | ✅ Complete (dead aliases deprecated for 0.4.0) |
| Extension method organization | ✅ Complete (single extension surface in `parser.syntax`) |
| Deprecation of old APIs | ✅ Complete (0.4.0 deprecation round) |

### TIER 4: Documentation — IN PROGRESS

| Document | Status |
|----------|--------|
| Getting Started (15-min guide) | ✅ Complete |
| Cookbook (10 common patterns) | ✅ Complete |
| Performance Guide | ✅ Complete |
| API Reference | ✅ Complete |
| Error Handling Guide | Partial — alternation vs recovery covered; error formatting section outstanding |
| Migration Guide | ✅ Complete |

### TIER 5: Platform Reach (Post-1.0)

| Platform | Status |
|----------|--------|
| JVM | ✅ Complete |
| Scala.js | Future |
| Scala Native | Future |

---

## Design Priorities

In order: correctness, error quality, ergonomics, then raw performance. Error tracking is
opt-in (`orElse` vs `recover`), positions are always tracked, trees are lossless, and left
recursion is built in — each of these is a deliberate trade against peak throughput.

## Performance Goals for v1.0

Within 2–3x of cats-parse on common workloads. The comparison methodology and per-shape
results live with the benchmark sources under `benchmarks/`.

## What "Ready for v1.0" Means

1. ✅ **Complete Feature Set** — all expected combinators + unique features
2. ⚠️ **Competitive Performance** — within 2–3x of cats-parse on common workloads
3. ⚠️ **Documentation** — learn without reading source
4. ✅ **Type Safety** — principled design, minimal escape hatches
5. ✅ **Tested** — 688 tests across three modules, property-based verification
6. ⚠️ **API Stability** — breaking changes addressed before release

---

## Non-Goals for v1.0

- Scala.js / Scala Native (post-1.0)
- Streaming parsing (post-1.0)
- Maximum raw performance (correctness and ergonomics come first)
- Feature parity with every competitor (the feature set has its own shape)

---

## Timeline

No fixed dates. 1.0 ships when the criteria above are met.
