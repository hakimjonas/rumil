# Task: Implement Comprehensive Benchmarks (ROADMAP 2.2)

## Context

You are working on **Rumil**, a Scala 3 parser combinator library. All core features are implemented, and we need comprehensive performance benchmarks to:
1. Establish baseline performance metrics
2. Compare against competing libraries (fastparse, cats-parse)
3. Measure optimization opportunities for future work
4. Provide concrete performance data for documentation

**Current Status:**
- ✅ Priority 1 complete (Core features, Documentation, Debugging)
- ✅ Priority 3.3 complete (CI/CD infrastructure)
- ✅ Priority 2.1 complete (Left Recursion Support)
- 🎯 Now implementing Priority 2.2: Comprehensive Benchmarks

**Author:** Hakim Jonas Ghoula <hakim@ghoula.net>

## Critical Constraints

1. **NO CLAUDE/ANTHROPIC ATTRIBUTION**
   - NEVER add "Generated with Claude Code" or similar
   - NO "Co-Authored-By: Claude" in commits
   - Author MUST be: Hakim Jonas Ghoula <hakim@ghoula.net>

2. **USE JMH (Java Microbenchmark Harness)**
   - Industry-standard benchmarking tool
   - Prevents JIT optimization artifacts
   - Provides statistical analysis (mean, std dev, percentiles)
   - Warm-up iterations to reach steady state

3. **FAIR COMPARISONS**
   - Same input data for all libraries
   - Same JVM settings
   - Measure equivalent operations
   - Document library versions tested

4. **SCIENTIFIC RIGOR**
   - Multiple iterations with warm-up
   - Statistical analysis
   - Document variance and confidence intervals
   - Test on realistic data sizes

## Goal: Establish Performance Baselines

We need to answer these questions with data:

1. **How fast is Rumil compared to fastparse?** (Industry standard)
2. **How fast is Rumil compared to cats-parse?** (Pure FP alternative)
3. **What's the overhead of error recovery?** (Resilient parsing)
4. **What's the overhead of GreenNode?** (Lossless syntax trees)
5. **What's the overhead of left recursion?** (Memoization cost)
6. **What operations are bottlenecks?** (Optimization targets)

## Your Tasks

### Phase 1: Set Up JMH Infrastructure

#### 1.1 Add JMH Plugin and Dependencies

Add to `project/plugins.sbt`:
```scala
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
```

Update `build.sbt` to add benchmark module:
```scala
// Add after existing modules

lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "rumil-benchmarks",
    libraryDependencies ++= Seq(
      // Competing libraries for comparison
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "org.typelevel" %% "cats-parse" % "1.0.0",

      // JSON parsing for comparison
      "io.circe" %% "circe-parser" % "0.14.10",

      // For realistic test data
      "org.scalacheck" %% "scalacheck" % "1.18.1"
    ),
    publish / skip := true  // Don't publish benchmarks
  )
  .dependsOn(core, parsers, interop)

// Update root project to aggregate benchmarks
lazy val root = (project in file("."))
  .aggregate(core, parsers, interop, benchmarks)
  .settings(
    name := "rumil",
    publish / skip := true
  )
```

#### 1.2 Create Benchmark Directory Structure

Create directories:
```bash
mkdir -p benchmarks/src/main/scala/parser/benchmarks
mkdir -p benchmarks/src/main/resources
```

### Phase 2: Implement Core Benchmarks

#### 2.1 JSON Parsing Benchmark

Create `benchmarks/src/main/scala/parser/benchmarks/JsonBenchmark.scala`:

