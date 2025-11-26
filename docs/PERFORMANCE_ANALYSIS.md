# Performance Analysis: Optimization Targets

Analysis of three performance gaps identified in library benchmarks vs cats-parse.

## Executive Summary

**Target Areas:**
1. **Many repetition**: 2-3x slower than cats-parse
2. **sepBy/CSV parsing**: 1.5-3.5x slower than cats-parse
3. **Sequential composition**: cats-parse has exceptional performance

**Root Causes Identified:**
- Many: List concatenation in error accumulation (`accErrors ++ errors`)
- sepBy: Implemented via flatMap/many composition, inherits Many overhead
- Sequential: Nested pattern matching overhead, no short-circuit optimizations

**Impact Assessment:**
- Many/sepBy are frequently used in production parsers (high impact)
- Sequential composition is fundamental (affects all complex parsers)
- All three areas are interconnected (optimizing one helps others)

---

## 1. Many Repetition Performance

### Current Implementation

Located in `Interpreter.scala:854-882`:

```scala
private def interpretManyI[E, A](p: Parser[E, A], state: ParserState): IResult[E, List[A]] = {
  val acc           = scala.collection.mutable.ArrayBuffer.empty[A]
  var accErrors     = List.empty[E]
  var totalConsumed = 0
  var continue      = true

  while (continue) {
    val snapshot = state.save
    interpretI(p, state) match {
      case Result.Success(value, consumed) =>
        acc += value
        totalConsumed += consumed
      case Result.Partial(value, errors, consumed) =>
        acc += value
        accErrors = accErrors ++ errors  // ⚠️ BOTTLENECK: O(n) List concatenation
        totalConsumed += consumed
      case LazyFailure(_, _) =>
        state.restore(snapshot)
        continue = false
    }
  }

  if (accErrors.isEmpty) {
    Result.Success(acc.toList, totalConsumed)
  } else {
    Result.Partial(acc.toList, accErrors, totalConsumed)
  }
}
```

### Performance Issues

**Primary Bottleneck: Error List Concatenation**
```scala
accErrors = accErrors ++ errors  // O(n) operation in tight loop
```

- List concatenation `++` is O(n) where n is the length of the left list
- In a loop with k iterations, this becomes O(k²) for error accumulation
- Every iteration that produces a Partial result pays this cost

**Secondary Issue: Final List Conversion**
```scala
acc.toList  // Allocates new List from ArrayBuffer
```
- ArrayBuffer → List conversion requires allocation
- Not a major issue, but adds to overhead

**Tertiary Issue: Snapshot Save/Restore**
```scala
val snapshot = state.save  // Every iteration
```
- State snapshot taken on every iteration
- Necessary for backtracking, but adds per-iteration overhead

### cats-parse Approach

cats-parse uses an **Accumulator pattern** for efficient collection building:

```scala
// Simplified concept from cats-parse
trait Accumulator[A] {
  def add(item: A): Unit       // O(1) mutation
  def finish(): List[A]        // Final conversion
}
```

Key benefits:
1. **O(1) append** operations during parsing
2. **Batch allocation** - single conversion at end
3. **Specialized accumulators** for common types

### Optimization Strategy

#### Option 1: Use ArrayBuffer for Errors (Quick Win)
```scala
val accErrors = scala.collection.mutable.ArrayBuffer.empty[E]

// In loop:
accErrors ++= errors  // Still creates iterator, but amortized O(1)
```
**Pros**: Simple change, significant improvement
**Cons**: Still creates temporary collections

#### Option 2: Separate Error List Builder (Better)
```scala
val acc = scala.collection.mutable.ArrayBuffer.empty[A]
val errAcc = scala.collection.mutable.ListBuffer.empty[E]

// In loop:
errAcc ++= errors  // ListBuffer has efficient append
```
**Pros**: O(1) amortized appends, clean conversion
**Cons**: Slightly more complex

