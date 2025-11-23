# Performance Analysis: Rumil vs cats-parse

## Executive Summary

Benchmarking reveals Rumil is 3-40x slower than cats-parse depending on the operation. This document presents findings from systematic profiling and proposes optimization strategies.

## Benchmark Results

| Operation | cats-parse | Rumil | Gap | B/op cats | B/op Rumil |
|-----------|-----------|-------|-----|-----------|------------|
| succeed (pure) | 1,214,699 | 55,577 | 22x | 16 | 456 |
| singleChar | 689,128 | 56,157 | 12x | 16 | 456 |
| satisfyDigit | 851,855 | 51,021 | 17x | 16 | 456 |
| stringShort (3 chars) | 253,569 | 49,024 | 5x | 16 | 456 |
| stringMedium (11 chars) | 231,655 | 51,546 | 4.5x | 16 | 456 |
| orFirst | 679,907 | 55,644 | 12x | 16 | 456 |
| orSecond (backtrack) | 772,750 | 25,856 | 30x | 16 | 664 |
| choice3First | 173,195 | 41,327 | 4x | - | - |
| choice3Last | 171,678 | 15,874 | 11x | - | - |
| many10 | 25,758 | 10,662 | 2.4x | 304 | 1,112 |

Key observation: `many10` has only 2.4x gap, proving core parsing logic is efficient when allocation is amortized.

## Root Cause Analysis

### 1. State Allocation Overhead (Primary Factor)

**Rumil allocates 456 bytes per parse vs cats-parse's ~48 bytes (9.5x more)**

#### Rumil ParserState breakdown:
```
ParserState object header:     ~16 bytes
input reference:                 8 bytes
offsetRef (Ref[Int]):          ~24 bytes
lineRef (Ref[Int]):            ~24 bytes
columnRef (Ref[Int]):          ~24 bytes
memo (MemoTable + HashMap):   ~150 bytes
lrStack (ArrayBuffer):         ~56 bytes
heads (mutable.Map):          ~120 bytes
───────────────────────────────────────
Total:                        ~456 bytes
```

#### cats-parse State breakdown:
```
State object header:           ~16 bytes
str reference:                   8 bytes
offset (var int):                4 bytes
error (nullable reference):      8 bytes
capture (var boolean):           1 bytes
locationMap (lazy):              8 bytes (reference only)
padding:                        ~3 bytes
───────────────────────────────────────
Total:                         ~48 bytes
```

**Key differences:**
- Rumil uses `Ref[Int]` objects; cats-parse uses primitive `var int`
- Rumil eagerly allocates left-recursion infrastructure (MemoTable, lrStack, heads) even when not needed
- cats-parse uses lazy LocationMap, only computed on error

### 2. Error Allocation on Backtrack (Secondary Factor)

**Rumil allocates ~208 extra bytes per failed branch; cats-parse allocates 0**

Evidence: `orSecond` benchmark (where first branch fails):
- cats-parse: 16 B/op (same as orFirst)
- Rumil: 664 B/op (456 base + 208 for failed branch)

#### Rumil error construction (on every failure):
```scala
Result.Failure(
  List(ParseError.Unexpected(c.toString, Set(expected), loc)),
  loc
)
```
Creates: List cons cell, ParseError object, String, Set, Location tuple

#### cats-parse error handling:
```scala
var error: Eval[Chain[Expectation]] = null  // starts null
state.error = Eval.later(...)               // deferred, not computed
```
- Null on success path (no allocation)
- `Eval.later` wraps construction (only evaluated at final failure)

### 3. Dispatch Mechanism (Tertiary Factor)

**Rumil uses pattern matching with linear instanceof chain; cats-parse uses O(1) vtable dispatch**

#### Rumil bytecode for interpret():
```
instanceof Parser$Succeed  → branch if no
instanceof Parser$Fail     → branch if no
instanceof Parser$Satisfy  → branch if no
... (22 cases total)
```
Plus for each match: `checkcast` → `unapply` → field extraction

#### cats-parse bytecode:
```
invokevirtual parseMut     // single vtable lookup
```

While each instanceof is fast (~1-2 cycles), they accumulate. More significantly, the `unapply` calls add method invocation overhead.

## Why `many` Performs Better

The `many` combinator (2.4x gap vs 12x for singleChar) demonstrates that core parsing logic is not the bottleneck:

```
many10 amortization:
- 456 byte state allocation: paid once
- 10 char parses: ~same work as cats-parse
- Result: overhead diluted over iterations
```

This proves: **fix the allocation overhead, and performance becomes competitive.**

---

