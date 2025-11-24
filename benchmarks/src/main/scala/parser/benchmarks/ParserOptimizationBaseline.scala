package parser.benchmarks

import parsers.xml.parseXml
import parsers.json.parseJson
import parsers.toml.parseToml
import scala.collection.mutable.ArrayBuffer

/**
 * Baseline benchmarks for parser implementations BEFORE adding .memoize optimizations.
 *
 * This establishes performance baselines so we can measure the impact of adding
 * .memoize to hot paths like xmlName, whitespace parsers, and number/string literals.
 *
 * Scenarios tested:
 * 1. XML: Document with many elements/attributes (tests xmlName calls)
 * 2. JSON: Array of objects (tests number/string parsing)
 * 3. TOML: Config file (tests whitespace and value parsing)
 */
object ParserOptimizationBaseline {

  case class BenchmarkResult(
    name: String,
    measurements: Vector[Long],
    unit: String = "ms"
  ) {
    def mean: Double = measurements.sum.toDouble / measurements.size
    def median: Double = {
      val sorted = measurements.sorted
      val n = sorted.size
      if (n % 2 == 0) (sorted(n/2 - 1) + sorted(n/2)) / 2.0
      else sorted(n/2).toDouble
    }
    def stdDev: Double = {
      val m = mean
      val variance = measurements.map(x => math.pow(x - m, 2)).sum / measurements.size
      math.sqrt(variance)
    }
    def min: Long = measurements.min
    def max: Long = measurements.max

    def summary: String = {
      f"$name%-50s: mean=${mean}%7.2f$unit ± ${stdDev}%5.2f$unit " +
      f"[median=${median}%6.2f$unit, min=$min%5d$unit, max=$max%5d$unit]"
    }
  }

  def runBenchmark(
    name: String,
    warmupIterations: Int,
    measureIterations: Int,
    innerLoopCount: Int,
    fn: () => Unit
  ): BenchmarkResult = {
    println(s"  $name")
    print(s"    Warmup ($warmupIterations iterations)...")

    // Warmup
    for (i <- 0 until warmupIterations) {
      fn()
      if (i % 1000 == 0 && i > 0) {
        print(".")
        System.out.flush()
      }
    }
    println(" done")

    // Force GC
    System.gc()
    Thread.sleep(50)

    print(s"    Measuring ($measureIterations runs x $innerLoopCount iterations)...")
    val measurements = ArrayBuffer[Long]()

    for (run <- 0 until measureIterations) {
      val start = System.nanoTime()
      // Run the operation innerLoopCount times to get measurable numbers
      for (_ <- 0 until innerLoopCount) {
        fn()
      }
      val elapsed = (System.nanoTime() - start) / 1_000_000
      measurements += elapsed

      if (run % 20 == 0 && run > 0) {
        print(".")
        System.out.flush()
      }
    }
    println(" done")

    BenchmarkResult(name, measurements.toVector)
  }

  // ============================================================================
  // XML Parser Benchmarks
  // ============================================================================