#### Option 3: cats-parse Style Accumulator (Best, More Work)
```scala
trait Accumulator[A] {
  def add(item: A): Unit
  def result(): List[A]
}

object Accumulator {
  def forList[A]: Accumulator[A] = new ListAccumulator[A]
}

private class ListAccumulator[A] extends Accumulator[A] {
  private val buf = new ArrayBuffer[A](32)  // Pre-sized
  def add(item: A): Unit = buf += item
  def result(): List[A] = buf.toList
}
```
**Pros**: Maximum performance, reusable pattern
**Cons**: More infrastructure, testing needed

### Recommended Approach

**Phase 1 (Immediate)**: Use ArrayBuffer for errors
- Change `List.empty[E]` → `ArrayBuffer.empty[E]`
- Change `accErrors ++ errors` → `accErrors ++= errors`
- Change result construction to `errAcc.toList`
- **Expected gain**: 30-50% improvement on Partial-heavy workloads

**Phase 2 (v0.3.0)**: Implement Accumulator abstraction
- Create reusable Accumulator trait
- Optimize for List, Vector, common collection types
- Use in Many, Many1, sepBy implementations
- **Expected gain**: Additional 20-30%, matching cats-parse

---

## 2. sepBy/CSV Parsing Performance

### Current Implementation

Located in `Combinators.scala:296-297`:

```scala
def sepBy1[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  flatMap(p, (head: A) => map(many(zipRight(sep, p)), (tail: List[A]) => head :: tail))
```

### Performance Issues

**Problem Chain:**
1. `sepBy1` is built from `many(zipRight(sep, p))`
2. `many` has the error accumulation bottleneck (see section 1)
3. `zipRight(sep, p)` = `flatMap(sep, _ => p)` - nested flatMap overhead
4. Pattern: `head :: tail` requires allocation

**Benchmark Results:**
- CSV 3 numbers: 11ms Rumil vs 3ms cats (3.67x slower)
- CSV 10 numbers: 3ms Rumil vs 2ms cats (1.50x slower)

The overhead is **proportional to the number of elements**, consistent with
the Many accumulation overhead.

### cats-parse Approach

cats-parse provides `repSep` which likely:
1. Specialized implementation for separator pattern
2. Single loop instead of Many + zipRight composition
3. Accumulator pattern for efficient building

### Optimization Strategy

#### Option 1: Optimize Many (Indirect)
Fix Many's error accumulation → sepBy automatically improves
**Pros**: Single fix helps many combinators
**Cons**: Doesn't address composition overhead

#### Option 2: Specialized sepBy Implementation (Direct)
```scala
// Specialized interpreter case
case Parser.SepBy1(elem, sep) =>
  interpretI(elem, state) match {
    case Result.Success(head, consumed1) =>
      val acc = ArrayBuffer(head)
      val errAcc = ArrayBuffer.empty[E]
      var totalConsumed = consumed1
      var continue = true

      while (continue) {
        val snapshot = state.save
        interpretI(sep, state) match {
          case Result.Success(_, sepConsumed) =>
            interpretI(elem, state) match {
              case Result.Success(value, elemConsumed) =>
                acc += value
                totalConsumed += sepConsumed + elemConsumed
              case Result.Partial(value, errors, elemConsumed) =>
                acc += value
                errAcc ++= errors
                totalConsumed += sepConsumed + elemConsumed
              case LazyFailure(_, _) =>
                state.restore(snapshot)
                continue = false
            }
          case Result.Partial(_, errors, sepConsumed) =>
            // Handle partial separator
            errAcc ++= errors
            // Continue or stop based on recovery strategy
          case LazyFailure(_, _) =>
            state.restore(snapshot)
            continue = false
        }
      }

      if (errAcc.isEmpty) {
        Result.Success(acc.toList, totalConsumed)
      } else {
        Result.Partial(acc.toList, errAcc.toList, totalConsumed)
      }
    case // ... handle head failure
  }
```

