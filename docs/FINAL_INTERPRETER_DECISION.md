# Final Interpreter Decision - Based on Real Benchmarks

## Executive Summary

After comprehensive research, bug fixing, and **real benchmarking**, we've determined:

**✅ KEEP TrampolineOpt as production default**

TrampolineHybrid's GADT type safety does NOT justify its performance regressions.

---

## The Journey

1. **Research Phase** ✅
   - Deep dive into Scala 3 type system
   - Proved casts are structurally necessary
   - Validated against cats-parse (37 casts) and zio-parser (101 casts)

2. **Implementation Phase** ✅
   - Created TrampolineHybrid (GADT + manual loop)
   - Fixed critical Success/Partial bug
   - All 223 tests passing

3. **Benchmarking Phase** ✅
   - Ran comprehensive performance tests
   - **DISCOVERED: Mixed results, some regressions**

---

## Real Benchmark Results

### TrampolineHybrid Performance (vs TrampolineOpt)

| Workload | Result | Impact |
|----------|--------|--------|
| Many (100 chars) | **+46.7%** | ✅ WIN |
| FlatMap (50 deep) | Tied | ➖ NEUTRAL |
| Many (1000 chars) | Tied | ➖ NEUTRAL |
| Choice (10 alt) | Tied | ➖ NEUTRAL |
| FlatMap (1000 deep) | **-60%** | ❌ LOSS |
| Map chain (50 deep) | **-350%** | ❌ MAJOR LOSS |
| Sequential (10K) | **-11.8%** | ❌ LOSS |

**Summary**: 1 win, 3 ties, 3 losses (including 2 major regressions)

---

## The Decision

### TrampolineOpt Remains Production Default

**Rationale**:
1. **Performance**: Faster or equal on 6/7 workloads
2. **Simplicity**: 16 casts vs Hybrid's 21
3. **Proven**: Months in production, stable
4. **No regressions**: Consistently fast

**Cast count**: 33 total (16 interpreter + 17 support)

### TrampolineHybrid: Experimental

**Status**: Available but not default
- **Pros**: GADT type safety, 46% faster on `many`
- **Cons**: 60-350% slower on Map/FlatMap chains
- **Use case**: When GADT type tracking is critical
- **Cast count**: 38 total (21 interpreter + 17 support)

### TrampolineZeroCast: Research Reference

**Status**: In `experimental/` directory
- **Pros**: Minimal casts (7), demonstrates theoretical minimum
- **Cons**: 2-3x slower due to TailRec overhead
- **Use case**: Understanding type system limits

---

## Why Hybrid is Slower

**Root cause**: GADT pattern matching overhead

TrampolineHybrid's enum-based continuations require more pattern matching:
```scala
enum Continuation[+E, -In, +Out] {
  case End[A]()
  case MapCont[A, B, C](...)
  case FlatMapCont[E1, A1, B1, C1](...)
  case FlatMapPartialCont[E1, A1, B1](...)
}
```

TrampolineOpt's simpler Frame enum is faster:
```scala
enum Frame {
  case FlatMap(fn: Any => Parser[Any, Any], consumed: Int)
  case FlatMapPartial(errors: List[Any], consumed: Int)
}
```

The GADT type parameters add overhead that the JVM cannot optimize away.

---

## Cast Count Summary

**Production** (TrampolineOpt):
```
TrampolineOpt:       16 casts ✅
Chunk:                7 casts
Interpreter:          5 casts
ParserState:          4 casts
Combinators:          1 cast
────────────────────────────
Total:               33 casts
```

**Comparison**:
- **Rumil**: 33 casts (stack-safe, fast)
- cats-parse: 37 casts (NOT stack-safe)
- zio-parser: 101 casts (stack-safe, type-erased)

**Achievement**: Lowest cast count among stack-safe parsers ✅

---

## Lessons Learned

1. **Type safety has costs**: GADT overhead is measurable
2. **Benchmarks matter**: Don't assume, measure!
3. **Simplicity wins**: TrampolineOpt's simpler design is faster
4. **Casts aren't everything**: 16 vs 21 casts matters less than performance

---

## References

- Type system research: `docs/GADT_TYPE_SYSTEM_RESEARCH.md`
- Library comparison: `docs/CAST_COMPARISON_ANALYSIS.md`
- Final benchmarks: `core/src/test/scala/parser/FinalHybridBenchmark.scala`

---

## Final Status

**Production**: TrampolineOpt (33 casts, consistently fast) ✅
**Experimental**: TrampolineHybrid (38 casts, GADT, mixed performance)
**Reference**: TrampolineZeroCast (24 casts total with support, minimal but slow)

**All 223 tests passing with production interpreter** ✅