  def xmlParserBaseline(): Unit = {
    println("\n" + "="*80)
    println("XML PARSER BASELINE")
    println("Testing: xmlName calls (elements + attributes)")
    println("="*80)

    // Small document: 10 elements, 20 attributes
    val smallXml = """<?xml version="1.0"?>
<root>
  <person id="1" name="Alice" age="30" city="NYC" country="USA">
    <hobby type="sports" name="Tennis"/>
    <hobby type="music" name="Piano"/>
  </person>
  <person id="2" name="Bob" age="25" city="LA" country="USA">
    <hobby type="sports" name="Soccer"/>
    <hobby type="reading" name="SciFi"/>
  </person>
</root>"""

    // Medium document: ~50 elements, ~100 attributes
    val mediumXml = """<?xml version="1.0"?>
<catalog>
  <book id="bk101" category="programming" language="en">
    <author name="Gambardella, Matthew"/>
    <title type="main">XML Developer's Guide</title>
    <genre>Computer</genre>
    <price currency="USD">44.95</price>
    <publish_date format="iso">2000-10-01</publish_date>
    <description lang="en">An in-depth look at creating applications with XML.</description>
  </book>
  <book id="bk102" category="fantasy" language="en">
    <author name="Ralls, Kim"/>
    <title type="main">Midnight Rain</title>
    <genre>Fantasy</genre>
    <price currency="USD">5.95</price>
    <publish_date format="iso">2000-12-16</publish_date>
    <description lang="en">A former architect battles corporate zombies.</description>
  </book>
  <book id="bk103" category="fantasy" language="en">
    <author name="Corets, Eva"/>
    <title type="main">Maeve Ascendant</title>
    <genre>Fantasy</genre>
    <price currency="USD">5.95</price>
    <publish_date format="iso">2000-11-17</publish_date>
    <description lang="en">After the collapse of a nanotechnology society.</description>
  </book>
  <book id="bk104" category="romance" language="en">
    <author name="Corets, Eva"/>
    <title type="main">Oberon's Legacy</title>
    <genre>Fantasy</genre>
    <price currency="USD">5.95</price>
    <publish_date format="iso">2001-03-10</publish_date>
    <description lang="en">In post-apocalypse England, the mysterious agent.</description>
  </book>
</catalog>"""

    // Large document: Many repeated element names (high cache hit potential)
    val largeXml = "<root>" +
      (1 to 100).map { i =>
        s"""<item id="$i" name="Item$i" category="cat${i % 10}" status="active">
  <property key="weight" value="$i" unit="kg"/>
  <property key="price" value="${i * 10}" unit="USD"/>
  <property key="stock" value="${i * 5}" unit="items"/>
</item>"""
      }.mkString("\n") +
      "</root>"

    val smallResult = runBenchmark(
      "XML Small (10 elements, 20 attributes)",
      warmupIterations = 5000,
      measureIterations = 100,
      innerLoopCount = 1000,
      () => { parseXml(smallXml); () }
    )

    val mediumResult = runBenchmark(
      "XML Medium (~50 elements, ~100 attributes)",
      warmupIterations = 2000,
      measureIterations = 100,
      innerLoopCount = 500,
      () => { parseXml(mediumXml); () }
    )

    val largeResult = runBenchmark(
      "XML Large (100 items, high name reuse)",
      warmupIterations = 1000,
      measureIterations = 100,
      innerLoopCount = 100,
      () => { parseXml(largeXml); () }
    )

    println("\n" + "-"*80)
    println("BASELINE RESULTS:")
    println(smallResult.summary)
    println(mediumResult.summary)
    println(largeResult.summary)
    println()
    println("Key metrics for xmlName optimization:")
    println(f"  Small XML: ${smallResult.mean}%.2fms (${1000.0 / smallResult.mean}%.0f parses/sec)")
    println(f"  Medium XML: ${mediumResult.mean}%.2fms (${1000.0 / mediumResult.mean}%.0f parses/sec)")
    println(f"  Large XML: ${largeResult.mean}%.2fms (${1000.0 / largeResult.mean}%.0f parses/sec)")
    println("-"*80)
  }

  // ============================================================================
  // JSON Parser Benchmarks
  // ============================================================================

  def jsonParserBaseline(): Unit = {
    println("\n" + "="*80)
    println("JSON PARSER BASELINE")
    println("Testing: Number/string parsing, whitespace handling")
    println("="*80)

    // Array of numbers (tests number parsing)
    val numbersJson = "[" + (1 to 100).mkString(", ") + "]"

    // Array of objects (tests string + number parsing)
    val objectsJson = "[" + (1 to 50).map { i =>
      s"""{"id": $i, "name": "Item$i", "price": ${i * 10.5}, "active": true}"""
    }.mkString(", ") + "]"

    // Nested structure (tests recursive parsing)
    val nestedJson = """
{
  "users": [
    {"id": 1, "name": "Alice", "age": 30, "email": "alice@example.com"},
    {"id": 2, "name": "Bob", "age": 25, "email": "bob@example.com"},
    {"id": 3, "name": "Charlie", "age": 35, "email": "charlie@example.com"}
  ],
  "products": [
    {"id": 101, "name": "Widget", "price": 19.99, "stock": 100},
    {"id": 102, "name": "Gadget", "price": 29.99, "stock": 50},
    {"id": 103, "name": "Doohickey", "price": 9.99, "stock": 200}
  ],
  "metadata": {
    "version": "1.0",
    "timestamp": "2024-01-01T00:00:00Z",
    "count": 6
  }
}"""

    val numbersResult = runBenchmark(
      "JSON Array of 100 numbers",
      warmupIterations = 5000,
      measureIterations = 100,
      innerLoopCount = 1000,
      () => { parseJson(numbersJson); () }
    )

    val objectsResult = runBenchmark(
      "JSON Array of 50 objects",
      warmupIterations = 3000,
      measureIterations = 100,
      innerLoopCount = 500,
      () => { parseJson(objectsJson); () }
    )

    val nestedResult = runBenchmark(
      "JSON Nested structure",
      warmupIterations = 3000,
      measureIterations = 100,
      innerLoopCount = 500,
      () => { parseJson(nestedJson); () }
    )

    println("\n" + "-"*80)
    println("BASELINE RESULTS:")
    println(numbersResult.summary)
    println(objectsResult.summary)
    println(nestedResult.summary)
    println()
    println("Key metrics for number/string optimization:")
    println(f"  Numbers: ${numbersResult.mean}%.2fms (${1000.0 / numbersResult.mean}%.0f parses/sec)")
    println(f"  Objects: ${objectsResult.mean}%.2fms (${1000.0 / objectsResult.mean}%.0f parses/sec)")
    println(f"  Nested: ${nestedResult.mean}%.2fms (${1000.0 / nestedResult.mean}%.0f parses/sec)")
    println("-"*80)
  }