```scala
package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import parsers.json.{JsonParser => RumilJsonParser}
import io.circe.parser.{parse => circeParseJson}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = Array("-Xmx2G"))
class JsonBenchmark {

  // Test data - various sizes and complexity
  var smallJson: String = _
  var mediumJson: String = _
  var largeJson: String = _
  var deeplyNestedJson: String = _

  @Setup
  def setup(): Unit = {
    // Small: Simple object with a few fields
    smallJson = """{"name":"John","age":30,"city":"NYC"}"""

    // Medium: Array of objects (typical API response)
    mediumJson = """[
      {"id":1,"name":"Alice","email":"alice@example.com","active":true},
      {"id":2,"name":"Bob","email":"bob@example.com","active":false},
      {"id":3,"name":"Charlie","email":"charlie@example.com","active":true}
    ]""" * 10  // Repeat for realistic size

    // Large: Realistic API response (~10KB)
    largeJson = scala.io.Source.fromResource("large-response.json").mkString

    // Deeply nested: Test recursion performance
    deeplyNestedJson = "[" * 50 + "42" + "]" * 50
  }

  // Rumil benchmarks
  @Benchmark
  def rumilSmall(): Unit = {
    RumilJsonParser.jsonValue.run(smallJson)
  }

  @Benchmark
  def rumilMedium(): Unit = {
    RumilJsonParser.jsonValue.run(mediumJson)
  }

  @Benchmark
  def rumilLarge(): Unit = {
    RumilJsonParser.jsonValue.run(largeJson)
  }

  @Benchmark
  def rumilDeeplyNested(): Unit = {
    RumilJsonParser.jsonValue.run(deeplyNestedJson)
  }

  // Circe benchmarks (for comparison)
  @Benchmark
  def circeSmall(): Unit = {
    circeParseJson(smallJson)
  }

  @Benchmark
  def circeMedium(): Unit = {
    circeParseJson(mediumJson)
  }

  @Benchmark
  def circeLarge(): Unit = {
    circeParseJson(largeJson)
  }

  @Benchmark
  def circeDeeplyNested(): Unit = {
    circeParseJson(deeplyNestedJson)
  }
}
```

#### 2.2 Expression Parser Benchmark

Create `benchmarks/src/main/scala/parser/benchmarks/ExpressionBenchmark.scala`:

```scala
package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import parser.core._
import parser.syntax._

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
class ExpressionBenchmark {

  // Arithmetic expression parser (left-associative)
  lazy val number: Parser[ParseError, Int] =
    digit.many1.map(_.mkString.toInt)

  lazy val expr: Parser[ParseError, Int] = recursive {
    term.chainl1(
      (char('+').as((a: Int, b: Int) => a + b)) |
      (char('-').as((a: Int, b: Int) => a - b))
    )
  }

  lazy val term: Parser[ParseError, Int] = recursive {
    factor.chainl1(
      (char('*').as((a: Int, b: Int) => a * b)) |
      (char('/').as((a: Int, b: Int) => a / b))
    )
  }

  lazy val factor: Parser[ParseError, Int] =
    number | (char('(') *> expr <* char(')'))

  var simpleExpression: String = _
  var complexExpression: String = _
  var deeplyNested: String = _

  @Setup
  def setup(): Unit = {
    simpleExpression = "1+2*3"
    complexExpression = "10+20*30-40/2+5*6-7"
    deeplyNested = "(" * 20 + "42" + ")" * 20
  }

  @Benchmark
  def parseSimple(): Unit = {
    expr.run(simpleExpression)
  }

  @Benchmark
  def parseComplex(): Unit = {
    expr.run(complexExpression)
  }

  @Benchmark
  def parseDeeplyNested(): Unit = {
    expr.run(deeplyNested)
  }
}
```

#### 2.3 Combinator Performance Benchmark

Create `benchmarks/src/main/scala/parser/benchmarks/CombinatorBenchmark.scala`:

```scala
package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import parser.core._
import parser.syntax._

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
class CombinatorBenchmark {

  var input: String = _

  @Setup
  def setup(): Unit = {
    input = "a" * 1000
  }

  // Test individual combinator performance
  @Benchmark
  def benchChar(): Unit = {
    char('a').run("a")
  }

  @Benchmark
  def benchString(): Unit = {
    string("hello").run("hello")
  }

  @Benchmark
  def benchMany(): Unit = {
    char('a').many.run(input)
  }

  @Benchmark
  def benchMany1(): Unit = {
    char('a').many1.run(input)
  }

  @Benchmark
  def benchSepBy(): Unit = {
    val p = char('a').sepBy(char(','))
    p.run("a,a,a,a,a")
  }

  @Benchmark
  def benchMap(): Unit = {
    char('a').map(_.toUpper).run("a")
  }

  @Benchmark
  def benchFlatMap(): Unit = {
    char('a').flatMap(c => char('b').map(b => (c, b))).run("ab")
  }

  @Benchmark
  def benchOr(): Unit = {
    (char('a') | char('b') | char('c')).run("c")
  }
}
```

#### 2.4 Error Recovery Overhead Benchmark

Create `benchmarks/src/main/scala/parser/benchmarks/ErrorRecoveryBenchmark.scala`:

```scala
package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import parser.core._
import parser.syntax._
import parsers.json.JsonParser

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
class ErrorRecoveryBenchmark {

  var validJson: String = _
  var invalidJson: String = _

  @Setup
  def setup(): Unit = {
    // Valid JSON
    validJson = """{"name":"John","age":30,"active":true}"""

    // Invalid JSON (missing quotes, extra commas, etc.)
    invalidJson = """{name:"John",age:30,active:true,}"""
  }

  @Benchmark
  def parseValidNormal(): Unit = {
    JsonParser.jsonValue.run(validJson)
  }

  @Benchmark
  def parseValidResilient(): Unit = {
    // Using error recovery combinators
    JsonParser.jsonValue.recoverWith(_ => succeed(parsers.json.JsonValue.Null)).run(validJson)
  }

  @Benchmark
  def parseInvalidNormal(): Unit = {
    // Will fail fast
    JsonParser.jsonValue.run(invalidJson)
  }

  @Benchmark
  def parseInvalidResilient(): Unit = {
    // Will collect errors and continue
    JsonParser.jsonValue.recoverWith(_ => succeed(parsers.json.JsonValue.Null)).run(invalidJson)
  }
}
```

#### 2.5 GreenNode Overhead Benchmark

Create `benchmarks/src/main/scala/parser/benchmarks/GreenNodeBenchmark.scala`:

```scala
package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import parser.core._

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
class GreenNodeBenchmark {

  var smallTree: GreenNode = _
  var largeTree: GreenNode = _

  @Setup
  def setup(): Unit = {
    import parser.core.{TokenKind, SyntaxKind}

    // Small tree
    smallTree = GreenNode.Tree(
      SyntaxKind.Expression,
      Vector(
        GreenNode.Token(TokenKind.Identifier, "x", (start = (line = 1, column = 1, offset = 0), end = (line = 1, column = 2, offset = 1))),
        GreenNode.Token(TokenKind.Operator, "+", (start = (line = 1, column = 3, offset = 2), end = (line = 1, column = 4, offset = 3))),
        GreenNode.Token(TokenKind.Number, "42", (start = (line = 1, column = 5, offset = 4), end = (line = 1, column = 7, offset = 6)))
      )
    )

    // Large tree (100 nodes)
    val nodes = (1 to 100).map { i =>
      GreenNode.Token(TokenKind.Identifier, s"var$i", (start = (line = 1, column = i, offset = i - 1), end = (line = 1, column = i + 1, offset = i)))
    }.toVector
    largeTree = GreenNode.Tree(SyntaxKind.Statement, nodes)
  }

  @Benchmark
  def spanSmall(): Unit = {
    smallTree.span
  }

  @Benchmark
  def spanLarge(): Unit = {
    largeTree.span
  }

  @Benchmark
  def reconstructSmall(): Unit = {
    smallTree.text
  }

  @Benchmark
  def reconstructLarge(): Unit = {
    largeTree.text
  }

  @Benchmark
  def traverseSmall(): Unit = {
    var count = 0
    smallTree.traverse { node =>
      count += 1
    }
  }

  @Benchmark
  def traverseLarge(): Unit = {
    var count = 0
    largeTree.traverse { node =>
      count += 1
    }
  }
}
```

### Phase 3: Add Test Resources

#### 3.1 Create Realistic Test Data

Create `benchmarks/src/main/resources/large-response.json`:

```json
{
  "users": [
    {"id": 1, "name": "Alice Johnson", "email": "alice@example.com", "age": 28, "active": true, "roles": ["admin", "user"]},
    {"id": 2, "name": "Bob Smith", "email": "bob@example.com", "age": 35, "active": true, "roles": ["user"]},
    {"id": 3, "name": "Charlie Brown", "email": "charlie@example.com", "age": 42, "active": false, "roles": ["user", "moderator"]}
    // ... repeat 50+ times for realistic size
  ],
  "meta": {
    "page": 1,
    "per_page": 50,
    "total": 1000,
    "total_pages": 20
  },
  "links": {
    "self": "https://api.example.com/users?page=1",
    "next": "https://api.example.com/users?page=2",
    "last": "https://api.example.com/users?page=20"
  }
}
```

### Phase 4: Run Benchmarks and Document Results

#### 4.1 Run Benchmarks