## Optimization Strategies

### Strategy A: Lazy Left-Recursion Infrastructure

**Problem:** MemoTable, lrStack, heads always allocated (~320 bytes)

**Observation:** Only `rule()` combinator needs LR support. Simple parsers pay for infrastructure they never use.

**Approach:** Defer allocation until first `Memo` case encountered.

**Considerations:**
- Requires nullable fields or lazy initialization
- Must handle first-use allocation atomically
- Could use a flag or sentinel value

### Strategy B: Primitive State Variables

**Problem:** 3 × Ref[Int] = ~72 bytes of boxing overhead

**Observation:** cats-parse uses `var int` directly in State class.

**Approach:** Replace Ref[Int] with primitive `var` fields.

**Considerations:**
- Loses the "controlled mutation" abstraction (Eru pattern)
- ParserState is already our mutation boundary - Refs may be over-engineering
- Could keep Ref for external API but use primitives internally

### Strategy C: Lazy Error Construction

**Problem:** ~208 bytes allocated per failed branch, even when recovered

**Observation:** Most failures in choice/or are recovered - errors discarded immediately.

**Approach:** Defer error object creation until actually needed.

**Options:**
1. **Nullable error field:** Track failure with offset-based sentinel, construct error only at final failure
2. **Eval/lazy wrapping:** Similar to cats-parse `Eval.later`
3. **Error continuation:** Pass error constructor function, only call when needed

**Considerations:**
- Changes Result type or interpreter logic significantly
- Must preserve error location accuracy
- Could complicate furthest-error tracking

### Strategy D: Optimized Dispatch

**Problem:** Linear instanceof chain for 22 cases

**Observation:** JVM can optimize pattern matching better with certain patterns.

**Approach Options:**
1. **Reorder cases:** Put most common cases (Satisfy, StringMatch, Map, FlatMap) first
2. **Tagless final:** Replace enum with typeclass-based approach (major rewrite)
3. **Visitor pattern:** Each Parser case has `accept` method (adds vtable lookup but removes instanceof chain)
4. **Sealed trait + match:** May compile to tableswitch if ordinals are dense

**Considerations:**
- Reordering is low-effort, low-reward
- Architectural changes (tagless/visitor) are high-effort
- May not be worth optimizing until allocation issues fixed

### Strategy E: Specialized Fast Paths

**Problem:** Simple parsers go through full interpreter machinery

**Observation:** `char('a')` doesn't need LR support, error recovery, etc.

**Approach:** Detect "simple" parser trees and use optimized execution.

**Options:**
1. **Fusion:** Combine common patterns (e.g., `many(satisfy(...))`) into single optimized operation
2. **Compilation:** Pre-analyze parser tree, generate optimized execution plan
3. **Inline caching:** Remember which Parser subtype was seen, skip instanceof on repeat

**Considerations:**
- High complexity for uncertain gains
- Better to fix fundamentals (A, B, C) first

---

## Recommended Priority

1. **Strategy A (Lazy LR)** - High impact, moderate effort
   - Saves ~320 bytes per parse for non-LR parsers
   - Most parsers don't use `rule()`

2. **Strategy C (Lazy Errors)** - High impact, moderate effort
   - Eliminates ~208 bytes per backtrack
   - Critical for choice-heavy grammars

3. **Strategy B (Primitive vars)** - Medium impact, low effort
   - Saves ~48 bytes (Ref overhead)
   - Simple mechanical change

4. **Strategy D (Dispatch)** - Low impact, variable effort
   - Reordering is easy but limited benefit
   - Major rewrites not justified yet

5. **Strategy E (Fast paths)** - Unknown impact, high effort
   - Defer until fundamentals optimized

---

## Success Metrics

After optimizations, target benchmarks:

| Operation | Current Gap | Target Gap |
|-----------|-------------|------------|
| singleChar | 12x | 3-4x |
| orSecond | 30x | 4-5x |
| choice3Last | 11x | 3-4x |
| many10 | 2.4x | 1.5-2x |

Rationale: 2-4x overhead is acceptable for an interpreter-based design with richer features (LR support, line/column tracking, resilient parsing).

---

## Appendix: Profiling Commands

```bash
# GC allocation profiling
sbt "benchmarks/Jmh/run -i 3 -wi 2 -f 1 -t 1 -prof gc .*benchmarkName"

# Stack profiling
sbt "benchmarks/Jmh/run -i 3 -wi 2 -f 1 -t 1 -prof stack .*benchmarkName"

# Run specific primitive benchmarks
sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 -t 1 PrimitiveBenchmarks"
```