**Pros**: Maximum performance, avoids composition overhead
**Cons**: More code to maintain, duplicates logic

### Recommended Approach

**Phase 1**: Optimize Many (solves 70% of the problem)
- sepBy inherits Many improvements automatically
- Simpler, single fix

**Phase 2**: Profile sepBy specifically
- If still significantly slower after Many optimization
- Consider specialized implementation
- Measure composition overhead (zipRight, flatMap)

**Estimated gain**: 50-70% from Many optimization alone

---

## 3. Sequential Composition Performance

### Current Implementation

Located in `Interpreter.scala:209-232`:

```scala
case Parser.FlatMap(source, f) =>
  interpretI(source, state) match {
    case Result.Success(value, consumed1) =>
      interpretI(f(value), state) match {
        case Result.Success(value2, consumed2) =>
          Result.Success(value2, consumed1 + consumed2)
        case Result.Partial(value2, errors2, consumed2) =>
          Result.Partial(value2, errors2, consumed1 + consumed2)
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }
    case Result.Partial(value, errors1, consumed1) =>
      interpretI(f(value), state) match {
        case Result.Success(value2, consumed2) =>
          Result.Partial(value2, errors1, consumed1 + consumed2)
        case Result.Partial(value2, errors2, consumed2) =>
          Result.Partial(value2, errors1 ++ errors2, consumed1 + consumed2)  // ⚠️ BOTTLENECK
        case LazyFailure(mkErrors2, furthest) =>
          LazyFailure(() => errors1 ++ mkErrors2(), furthest)
      }
    case LazyFailure(mkErrs, loc) =>
      LazyFailure(mkErrs, loc)
  }
```

**Sequential composition via `~`:**
```scala
// In syntax/Extensions.scala
inline def ~[B](that: Parser[E, B]): Parser[E, (A, B)] =
  parser.core.zip(p, that)

// In core/Combinators.scala
def zip[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, (A, B)] =
  flatMap(p1, (a: A) => map(p2, (b: B) => (a, b)))
```

### Performance Issues

**1. Nested Pattern Matching Overhead**
- 9 distinct match paths (3 × 3 result types)
- Each sequential composition pays full pattern match cost
- 50 sequential parsers = 50 nested flatMap evaluations

**2. Error List Concatenation**
```scala
Result.Partial(value2, errors1 ++ errors2, consumed1 + consumed2)
```
- Same O(n) concatenation issue as Many
- Compounds with deep nesting

**3. No Short-Circuit Optimizations**
- All result constructors allocated, even for Success-only paths
- No special handling for known-pure parsers
- No fusion of adjacent operations

**4. Function Call Overhead**
```scala
zip(p1, p2) = flatMap(p1, (a: A) => map(p2, (b: B) => (a, b)))
```
- Every `~` creates closure for tuple construction
- Map creates another closure
- No inlining or specialization

### cats-parse Approach

**Key Optimization: Parser Type Specialization**
```scala
def product0[A, B](first: Parser0[A], second: Parser0[B]): Parser0[(A, B)] =
  first match {
    case f1: Parser[A] => product10(f1, second)
    case Impl.Pure(a) => second.map(Impl.ToTupleWith1(a))  // ⚠️ SHORT-CIRCUIT!
    // ... more optimized paths
  }
```

**Optimizations:**
1. **Known result short-circuiting**: `Pure(a) ~ p` → just map over p
2. **Right-associating products**: Optimizes nested `~` chains
3. **Avoiding unnecessary allocations**: Pre-allocated tuple constructors
4. **Type-specialized paths**: Different code for Parser vs Parser0

### Optimization Strategy

#### Option 1: Short-Circuit Pure Values (Quick Win)
```scala
case Parser.FlatMap(source, f) =>
  source match {
    case Parser.Succeed(value) =>
      // Skip interpretation of source, directly interpret continuation
      interpretI(f(value), state)
    case _ =>
      // Current nested match implementation
      interpretI(source, state) match { ... }
  }
```
**Pros**: Simple, helps common pattern
**Cons**: Limited scope, doesn't address deep nesting

