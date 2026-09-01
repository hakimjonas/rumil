package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.core.*
import parser.runtime.run
import parser.syntax.*

/** JMH benchmarks comparing the Pratt combinator against `chainl1` for GreenNode-producing
  * arithmetic expression grammars.
  *
  * Both parsers target identical output (byte-equal `GreenNode.toSource`) across the comparable
  * operator set (`+ - * /`). The Pratt parser additionally handles `^` and prefix `-` at no extra
  * cost, which `chainl1` cannot express without rebuilding the grammar.
  *
  * Run with: sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 -t 1 PrattBenchmarks"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class PrattBenchmarks {

  var exprSmall: String = uninitialized
  var exprMedium: String = uninitialized
  var exprLarge: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    exprSmall = buildExpr(10)
    exprMedium = buildExpr(100)
    exprLarge = buildExpr(1000)
  }

  private def buildExpr(operatorCount: Int): String = {
    val opSymbols = Array('+', '-', '*', '/')
    val sb = new StringBuilder
    sb.append((1 % 10).toString)
    (1 to operatorCount).foreach { i =>
      sb.append(opSymbols(i % opSymbols.length))
      sb.append(((i + 1) % 10).toString)
    }
    sb.result()
  }

  private def numberToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Number, c.toString)

  private def opToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Operator, c.toString)

  private def binOp(left: GreenNode, op: Char, right: GreenNode): GreenNode =
    GreenNode.treeOfVec(SyntaxKind.Expression, Vector(left, opToken(op), right))

  private val atomDigit: Parser[ParseError, GreenNode] =
    digit.map(numberToken)

  val chainlParser: Parser[ParseError, GreenNode] = {
    lazy val expr: Parser[ParseError, GreenNode] =
      defer(term).chainl1(
        char('+').as((a: GreenNode, b: GreenNode) => binOp(a, '+', b)) |
          char('-').as((a: GreenNode, b: GreenNode) => binOp(a, '-', b))
      )
    lazy val term: Parser[ParseError, GreenNode] =
      defer(factor).chainl1(
        char('*').as((a: GreenNode, b: GreenNode) => binOp(a, '*', b)) |
          char('/').as((a: GreenNode, b: GreenNode) => binOp(a, '/', b))
      )
    lazy val factor: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(expr) <* char(')'))
    expr
  }

  val prattParser: Parser[ParseError, GreenNode] = {
    lazy val atom: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(exprP) <* char(')'))
    lazy val exprP: Parser[ParseError, GreenNode] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '+', b)),
          Operator.InfixLeft(char('-'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '-', b)),
          Operator.InfixLeft(char('*'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '*', b)),
          Operator.InfixLeft(char('/'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '/', b))
        )
      )
    exprP
  }

  @Benchmark
  def chainl_small10(bh: Blackhole): Unit = {
    bh.consume(run(chainlParser, exprSmall))
  }

  @Benchmark
  def pratt_small10(bh: Blackhole): Unit = {
    bh.consume(run(prattParser, exprSmall))
  }

  @Benchmark
  def chainl_medium100(bh: Blackhole): Unit = {
    bh.consume(run(chainlParser, exprMedium))
  }

  @Benchmark
  def pratt_medium100(bh: Blackhole): Unit = {
    bh.consume(run(prattParser, exprMedium))
  }

  @Benchmark
  def chainl_large1000(bh: Blackhole): Unit = {
    bh.consume(run(chainlParser, exprLarge))
  }

  @Benchmark
  def pratt_large1000(bh: Blackhole): Unit = {
    bh.consume(run(prattParser, exprLarge))
  }

  var postfixChainSmall: String = uninitialized
  var postfixChainMedium: String = uninitialized

  @Setup(Level.Trial)
  def setupPostfix(): Unit = {
    postfixChainSmall = "5" + "!" * 10
    postfixChainMedium = "5" + "!" * 100
  }

  val prattPostfixParser: Parser[ParseError, GreenNode] =
    pratt(
      atomDigit,
      List(
        Operator.Postfix(
          char('!'),
          50,
          (g: GreenNode) => GreenNode.treeOfVec(SyntaxKind.Expression, Vector(g, opToken('!')))
        )
      )
    )

  @Benchmark
  def pratt_postfix10(bh: Blackhole): Unit = {
    bh.consume(run(prattPostfixParser, postfixChainSmall))
  }

  @Benchmark
  def pratt_postfix100(bh: Blackhole): Unit = {
    bh.consume(run(prattPostfixParser, postfixChainMedium))
  }

  // ==== C-family preset: 15 operators across 7 levels (no-space token stream) ====

  var cExprSmall: String = uninitialized
  var cExprMedium: String = uninitialized
  var cExprLarge: String = uninitialized

  @Setup(Level.Trial)
  def setupCFamily(): Unit = {
    cExprSmall = buildCFamily(10)
    cExprMedium = buildCFamily(100)
    cExprLarge = buildCFamily(500)

    // Guard: full consumption on every size
    List(
      (cExprSmall, "cExprSmall"),
      (cExprMedium, "cExprMedium"),
      (cExprLarge, "cExprLarge")
    ).foreach { (input, name) =>
      run(cFamilyParser, input) match {
        case Result.Success(_, c) =>
          assert(c == input.length, s"$name must be fully consumed")
        case other => throw new IllegalStateException(s"$name must succeed, got $other")
      }
    }
  }

  // Each piece: (!i + i * (i - i)) != 0 — exercises prefix !, + * - (arithmetic), != (equality),
  // && (boolean); joined without spaces so atoms are plain digit runs.
  private def buildCFamily(pieces: Int): String =
    (0 until pieces).map(i => s"(!$i+$i*($i-$i))!=0").mkString("&&")

  val cFamilyParser: Parser[ParseError, Int] = {
    lazy val atom: Parser[ParseError, Int] =
      digit.many1.map(_.mkString.toInt) | (char('(') *> defer(cExpr) <* char(')'))
    lazy val cExpr: Parser[ParseError, Int] =
      pratt(
        defer(atom),
        cFamilyPrecedence(
          s => string(s).void,
          (op, a, b) =>
            op match {
              case "+" => a + b
              case "-" => a - b
              case "*" => a * b
              case "<" => if a < b then 1 else 0
              case ">" => if a > b then 1 else 0
              case "<=" => if a <= b then 1 else 0
              case ">=" => if a >= b then 1 else 0
              case "==" => if a == b then 1 else 0
              case "!=" => if a != b then 1 else 0
              case "&&" => if a != 0 && b != 0 then 1 else 0
              case "||" => if a != 0 || b != 0 then 1 else 0
              case _ => if b == 0 then 0 else a / b // "/" or "%"
            },
          (op, a) =>
            op match {
              case "-" => -a
              case "!" => if a == 0 then 1 else 0
              case _ => a
            }
        )
      )
    cExpr
  }

  @Benchmark
  def pratt_cfamily_small(bh: Blackhole): Unit = {
    bh.consume(run(cFamilyParser, cExprSmall))
  }

  @Benchmark
  def pratt_cfamily_medium(bh: Blackhole): Unit = {
    bh.consume(run(cFamilyParser, cExprMedium))
  }

  @Benchmark
  def pratt_cfamily_large(bh: Blackhole): Unit = {
    bh.consume(run(cFamilyParser, cExprLarge))
  }
}
