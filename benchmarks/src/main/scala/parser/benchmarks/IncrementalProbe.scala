package parser.benchmarks

import parser.core.*
import parser.runtime.run

/** Standalone probe run from `sbt benchmarks/runMain` to classify which incremental path fires for
  * each scenario in [[IncrementalBenchmarks]]. Prints the outcome of each edit:
  *   - `fullReparse = true` means the harness fell back (no incremental win possible)
  *   - `fullReparse = false, reparseRegion = Some(...)` means the incremental path fired; the span
  *     of the reparsed region tells us which level (token / statement / block / file)
  *
  * Does not measure time — use [[IncrementalBenchmarks]] for that. This is diagnostic only, run
  * once to understand what the bench is actually measuring.
  */
object IncrementalProbe {

  def main(args: Array[String]): Unit = {
    val statementCount = 200
    val opsPerStatement = 5
    val (source, tree) = IncrementalGrammar.synthesize(statementCount, opsPerStatement)
    val lineWidth = 1 + opsPerStatement * 2 + 1
    val midStmtStart = (statementCount / 2) * lineWidth
    val midStmtEnd = midStmtStart + (lineWidth - 1)

    val parsers = IncrementalGrammar.parsers
    def probe(label: String, edit: TextEdit): Unit = {
      val newSource = edit(source)
      val findResult = RedTree(tree).findReparseRegion(
        edit.startOffset,
        edit.endOffset,
        parsers.reparsableKinds
      )
      val findStr = findResult match {
        case Some(n) =>
          val regionStart = n.span.start.offset
          val regionEnd = n.span.end.offset
          val adjustedEnd = if edit.endOffset <= regionEnd then regionEnd + edit.lengthDelta else regionEnd
          val guardLhs = adjustedEnd - regionStart
          val guardRhs = newSource.length - IncrementalParser.defaultConfig.minReparseSize
          s"${n.kind} span=[$regionStart..$regionEnd) adjustedEnd=$adjustedEnd guard=${guardLhs}>=${guardRhs}=${guardLhs >= guardRhs}"
        case None => "none"
      }
      val result = IncrementalParser.incrementalParse(tree, source, edit, parsers)
      val region = result.reparseRegion match {
        case Some(span) =>
          val bytes = span.end.offset - span.start.offset
          s"${bytes}B @ [${span.start.offset}..${span.end.offset})"
        case None => "none"
      }
      val srcMatches = GreenNode.toSource(result.tree) == newSource
      println(
        f"  $label%-18s  find=$findStr%-80s  fullReparse=${result.fullReparse}%-5s  region=$region%-30s  ok=$srcMatches"
      )
    }

    println(
      s"Source: ${source.length} bytes, $statementCount statements, $opsPerStatement ops/stmt, lineWidth=$lineWidth"
    )
    println()
    probe("tokenLocalEdit", TextEdit.replace(midStmtStart + 2, midStmtStart + 3, "9"))
    probe("stmtLocalEdit", TextEdit.replace(midStmtStart + 1, midStmtStart + 2, "-"))
    probe("stmtInsert", TextEdit.insert(source.length, "1+2*3\n"))
    probe("stmtDelete", TextEdit.delete(midStmtStart, midStmtEnd + 1))
    probe("crossStmtEdit", TextEdit.replace(midStmtEnd, midStmtEnd + 1, "+"))
    probe(
      "largeEdit", {
        val start = source.length / 4
        val end = (source.length * 3) / 4
        TextEdit.replace(start, end, IncrementalGrammar.synthesize(statementCount / 2, opsPerStatement)._1)
      }
    )

    println()
    println("Panic-mode recovery probe (syncUntil at statement boundary):")
    // Corrupted source: statement 1 is malformed ("5+garbage_junk+3"), statement 2 is valid.
    // syncUntil at the '\n' boundary should wrap `garbage_junk` in GreenNode.Unexpected and keep
    // parsing so statement 2 still produces a clean SyntaxKind.Statement subtree.
    val corrupted = "5+garbage_junk+3\n6*7\n"
    val parseResult = run(IncrementalGrammar.resilientSourceFile, corrupted)
    val (label, recoveredTree, errs) = parseResult match {
      case Result.Success(t, _) => ("Success", Some(t), 0)
      case Result.Partial(t, e, _) => ("Partial", Some(t), e.length)
      case Result.Failure(e, _) => ("Failure", None, e.length)
    }
    val reconstructed = recoveredTree.map(GreenNode.toSource).getOrElse("")
    val lossless = reconstructed == corrupted
    val unexpectedCount = recoveredTree.map { t =>
      var n = 0
      GreenNode.traverse(t) {
        case GreenNode.Unexpected(_, _) => n += 1
        case _ => ()
      }
      n
    }.getOrElse(0)
    val statementCountInTree = recoveredTree.map { t =>
      var n = 0
      GreenNode.traverse(t) {
        case GreenNode.Tree(SyntaxKind.Statement, _, _) => n += 1
        case _ => ()
      }
      n
    }.getOrElse(0)
    println(
      f"  recoveryScenario    result=$label%-7s errors=$errs%-3d unexpectedNodes=$unexpectedCount%-2d statementsInTree=$statementCountInTree%-2d lossless=$lossless"
    )
    println(s"  corrupted input: ${corrupted.replace("\n", "\\n")}")
  }
}
