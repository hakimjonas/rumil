# Memoization Performance Analysis

## Executive Summary

Through rigorous benchmarking, we discovered that **memoization (.memoize and rule) is harmful for typical forward-parsing scenarios**. Both `.memoize` and `rule` add significant overhead (157% and 479% respectively) when cache hits are rare, making them **only beneficial in specific backtracking scenarios**.

## Benchmark Results

### Rigorous Memoization Benchmarks

We created comprehensive benchmarks (`MemoizationBenchmarksRigorous.scala`) testing four scenarios:

#### 1. Pure Cache Hit Performance (Same Position)
- **.memoize**: 0.03ms (2.33x faster than rule)
- **rule**: 0.07ms
- **Finding**: `.memoize` is faster when cache hits occur

#### 2. Backtracking with Cache Hits
- **baseline**: 5.01ms
- **.memoize**: 2.02ms (2.48x faster than baseline)
- **rule**: 2.01ms (2.49x faster than baseline)
- **Finding**: Both provide ~2.5x speedup vs baseline in backtracking

#### 3. Cache Misses (Different Positions) ⚠️ **CRITICAL**
- **baseline**: 2.00ms
- **.memoize**: 5.15ms (**157.5% overhead**)
- **rule**: 11.58ms (**479.0% overhead**)
- **Finding**: Memoization is harmful when cache misses dominate!

#### 4. Realistic Mixed Scenario
- **baseline**: 3.06ms
- **.memoize**: 4.17ms (0.73x slower)
- **rule**: 5.01ms (0.61x slower)
- **Finding**: Real-world workloads often have too few cache hits to justify overhead

### Real-World Parser Performance

We attempted to optimize XML, JSON, and TOML parsers by adding `.memoize` to frequently-called parsers:
- `xmlName` in XML parser
- `jsonNumber` and `jsonString` in JSON parser
- `ws` (whitespace) in all parsers

**Results: ALL PARSERS GOT SLOWER**

| Parser | Baseline | With .memoize | Slowdown |
|--------|----------|---------------|----------|
| XML Small | 24.91ms | 35.92ms | **+44%** |
| XML Medium | 52.71ms | 73.91ms | **+40%** |
| XML Large | 163.94ms | 235.13ms | **+43%** |
| JSON Numbers | 49.06ms | 56.00ms | **+14%** |
| JSON Objects | 117.83ms | 135.29ms | **+15%** |
| JSON Nested | 19.65ms | 21.92ms | **+12%** |
| TOML Simple | 12.19ms | 14.06ms | **+15%** |

## Why Memoization Failed

### The Fundamental Issue

Memoization works by caching parse results at specific input positions. For this to be beneficial:
1. The parser must be tried **multiple times at the SAME position**
2. Cache hits must occur frequently enough to offset the overhead

### Why Real-World Parsers Don't Benefit

**Forward-parsing scenario** (typical case):
```
Input: <person id="123" name="Alice">
Position: 0 → 1 → 2 → 3 → ... → N

- xmlName called at position 1 → parses "person", advances to position 7
- xmlName called at position 10 → parses "id", advances to position 12
- xmlName called at position 14 → parses "123", advances to position 17
...
```

Each call to `xmlName` is at a **different position** → **zero cache hits** → **only overhead**

**Backtracking scenario** (where memoization helps):
```
Input: "abcz"
Parser: (expensiveWork ~ char('x')) | (expensiveWork ~ char('y')) | (expensiveWork ~ char('z'))

Position 0: expensiveWork called → parses "abc", tries 'x', fails, BACKTRACKS
Position 0: expensiveWork called again → CACHE HIT! → tries 'y', fails, BACKTRACKS
Position 0: expensiveWork called again → CACHE HIT! → tries 'z', succeeds
```

Multiple attempts at position 0 → **cache hits** → **2.5x speedup**

## Memoization Overhead Breakdown

### `.memoize` (SimpleMemoTable)
- Hash map lookup: `memoTable.get(state.offset)`
- Hash map insert: `memoTable.put(state.offset, result)`
- Object allocation for cache entry
- **Overhead: ~157% for cache misses**

### `rule` (Parser.Memo with LR)
- All `.memoize` overhead, PLUS:
- Left-recursion detection logic
- Seed value management
- Growth iteration tracking
- **Overhead: ~479% for cache misses**

## Guidelines for Using Memoization

### ✅ USE `.memoize` when:
1. **Parser is expensive** (non-trivial computation)
2. **Backtracking occurs** (alternatives try the same parser at the same position)
3. **NOT left-recursive**

Example:
```scala
val expensiveParser = (complexComputation ~ validation).memoize

val withBacktracking =
  (expensiveParser ~ char('x')) |
  (expensiveParser ~ char('y')) |
  (expensiveParser ~ char('z'))
```

### ✅ USE `rule` when:
1. **Left-recursion is needed**
2. **Named recursive rules** (e.g., expression parsers)

Example:
```scala
lazy val expr: Parser[ParseError, Expr] = rule {
  (expr ~ char('+') ~ term).map(Add) |  // Left-recursive!
  term
}
```

### ❌ AVOID memoization when:
1. **Parser is simple/fast** (like `satisfy`, `char`, `digit`)
2. **Forward-parsing dominates** (parser advances through input linearly)
3. **Cache hits are unlikely** (parser called at many different positions)
4. **No backtracking** (single parse path through the grammar)

Examples of **DO NOT MEMOIZE**:
```scala
// ❌ Bad: advances through input, no backtracking
private def xmlName = /* parser */.memoize

// ❌ Bad: simple, fast parser
private def ws = satisfy(_.isWhitespace).many.void.memoize

// ❌ Bad: advances linearly, no repeated positions
private def jsonNumber = /* parser */.memoize
```

## Recommendations for Rumil

1. **Keep both `.memoize` and `rule` in the API** - they serve different purposes:
   - `.memoize`: Fast memoization for backtracking (no LR)
   - `rule`: Full packrat with left-recursion support

2. **Document the overhead clearly** - warn users that memoization is NOT a free optimization

3. **Provide benchmarking tools** - help users measure whether memoization helps their specific grammar

4. **Add examples** of when to use each:
   - Expression parsers with left-recursion → `rule`
   - Expensive lookahead with backtracking → `.memoize`
   - Simple forward parsing → neither

5. **Consider selective memoization** - only memoize the expensive parts that benefit from it

## Key Takeaways

1. **Memoization is NOT a universal optimization** - it helps specific scenarios, hurts others
2. **Cache-miss overhead is real** - 157% for `.memoize`, 479% for `rule`
3. **Forward-parsing doesn't benefit** - typical parsers advance linearly without cache hits
4. **Backtracking is the key** - multiple attempts at the same position enable cache hits
5. **Profile before optimizing** - measure your specific use case before adding memoization

## Benchmark Artifacts

- Baseline benchmarks: `/tmp/parser_baseline_complete.txt`
- With memoization: `/tmp/parser_optimized.txt`
- Rigorous memoization benchmarks: `/tmp/benchmark_final.txt`
- Source files:
  - `benchmarks/src/main/scala/parser/benchmarks/MemoizationBenchmarksRigorous.scala`
  - `benchmarks/src/main/scala/parser/benchmarks/ParserOptimizationBaseline.scala`
