package parser.benchmarks

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Comprehensive comparison of Rumil vs cats-parse and zio-parser.
  *
  * Methodology:
  *   - All parsers built once in @Setup (excludes construction overhead)
  *   - Equivalent parsers across all libraries where APIs allow
  *   - Validated for correctness before benchmarking
  *   - Fair comparison focusing on runtime performance
  *
  * Benchmarks:
  *   1. Simple string matching
  *   2. Choice with backtracking (10 alternatives)
  *   3. Many repetition (1K elements)
  *   4. Sequential composition (100 operations)
  *   5. Number parsing with transformation
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class LibraryComparison {

  // ============================================================================
  // Test Inputs
  // ============================================================================

  var stringInput: String = scala.compiletime.uninitialized
  var choiceInput: String = scala.compiletime.uninitialized
  var manyInput: String = scala.compiletime.uninitialized
  var seqInput: String = scala.compiletime.uninitialized
  var numberInput: String = scala.compiletime.uninitialized

  // ============================================================================
  // Rumil Parsers
  // ============================================================================

  var rumilString: parser.core.Parser[parser.core.ParseError, String] =
    scala.compiletime.uninitialized
  var rumilChoice: parser.core.Parser[parser.core.ParseError, String] =
    scala.compiletime.uninitialized
  var rumilMany: parser.core.Parser[parser.core.ParseError, List[Char]] =
    scala.compiletime.uninitialized
  var rumilSeq: parser.core.Parser[parser.core.ParseError, Any] = scala.compiletime.uninitialized
  var rumilNumber: parser.core.Parser[parser.core.ParseError, Int] = scala.compiletime.uninitialized

  // ============================================================================
  // cats-parse Parsers
  // ============================================================================

  var catsString: cats.parse.Parser[String] = scala.compiletime.uninitialized
  var catsChoice: cats.parse.Parser[String] = scala.compiletime.uninitialized
  var catsMany: cats.parse.Parser[List[Char]] = scala.compiletime.uninitialized
  var catsSeq: cats.parse.Parser[Any] = scala.compiletime.uninitialized
  var catsNumber: cats.parse.Parser[Int] = scala.compiletime.uninitialized

  // ============================================================================
  // zio-parser Parsers
  // ============================================================================

  var zioString: zio.parser.Syntax[String, Char, Char, String] = scala.compiletime.uninitialized
  var zioChoice: zio.parser.Syntax[String, Char, Char, String] = scala.compiletime.uninitialized
  var zioMany: zio.parser.Syntax[String, Char, Char, zio.Chunk[Unit]] =
    scala.compiletime.uninitialized
  var zioNumber: zio.parser.Syntax[String, Char, Char, Int] = scala.compiletime.uninitialized

  @Setup
  def setup(): Unit = {
    // ============================================================================
    // Setup Inputs
    // ============================================================================

    stringInput = "hello"
    choiceInput = "lemon" // Last choice, exercises all branches
    manyInput = "a" * 1000 // 1K repetitions (manageable for all libs)
    seqInput = "1" * 100 // 100 sequential operations
    numberInput = "42"

    // ============================================================================
    // Build Rumil Parsers
    // ============================================================================

    {
      import parser.core.*
      import parser.syntax.*

      rumilString = string("hello")

      rumilChoice = string("apple") | string("banana") | string("cherry") |
        string("date") | string("elderberry") | string("fig") |
        string("grape") | string("honeydew") | string("kiwi") |
        string("lemon")

      rumilMany = parser.core.many(char('a'))

      var p: Parser[ParseError, Any] = char('1')
      (1 until 100).foreach(_ => p = p ~ char('1'))
      rumilSeq = p

      rumilNumber = parser.core.digit.many1.map(digits => digits.mkString.toInt)
    }

    // ============================================================================
    // Build cats-parse Parsers
    // ============================================================================

    {
      import cats.parse.{Parser as P, Numbers}
      import cats.syntax.all.*

      catsString = P.string("hello").string

      catsChoice = P.string("apple").string | P.string("banana").string | P.string("cherry").string |
        P.string("date").string | P.string("elderberry").string | P.string("fig").string |
        P.string("grape").string | P.string("honeydew").string | P.string("kiwi").string |
        P.string("lemon").string

      catsMany = P.charIn('a').rep.map(_.toList.map(_.toString.head))

      var p: P[Any] = P.charIn('1')
      (1 until 100).foreach(_ => p = (p, P.charIn('1')).tupled)
      catsSeq = p

      catsNumber = Numbers.digits.map(_.toInt)
    }

    // ============================================================================
    // Build zio-parser Parsers
    // ============================================================================

    {
      import zio.parser.*

      zioString = Syntax.string("hello", "hello")

      zioChoice = Syntax.string("apple", "apple") | Syntax.string("banana", "banana") |
        Syntax.string("cherry", "cherry") | Syntax.string("date", "date") |
        Syntax.string("elderberry", "elderberry") | Syntax.string("fig", "fig") |
        Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") |
        Syntax.string("kiwi", "kiwi") | Syntax.string("lemon", "lemon")

      zioMany = Syntax.char('a').repeat

      zioNumber = Syntax.digit.repeat.transform(
        chars => chars.mkString.toInt,
        num => zio.Chunk.fromIterable(num.toString)
      )
    }

    // ============================================================================
    // Validate Correctness
    // ============================================================================

    {
      import parser.runtime.run
      import parser.core.Result

      // String matching
      assert(
        run(rumilString, stringInput).isInstanceOf[Result.Success[?, ?]],
        "Rumil string failed"
      ) // scalafix:ok DisableSyntax.isInstanceOf
      assert(catsString.parse(stringInput).isRight, "cats string failed")
      assert(zioString.parseString(stringInput).isRight, "zio string failed")

      // Choice
      assert(
        run(rumilChoice, choiceInput).isInstanceOf[Result.Success[?, ?]],
        "Rumil choice failed"
      ) // scalafix:ok DisableSyntax.isInstanceOf
      assert(catsChoice.parse(choiceInput).isRight, "cats choice failed")
      assert(zioChoice.parseString(choiceInput).isRight, "zio choice failed")

      // Many
      assert(
        run(rumilMany, manyInput).isInstanceOf[Result.Success[?, ?]],
        "Rumil many failed"
      ) // scalafix:ok DisableSyntax.isInstanceOf
      assert(catsMany.parse(manyInput).isRight, "cats many failed")
      assert(zioMany.parseString(manyInput).isRight, "zio many failed")

      // Sequential
      assert(
        run(rumilSeq, seqInput).isInstanceOf[Result.Success[?, ?]],
        "Rumil seq failed"
      ) // scalafix:ok DisableSyntax.isInstanceOf
      assert(catsSeq.parse(seqInput).isRight, "cats seq failed")

      // Number
      assert(
        run(rumilNumber, numberInput).isInstanceOf[Result.Success[?, ?]],
        "Rumil number failed"
      ) // scalafix:ok DisableSyntax.isInstanceOf
      assert(catsNumber.parse(numberInput).isRight, "cats number failed")
      assert(zioNumber.parseString(numberInput).isRight, "zio number failed")

      println("✓ All libraries validated - correctness confirmed")
    }
  }

  // ============================================================================
  // Benchmark 1: String Matching
  // ============================================================================

  @Benchmark
  def string_rumil(): Any = {
    import parser.runtime.run
    run(rumilString, stringInput)
  }

  @Benchmark
  def string_cats(): Any =
    catsString.parse(stringInput)

  @Benchmark
  def string_zio(): Any =
    zioString.parseString(stringInput)

  // ============================================================================
  // Benchmark 2: Choice with Backtracking
  // ============================================================================

  @Benchmark
  def choice_rumil(): Any = {
    import parser.runtime.run
    run(rumilChoice, choiceInput)
  }

  @Benchmark
  def choice_cats(): Any =
    catsChoice.parse(choiceInput)

  @Benchmark
  def choice_zio(): Any =
    zioChoice.parseString(choiceInput)

  // ============================================================================
  // Benchmark 3: Many Repetition (1K elements)
  // ============================================================================

  @Benchmark
  def many_rumil(): Any = {
    import parser.runtime.run
    run(rumilMany, manyInput)
  }

  @Benchmark
  def many_cats(): Any =
    catsMany.parse(manyInput)

  @Benchmark
  def many_zio(): Any =
    zioMany.parseString(manyInput)

  // ============================================================================
  // Benchmark 4: Sequential Composition (100 operations)
  // ============================================================================

  @Benchmark
  def seq_rumil(): Any = {
    import parser.runtime.run
    run(rumilSeq, seqInput)
  }

  @Benchmark
  def seq_cats(): Any =
    catsSeq.parse(seqInput)

  // ============================================================================
  // Benchmark 5: Number Parsing with Transformation
  // ============================================================================

  @Benchmark
  def number_rumil(): Any = {
    import parser.runtime.run
    run(rumilNumber, numberInput)
  }

  @Benchmark
  def number_cats(): Any =
    catsNumber.parse(numberInput)

  @Benchmark
  def number_zio(): Any =
    zioNumber.parseString(numberInput)
}
