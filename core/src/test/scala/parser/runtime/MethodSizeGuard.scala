package parser.runtime

import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.CodeAttribute
import scala.jdk.CollectionConverters.*

/** JIT method-size invariant guard.
  *
  * Increment 1's `Parser → ParserK[+E, Elem, +A]` reshape silently tipped `interpretI` from just
  * under 8000 bytes to 8030 — over HotSpot's `-XX:DontCompileHugeMethods` ceiling (8000 B). C2 then
  * refuses to JIT the method at all, it runs interpreted, and the whole string path regresses ~8×
  * with no compile error and no test failure. The fix (commit: split `interpretI`'s fat arms into
  * named helpers) brought it back under the ceiling.
  *
  * This test converts that silent cliff into a loud failure at the commit that would cross it. It
  * reads the compiled bytecode of the interpreter/trampoline hot methods via the JDK 25 Classfile
  * API (the reliable measurement — NOT awk-over-`javap`, which glitches on overloads) and fails if
  * any exceeds [[Threshold]], a safe margin below 8000 so there is headroom before the real cliff.
  *
  * If a future change legitimately needs a method to grow past the threshold, the fix is to extract
  * a helper (the established `interpretOrI` / `interpretSatisfyI` shape), NOT to raise the
  * threshold toward 8000.
  */
class MethodSizeGuard extends munit.FunSuite {

  /** Safe ceiling, well below HotSpot's 8000 B `DontCompileHugeMethods` limit. */
  private val Threshold = 6000

  /** (binary class name, method names to guard). `interpretI` lives in the file-level
    * `Interpreter$package$`; the trampoline driver + step methods live in `TrampolineOpt$`.
    */
  private val Guarded: List[(String, Set[String])] = List(
    "parser.runtime.Interpreter$package$" -> Set("interpretI"),
    "parser.runtime.TrampolineOpt$" ->
      Set("loop", "stepEval", "stepApply", "stepApplySuccess", "stepComposeK")
  )

  /** Max `Code`-attribute length per method name in a class (overloads → take the max). */
  private def codeSizes(binaryName: String): Map[String, Int] = {
    val resource = binaryName.replace('.', '/') + ".class"
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(fail(s"could not locate compiled class resource: $resource"))
    val bytes =
      try stream.readAllBytes()
      finally stream.close()
    val cm = ClassFile.of().parse(bytes)
    cm.methods()
      .asScala
      .toList
      .flatMap { mm =>
        mm.attributes().asScala.collectFirst { case ca: CodeAttribute =>
          mm.methodName().stringValue() -> ca.codeLength()
        }
      }
      .groupMapReduce(_._1)(_._2)(math.max)
  }

  for ((binaryName, methods) <- Guarded; method <- methods) {
    test(s"$method bytecode stays under $Threshold B (JIT huge-method ceiling guard)") {
      val sizes = codeSizes(binaryName)
      val size = sizes.getOrElse(method, fail(s"method $method not found in $binaryName"))
      assert(
        size <= Threshold,
        s"$binaryName.$method is $size B, over the $Threshold B guard (HotSpot stops JIT-compiling " +
          "at 8000 B → silent ~8× regression). Extract a helper to shrink it; do not raise the threshold."
      )
    }
  }
}