  // ============================================================================
  // TOML Parser Benchmarks
  // ============================================================================

  def tomlParserBaseline(): Unit = {
    println("\n" + "="*80)
    println("TOML PARSER BASELINE")
    println("Testing: Whitespace handling, string/number parsing")
    println("="*80)

    // Simple config (tests basic parsing)
    val simpleToml = """
title = "TOML Example"
count = 42
enabled = true
price = 19.99
"""

    // Config with arrays (tests repeated value parsing)
    val arrayToml = """
[server]
host = "localhost"
ports = [8080, 8081, 8082, 8083, 8084]
names = ["alpha", "beta", "gamma", "delta", "epsilon"]

[database]
connection = "postgresql://localhost:5432"
max_connections = 100
timeout = 30
"""

    // Large config (tests whitespace + repeated patterns)
    val largeToml = "[settings]\n" +
      (1 to 50).map { i =>
        s"""setting_$i = "$i"
value_$i = $i
enabled_$i = true
weight_$i = ${i * 1.5}
"""
      }.mkString("\n")

    val simpleResult = runBenchmark(
      "TOML Simple config",
      warmupIterations = 5000,
      measureIterations = 100,
      innerLoopCount = 1000,
      () => { parseToml(simpleToml); () }
    )

    val arrayResult = runBenchmark(
      "TOML With arrays",
      warmupIterations = 3000,
      measureIterations = 100,
      innerLoopCount = 500,
      () => { parseToml(arrayToml); () }
    )

    val largeResult = runBenchmark(
      "TOML Large config (50 entries)",
      warmupIterations = 2000,
      measureIterations = 100,
      innerLoopCount = 500,
      () => { parseToml(largeToml); () }
    )

    println("\n" + "-"*80)
    println("BASELINE RESULTS:")
    println(simpleResult.summary)
    println(arrayResult.summary)
    println(largeResult.summary)
    println()
    println("Key metrics for whitespace/value optimization:")
    println(f"  Simple: ${simpleResult.mean}%.2fms (${1000.0 / simpleResult.mean}%.0f parses/sec)")
    println(f"  Arrays: ${arrayResult.mean}%.2fms (${1000.0 / arrayResult.mean}%.0f parses/sec)")
    println(f"  Large: ${largeResult.mean}%.2fms (${1000.0 / largeResult.mean}%.0f parses/sec)")
    println("-"*80)
  }

  // ============================================================================
  // Main Entry Point
  // ============================================================================

  def main(args: Array[String]): Unit = {
    println("\n" + "="*80)
    println("  PARSER OPTIMIZATION BASELINE BENCHMARKS")
    println("  Measuring BEFORE adding .memoize optimizations")
    println("="*80)
    println()
    println("This establishes baseline performance for:")
    println("  1. XML: xmlName parsing (elements + attributes)")
    println("  2. JSON: Number/string literal parsing")
    println("  3. TOML: Whitespace + value parsing")
    println()
    println("After these baselines, we'll add .memoize to hot paths and re-measure.")
    println()

    xmlParserBaseline()
    jsonParserBaseline()
    tomlParserBaseline()

    println("\n" + "="*80)
    println("BASELINE BENCHMARKS COMPLETE")
    println("="*80)
    println()
    println("Next steps:")
    println("  1. Add .memoize to xmlName in XML parser")
    println("  2. Add .memoize to number/string parsers in JSON")
    println("  3. Add .memoize to whitespace parsers in TOML")
    println("  4. Re-run benchmarks to measure improvement")
    println()
    println("Expected improvements:")
    println("  - XML: 20-40% faster (xmlName is called for every element/attribute)")
    println("  - JSON: 10-20% faster (number/string parsing with backtracking)")
    println("  - TOML: 15-30% faster (whitespace parser called frequently)")
    println("="*80)
  }
}