```bash
# Run all benchmarks
sbt "benchmarks/jmh:run -i 10 -wi 5 -f 2 -t 1"

# Run specific benchmark
sbt "benchmarks/jmh:run JsonBenchmark -i 10 -wi 5 -f 2"

# Run with profilers (optional, for deeper analysis)
sbt "benchmarks/jmh:run -prof gc"  # GC profiling
sbt "benchmarks/jmh:run -prof stack"  # Stack profiling
```

#### 4.2 Create Results Document

Create `benchmarks/RESULTS.md`:

```markdown
# Rumil Performance Benchmarks

## Environment

- **JVM:** OpenJDK 21.0.X
- **OS:** Linux/macOS/Windows
- **CPU:** [Your CPU]
- **Date:** [Benchmark date]

## Summary

[Executive summary of results]

## JSON Parsing

| Benchmark | Rumil | Circe | Winner |
|-----------|-------|-------|--------|
| Small (100B) | X ops/s | Y ops/s | [Winner] |
| Medium (1KB) | X ops/s | Y ops/s | [Winner] |
| Large (10KB) | X ops/s | Y ops/s | [Winner] |
| Deeply Nested | X ops/s | Y ops/s | [Winner] |

**Analysis:**
- [Observations about performance]
- [Strengths of Rumil]
- [Areas for improvement]

## Expression Parsing

| Benchmark | Throughput | Std Dev |
|-----------|------------|---------|
| Simple | X μs/op | ±Y% |
| Complex | X μs/op | ±Y% |
| Deeply Nested | X μs/op | ±Y% |

**Analysis:**
- Left recursion overhead: [measurement]
- Combinator efficiency: [observations]

## Combinator Performance

| Combinator | Throughput | Notes |
|------------|------------|-------|
| char | X μs/op | Baseline |
| string | X μs/op | [observations] |
| many | X μs/op | [observations] |
| flatMap | X μs/op | [observations] |

## Error Recovery Overhead

| Scenario | Normal | Resilient | Overhead |
|----------|--------|-----------|----------|
| Valid input | X ms | Y ms | Z% |
| Invalid input | X ms | Y ms | Z% |

**Analysis:**
- Error recovery adds approximately X% overhead
- Trade-off is acceptable for IDE/tooling use cases

## GreenNode Overhead

| Operation | Time | Notes |
|-----------|------|-------|
| span() | X μs | [observations] |
| text() | X μs | [observations] |
| traverse() | X μs | [observations] |

## Bottlenecks Identified

1. [Bottleneck 1]: [description and severity]
2. [Bottleneck 2]: [description and severity]

## Optimization Opportunities

1. **[Opportunity 1]**: Expected improvement: X%
2. **[Opportunity 2]**: Expected improvement: Y%

## Conclusions

[Overall assessment of Rumil's performance]
```

#### 4.3 Update Documentation

Add performance section to `README.md`:

```markdown
## Performance

Rumil is designed for both speed and correctness. See detailed [benchmark results](benchmarks/RESULTS.md).

**Highlights:**
- JSON parsing: ~XXX MB/s
- Expression evaluation: ~XXX μs for complex expressions
- Error recovery overhead: <X%
- Competitive with fastparse, faster than cats-parse for most workloads

**Philosophy:** We optimize for real-world use cases, not microbenchmarks. Rumil prioritizes:
1. Correctness and error recovery
2. Lossless syntax tree construction
3. Developer ergonomics
4. Speed (in that order)
```

Add performance page to `docs/performance.md`:

```markdown
# Performance Guide

## Benchmarking Methodology

Rumil uses JMH (Java Microbenchmark Harness) for all performance testing...

[Detailed explanation of benchmarking approach]

## Results

[Link to RESULTS.md]

## Optimization Tips

### When Speed Matters

If you're parsing in a hot loop:
- Reuse parser instances (they're immutable and thread-safe)
- Use `many` instead of recursive calls when possible
- Avoid unnecessary `.map()` transformations

### When Resilience Matters

For IDE/tooling use cases:
- Use error recovery combinators
- Build GreenNode trees for lossless parsing
- Accept the ~X% overhead for better UX

### Profiling Your Parser

```scala
// Add .trace() to identify bottlenecks
val myParser = (expensive ~ operation).trace("bottleneck")
```

## Compared to Other Libraries

[Fair comparison with fastparse, cats-parse, parsley]
```

### Phase 5: Optional CI Integration

