# Benchmark Methodology & Fairness Analysis

**Date**: November 26, 2025

## Problem Statement

When benchmarking Rumil vs zio-parser, we discovered several methodological issues that cast doubt on the initial results (1220x difference). This document analyzes fairness concerns and establishes proper methodology.

---

## Issues Discovered

### 1. Parser Construction Overhead (CRITICAL)

**Problem**: Initial benchmarks constructed parsers INSIDE @Benchmark methods.

```scala
@Benchmark
def rumil_choice10(): Any = {
  import parser.core._
  // Parser construction happens EVERY invocation!
  val p = string("apple") | string("banana") | ...
  run(p, input)
}
```

**Impact**:
- Measures: construction time + compilation time + parsing time
- zio-parser has Parser → ParserOp compilation step
- This overhead dominates for small inputs
- Unfairly penalizes zio-parser

**Fix**: Move parser construction to `@Setup` method.

### 2. Incorrect Test Inputs

**Problem**: `deepFlatMap` benchmark used wrong input length.

```scala
var p = char('1')
for (_ <- 1 to 100) {
  p = p.flatMap(_ => char('1'))
}
val input = "1" * 100  // WRONG! Need 101 chars (initial + 100 continuations)
```

**Impact**:
- Both parsers were FAILING, not succeeding
- Benchmark measured error handling, not success path
- Invalidates results entirely

**Fix**: Use `"1" * 101` for 100 flatMaps.

### 3. API Semantic Differences

**Problem**: Rumil and zio-parser have different APIs for similar operations.

| Operation | Rumil | zio-parser | Equivalent? |
|-----------|-------|------------|-------------|
| Sequential | `~` (zip) | `~` (zip) | YES |
| Ignore left | `~>` | `~>` (zipRight) | YES |
| FlatMap | `.flatMap` | N/A (only on Parser) | NO - used `~>` instead |
| Many | `many()` | `.repeat` | Semantically yes |
| Choice | `\|` | `\|` | YES |

**Impact**:
- `flatMap` vs `~>` may have different performance characteristics
- Hard to ensure truly equivalent operations

### 4. Type System Differences

**Problem**: zio-parser's type-level constraints limit certain patterns.

```scala
// Rumil: works fine
val p = digit ~ digit ~ digit ~ digit ~ digit ~
        digit ~ digit ~ digit ~ digit ~ digit

// zio-parser: type error after ~9 zips
// No given instance of PUnzippable.In[(((...)), Char), Any]
```

**Impact**:
- Cannot create identical test cases
- Must use different combinators (e.g., `repeat(10)` vs `~ ~ ~ ...`)
- Makes apples-to-apples comparison impossible for some patterns

---

## Proper Methodology

### Setup Phase (Once)

```scala
@State(Scope.Benchmark)
class FairBenchmark {
  var parser: ParserType = _
  var input: String = _

  @Setup
  def setup(): Unit = {
    // Construct parsers ONCE
    parser = buildComplexParser()
    input = generateTestInput()
  }

  @Benchmark
  def benchmark(): Any = {
    // Only measure parsing, not construction
    parser.parse(input)
  }
}
```

### Input Validation

Before benchmarking, validate with test harness:

```scala
// Verify both parsers succeed
assert(rumilParser.parse(input).isSuccess)
assert(zioParser.parse(input).isRight)

// Verify results are equivalent
val rumilResult = rumilParser.parse(input).get
val zioResult = zioParser.parse(input).toOption.get
assert(resultsEquivalent(rumilResult, zioResult))
```

### Benchmark Categories

Only compare operations where APIs align:

| Category | Rumil | zio-parser | Fair? |
|----------|-------|------------|-------|
| **Choice** | `a \| b \| c` | `a \| b \| c` | ✅ YES |
| **Sequential** | `a ~ b ~ c` | `a ~ b ~ c` (if < 10) | ✅ YES |
| **Repetition** | `many(p)` | `p.repeat` | ✅ YES (semantically) |
| **FlatMap chains** | `.flatMap` chains | `~>` chains | ⚠️ MAYBE (different ops) |
| **Deep nesting** | 10+ zips | 10+ zips | ❌ NO (type error) |

---

## Revised Results (Pending)

Need to re-run with:
1. ✅ Parsers constructed in @Setup
2. ✅ Correct input lengths validated
3. ⏳ Only fair comparisons included
4. ⏳ More iterations for statistical confidence

---

## Open Questions

### 1. Is Compilation Overhead Significant?

**Hypothesis**: zio-parser's Parser → ParserOp compilation is expensive upfront.

**Test**:
```scala
// Measure compilation only
@Benchmark
def zio_compilation(): Any = {
  buildComplexParser() // Don't parse, just compile
}

// Measure amortized cost
@Benchmark
def zio_reusedParser(): Any = {
  prebuiltParser.parse(input) // Parser built in @Setup
}
```

### 2. What Is zio-parser Optimized For?

**Observation**: zio-parser is slow in our benchmarks, but it exists for a reason.

**Possibilities**:
- Bidirectional parsing/printing (not measured in our benchmarks)
- Correctness over speed
- Integration with ZIO ecosystem (streaming, etc.)
- Different workloads (very large inputs?)

**Need**: Understand zio-parser's design goals before claiming it's "slow".

### 3. Why Is Choice So Slow in zio-parser?

**Data**: 1220x slower (even if construction is factored out, this seems extreme).

**Hypotheses**:
- Backtracking implementation difference?
- Error collection overhead?
- Bidirectionality tax?

**Need**: Profile with JMH `-prof stack` to find hotspot.

---

## Conclusions (Tentative)

### What We Know

1. **Initial benchmarks were flawed**
   - Construction overhead measured
   - Wrong inputs used
   - Results invalidated

2. **APIs differ significantly**
   - Makes perfect comparison impossible
   - Must accept some semantic differences

3. **Need rigorous validation**
   - Every benchmark needs correctness test
   - Must understand what we're measuring

### What We Don't Know Yet

1. **Actual performance difference** (after fixes)
2. **Root cause of zio-parser slowness** (if it persists)
3. **zio-parser's design goals** (optimization targets)
4. **Whether Chunk matters** (collection performance)

### Next Steps

1. ☐ Run FairTrampolineComparison with fixed methodology
2. ☐ Profile both libraries to understand hotspots
3. ☐ Research zio-parser's documentation for design philosophy
4. ☐ Measure compilation vs execution time separately
5. ☐ Only make claims backed by rigorous data

---

## Lesson Learned

**"Extraordinary claims require extraordinary evidence."**

A 1220x performance difference is extraordinary. Before claiming it:
- Validate methodology rigorously
- Ensure apples-to-apples comparison
- Understand what you're measuring
- Profile to find root causes
- Research the compared library's goals

Benchmark methodology matters as much as the results.