#### Option 2: Optimize zip Separately
```scala
case Parser.Zip(p1, p2) =>  // Add explicit Zip case
  interpretI(p1, state) match {
    case Result.Success(v1, c1) =>
      interpretI(p2, state) match {
        case Result.Success(v2, c2) =>
          Result.Success((v1, v2), c1 + c2)  // Direct tuple, no closure
        // ... optimized paths
      }
    // ...
  }
```
**Pros**: Avoids flatMap overhead for ~
**Cons**: More cases in interpreter, duplicates some logic

#### Option 3: Continuation Fusion (Advanced)
Transform nested flatMaps into single continuation:
```scala
// Transform: flatMap(flatMap(p, f), g)
// Into: flatMap(p, x => flatMap(f(x), g))  [right-associated]
// Or: Parser.Chain(p, List(f, g))  [explicit chain]
```
**Pros**: Eliminates nested interpretation
**Cons**: Complex transformation, affects optimizer

#### Option 4: Implement Parser.Sequence (Specialized)
```scala
case class Sequence[E, A, B](p1: Parser[E, A], p2: Parser[E, B]) extends Parser[E, (A, B)]

// In interpreter:
case Parser.Sequence(p1, p2) =>
  // Optimized sequence interpretation
  // No closure allocation, direct tuple construction
```
**Pros**: Clean separation, optimizable
**Cons**: Need to update all combinators to use Sequence

### Recommended Approach

**Phase 1 (Quick Win)**: Add short-circuit for Pure
- Check for `Succeed` in flatMap source
- Skip interpretation overhead
- **Expected gain**: 10-20% on pure-heavy parsers

**Phase 2 (Significant)**: Add explicit Zip case
- Transform `zip(p1, p2)` to `Parser.Zip(p1, p2)`
- Specialized interpreter case
- Direct tuple construction, no closures
- **Expected gain**: 30-40% on sequential composition

**Phase 3 (Future)**: Fix error concatenation
- Same ArrayBuffer solution as Many
- Helps all nested composition
- **Expected gain**: Additional 10-20%

**Total estimated gain**: 50-80% improvement, approaching cats-parse performance

---

## 4. Implementation Priority

### High Priority (v0.2.0 - before release)

1. **Fix Many error accumulation**
   - Impact: HIGH (affects many, sepBy, all repetition)
   - Effort: LOW (ArrayBuffer change)
   - Risk: LOW (well-understood optimization)
   - **Estimated time**: 2-4 hours
   - **Expected gain**: 30-50% on repetition-heavy workloads

### Medium Priority (v0.2.x - post-release improvements)

2. **Add short-circuit for Pure in FlatMap**
   - Impact: MEDIUM (helps sequential composition)
   - Effort: LOW (simple pattern match)
   - Risk: LOW (additive optimization)
   - **Estimated time**: 1-2 hours
   - **Expected gain**: 10-20% on pure-heavy parsers

3. **Add explicit Zip case**
   - Impact: HIGH (fundamental to sequential composition)
   - Effort: MEDIUM (new case + combinator changes)
   - Risk: MEDIUM (touches core interpreter)
   - **Estimated time**: 4-6 hours
   - **Expected gain**: 30-40% on sequential composition

### Low Priority (v0.3.0 - major improvements)

4. **Implement Accumulator abstraction**
   - Impact: MEDIUM (cleaner code, slightly better perf)
   - Effort: MEDIUM (new abstraction + refactoring)
   - Risk: MEDIUM (API addition)
   - **Estimated time**: 6-8 hours
   - **Expected gain**: Additional 20-30%

