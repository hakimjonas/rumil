package parser

import munit.FunSuite

class TupleSizeTest extends FunSuite {

  test("Scala 3 tuples can exceed 22 elements") {
    // Scala 2 had a hard limit of 22 elements
    // Scala 3 should support arbitrary sizes

    val tuple30 = (
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30
    )

    assertEquals(tuple30.size, 30)
  }

  test("can build large tuples dynamically") {
    var t: Tuple = EmptyTuple
    (1 to 100).foreach { i =>
      t = i *: t
    }
    assertEquals(t.size, 100)
  }

  test("very large tuples - 1000 elements") {
    var t: Tuple = EmptyTuple
    (1 to 1000).foreach { i =>
      t = i *: t
    }
    assertEquals(t.size, 1000)
  }

  test("accessing elements in large tuples") {
    val large = (1 to 50).foldLeft(EmptyTuple: Tuple)((acc, i) => i *: acc)

    // Can access via .head and .tail
    val firstElem = large.head.asInstanceOf[Int] // scalafix:ok DisableSyntax.asInstanceOf
    assertEquals(firstElem, 50) // Last added is first (prepend)

    // Check size
    assertEquals(large.size, 50)
  }
}
