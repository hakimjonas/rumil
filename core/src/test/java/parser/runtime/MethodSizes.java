package parser.runtime;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.HashMap;
import java.util.Map;

/**
 * Max {@code Code}-attribute length per method name in a parsed class (overloads take the max).
 *
 * <p>Isolated in Java on purpose: Scala 3.9's JDK generic-signature reading both rejects the
 * raw-list idiom at {@code ClassModel.methods()} and crashes while completing the JDK 25
 * class-file impl classes (scala/scala3#25451 family). javac reads those signatures natively,
 * and Scala consumers only see a plain {@code Map<String, Integer>}.
 */
public final class MethodSizes {

  private MethodSizes() {}

  public static Map<String, Integer> codeSizes(byte[] bytes) {
    var cm = ClassFile.of().parse(bytes);
    var sizes = new HashMap<String, Integer>();
    for (var mm : cm.methods()) {
      for (var attr : mm.attributes()) {
        if (attr instanceof CodeAttribute ca) {
          sizes.merge(mm.methodName().stringValue(), ca.codeLength(), Math::max);
        }
      }
    }
    return Map.copyOf(sizes);
  }
}