Add benchmark job to `.github/workflows/ci.yml` (optional):

```yaml
  benchmarks:
    name: Performance Benchmarks
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'  # Only on PRs

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Setup sbt
        uses: sbt/setup-sbt@v1

      - name: Run quick benchmarks
        run: sbt "benchmarks/jmh:run -i 3 -wi 2 -f 1"

      - name: Comment PR with results
        # Upload results as PR comment
        uses: actions/github-script@v6
        with:
          script: |
            // Parse and post benchmark results
```

### Phase 6: Commit and Document

1. **Run Benchmarks**
   ```bash
   sbt "benchmarks/jmh:run -i 10 -wi 5 -f 2" | tee benchmark-output.txt
   ```

2. **Analyze Results**
   - Fill in `benchmarks/RESULTS.md` with actual numbers
   - Identify bottlenecks
   - Document optimization opportunities

3. **Run Formatting**
   ```bash
   sbt scalafmtAll scalafmtSbt
   ```

4. **Create Commit**
   ```bash
   git add .
   git commit -m "$(cat <<'EOF'
   Implement comprehensive benchmarks (ROADMAP 2.2)

   Adds JMH-based performance benchmarking suite with comparisons against
   competing libraries (fastparse, cats-parse, circe).

   Benchmark Categories:
   - JSON parsing (small, medium, large, deeply nested)
   - Expression parsing (arithmetic with left recursion)
   - Combinator performance (char, string, many, flatMap, etc.)
   - Error recovery overhead
   - GreenNode tree operations

   Results Summary:
   - [Key finding 1]
   - [Key finding 2]
   - [Key finding 3]

   Benchmarks identify optimization opportunities for Priority 2.3
   (Memoization/Packrat) implementation.

   Infrastructure:
   - Added benchmarks module with JMH plugin
   - Created realistic test data (10KB JSON response)
   - Documented methodology and results
   - Added performance guide to documentation
   EOF
   )"
   ```

## Expected Deliverables

1. ✅ JMH benchmark infrastructure set up
2. ✅ Benchmarks for JSON parsing (vs circe)
3. ✅ Benchmarks for expression parsing (left recursion)
4. ✅ Benchmarks for combinator performance
5. ✅ Benchmarks for error recovery overhead
6. ✅ Benchmarks for GreenNode operations
7. ✅ Realistic test data (10KB+ JSON)
8. ✅ `benchmarks/RESULTS.md` with actual measurements
9. ✅ Performance section in README.md
10. ✅ Performance guide in docs/
11. ✅ Identified bottlenecks and optimization opportunities

## Success Criteria

- ✅ All benchmarks run successfully
- ✅ Results are statistically significant (low variance)
- ✅ Baseline metrics established for all core operations
- ✅ Performance compared against at least 2 competing libraries
- ✅ Bottlenecks identified with profiling data
- ✅ Documentation clearly explains methodology
- ✅ Results inform Priority 2.3 (Memoization) decisions

## Important Notes

1. **JMH Best Practices:**
   - Always use warm-up iterations
   - Use multiple forks to avoid JIT artifacts
   - Report throughput (ops/sec) for most benchmarks
   - Report average time (μs/op) for micro-operations

2. **Fair Comparisons:**
   - Use same JVM version for all libraries
   - Test equivalent operations (don't compare apples to oranges)
   - Document library versions
   - Run on same hardware

3. **Statistical Validity:**
   - Run enough iterations for stable results
   - Report standard deviation
   - Watch for bi-modal distributions (indicates issues)

4. **Realistic Workloads:**
   - Use actual API responses, not toy examples
   - Test edge cases (empty, huge, malformed)
   - Match common use cases

5. **Profiling:**
   - Use `-prof gc` to check allocation rates
   - Use `-prof stack` to find hot methods
   - Consider flame graphs for deep analysis

## After Completion

Report back with:
1. Summary of benchmark results
2. Key performance findings
3. Comparison with competing libraries
4. Identified bottlenecks and optimization opportunities
5. Recommendations for Priority 2.3 (Memoization)
6. Any surprising discoveries

## References

- **JMH Documentation:** https://github.com/openjdk/jmh
- **JMH Samples:** https://github.com/openjdk/jmh/tree/master/jmh-samples
- **Scala Benchmarking Guide:** https://github.com/scala/scala-dev/blob/master/BENCHMARKING.md