5. **Specialized sepBy implementation**
   - Impact: MEDIUM (if still needed after Many fix)
   - Effort: MEDIUM (duplicates logic)
   - Risk: MEDIUM (maintenance burden)
   - **Estimated time**: 4-6 hours
   - **Expected gain**: 20-30% on CSV/sepBy workloads

---

## 5. Benchmarking Plan

After each optimization:

1. **Run comprehensive benchmarks**
   ```bash
   sbt "project core" "testOnly parser.ComprehensiveLibraryComparison"
   ```

2. **Focus metrics:**
   - Category 2 (Many): Target 2-3x → 1-1.5x
   - Category 6 (CSV): Target 1.5-3.5x → 1-1.5x
   - Category 4 (Sequential): Target 1-4x → 1-1.5x

3. **Regression testing:**
   - All 218 tests must pass
   - No performance regression on strengths (number parsing)
   - Stack safety maintained

4. **Update documentation:**
   - Update LIBRARY_COMPARISON_BENCHMARK.md with new results
   - Document optimization strategies used
   - Add performance notes to API docs if relevant

---

## 6. Risk Assessment

### Low Risk Optimizations
- ArrayBuffer for error accumulation
- Short-circuit Pure in FlatMap
- Well-tested, additive changes

### Medium Risk Optimizations
- Explicit Zip case
- Accumulator abstraction
- Require comprehensive testing, but limited blast radius

### High Risk Optimizations
- Continuation fusion
- Deep interpreter restructuring
- **Not recommended until v1.0+**

---

## 7. Competitive Analysis Context

### Current Standing (v0.2.0)

| Metric | Rumil | cats-parse | zio-parser |
|--------|-------|------------|------------|
| **Type Safety** | ✅ Best (33 casts) | Good (37 casts) | Poor (101 casts) |
| **Stack Safety** | ✅ Yes | ❌ No | ✅ Yes |
| **Performance** | Good (1-4x) | ✅ Excellent | Good |
| **Many** | 2-3x slower | ✅ Fastest | 2x slower |
| **sepBy** | 1.5-3.5x slower | ✅ Fastest | N/A |
| **Sequential** | 1-4x slower | ✅ Fastest | N/A |

### After Optimizations (Projected v0.2.1+)

| Metric | Rumil | cats-parse | zio-parser |
|--------|-------|------------|------------|
| **Type Safety** | ✅ Best (33 casts) | Good (37 casts) | Poor (101 casts) |
| **Stack Safety** | ✅ Yes | ❌ No | ✅ Yes |
| **Performance** | ✅ Excellent | ✅ Excellent | Good |
| **Many** | **~1-1.5x** | ✅ Fastest | 2x slower |
| **sepBy** | **~1-1.5x** | ✅ Fastest | N/A |
| **Sequential** | **~1-1.5x** | ✅ Fastest | N/A |

**Value Proposition:**
- **Only stack-safe library with competitive performance**
- **Best type safety in the ecosystem**
- **Approaching or matching cats-parse performance without sacrificing safety**

---

## 8. Conclusion

Three optimization targets identified, all stemming from **error accumulation strategy**:

1. **Many repetition**: List concatenation bottleneck
2. **sepBy**: Inherits Many overhead + composition cost
3. **Sequential composition**: Nested pattern matching + error concatenation

**Recommended path:**
- ✅ Fix Many error accumulation (HIGH priority, LOW effort, HIGH impact)
- ✅ Add Zip case (MEDIUM priority, MEDIUM effort, HIGH impact)
- ⏳ Profile and optimize sepBy if still needed (MEDIUM priority)
- ⏳ Implement Accumulator abstraction (LOW priority, code quality improvement)

**Expected outcome:**
- Rumil performance within 1-1.5x of cats-parse across all workloads
- Maintain superior type safety and stack safety
- Position as **the** choice for production parser combinators in Scala 3

**Timeline:**
- Many optimization: Can be done immediately
- Zip case: v0.2.1 (post-release bugfix version)
- Full optimization suite: v0.3.0 (next feature release)
