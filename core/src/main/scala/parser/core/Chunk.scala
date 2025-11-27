package parser.core

import scala.collection.AbstractIterator

/** A chunk-based collection optimized for parser result accumulation.
  *
  * Chunk provides O(1) append during building and cache-friendly array-backed storage after
  * finalization. Unlike List which requires O(n) reverse, Chunk builds in natural order.
  *
  * Design philosophy:
  *   - Simple: ~100 LOC, easy to understand
  *   - Fast: Contiguous array storage, no reverse needed
  *   - Pragmatic: One safe cast, minimal API surface
  */
final class Chunk[+A] private[parser] (
    private[parser] val array: Array[Any],
    val length: Int
) {

  /** Access element at index i. O(1) operation.
    *
    * @throws IndexOutOfBoundsException
    *   if i < 0 or i >= length
    */
  def apply(i: Int): A = {
    if (i < 0 || i >= length) {
      throw new IndexOutOfBoundsException(s"Index $i out of bounds for length $length")
    }
    array(i).asInstanceOf[A]
  }

  /** Convert to List. Allocates new list structure. */
  def toList: List[A] = {
    var result: List[A] = Nil
    var i = length - 1
    while (i >= 0) {
      result = array(i).asInstanceOf[A] :: result
      i -= 1
    }
    result
  }

  /** Convert to Array. Allocates new right-sized array. */
  def toArray[A1 >: A: scala.reflect.ClassTag]: Array[A1] = {
    val result = new Array[A1](length)
    var i = 0
    while (i < length) {
      result(i) = array(i).asInstanceOf[A1]
      i += 1
    }
    result
  }

  /** Efficient iterator over chunk elements. */
  def iterator: Iterator[A] = new AbstractIterator[A] {
    private var i = 0
    def hasNext: Boolean = i < Chunk.this.length
    def next(): A = {
      if (!hasNext) throw new NoSuchElementException("next on empty iterator")
      val result = array(i).asInstanceOf[A]
      i += 1
      result
    }
  }

  /** Map over chunk elements, producing new chunk. */
  def map[B](f: A => B): Chunk[B] = {
    val builder = new ChunkBuilder[B](length)
    var i = 0
    while (i < length) {
      builder += f(array(i).asInstanceOf[A])
      i += 1
    }
    builder.result()
  }

  /** Filter chunk elements, producing new chunk. */
  def filter(pred: A => Boolean): Chunk[A] = {
    val builder = new ChunkBuilder[A](length)
    var i = 0
    while (i < length) {
      val elem = array(i).asInstanceOf[A]
      if (pred(elem)) {
        builder += elem
      }
      i += 1
    }
    builder.result()
  }

  /** Check if chunk is empty. */
  def isEmpty: Boolean = length == 0

  /** Check if chunk is non-empty. */
  def nonEmpty: Boolean = length > 0

  /** Get first element if exists. */
  def headOption: Option[A] =
    if (length > 0) Some(array(0).asInstanceOf[A])
    else None

  override def toString: String = iterator.mkString("Chunk(", ", ", ")")

  override def equals(other: Any): Boolean = other match {
    case that: Chunk[?] =>
      this.length == that.length && {
        var i = 0
        var equal = true
        while (i < length && equal) {
          val thisElem = this.array(i)
          val thatElem = that.array(i)
          equal = java.util.Objects.equals(thisElem, thatElem)
          i += 1
        }
        equal
      }
    case _ => false
  }

  override def hashCode: Int = {
    var hash = 1
    var i = 0
    while (i < length) {
      val elem = array(i)
      hash = 31 * hash + java.util.Objects.hashCode(elem)
      i += 1
    }
    hash
  }
}

object Chunk {

  /** Empty chunk. */
  def empty[A]: Chunk[A] = new Chunk[A](Array.empty, 0)

  /** Create chunk from single element. */
  def single[A](elem: A): Chunk[A] = {
    val arr = new Array[Any](1)
    arr(0) = elem
    new Chunk[A](arr, 1)
  }

  /** Create chunk from varargs elements. */
  def apply[A](elems: A*): Chunk[A] = {
    val arr = new Array[Any](elems.length)
    var i = 0
    elems.foreach { elem =>
      arr(i) = elem
      i += 1
    }
    new Chunk[A](arr, elems.length)
  }

  /** Create chunk from existing array (copies). */
  def fromArray[A](array: Array[A]): Chunk[A] = {
    val arr = new Array[Any](array.length)
    System.arraycopy(array, 0, arr, 0, array.length)
    new Chunk[A](arr, array.length)
  }

  /** Create chunk from iterable (iterates once). */
  def fromIterable[A](iter: Iterable[A]): Chunk[A] = {
    val builder = new ChunkBuilder[A](16)
    iter.foreach(builder += _)
    builder.result()
  }
}

/** Mutable builder for efficient chunk construction.
  *
  * ChunkBuilder provides O(1) amortized append with automatic array resizing. Unlike List building
  * which requires O(n) reverse, ChunkBuilder constructs in natural order.
  *
  * Usage:
  * {{{
  * val builder = new ChunkBuilder[Int](sizeHint = 100)
  * while (condition) {
  *   builder += element
  * }
  * val chunk = builder.result()  // Zero-copy finalization
  * }}}
  *
  * @param sizeHint
  *   initial capacity hint (default 16)
  */
final class ChunkBuilder[A](sizeHint: Int = 16) {
  private var array: Array[Any] = new Array[Any](math.max(sizeHint, 1))
  private var size: Int = 0

  /** Append element to builder. O(1) amortized. */
  def +=(elem: A): this.type = {
    ensureCapacity(size + 1)
    array(size) = elem
    size += 1
    this
  }

  /** Append all elements from iterable. */
  def ++=(elems: Iterable[A]): this.type = {
    elems.foreach(this += _)
    this
  }

  /** Current number of elements. */
  def length: Int = size

  /** Finalize to immutable chunk.
    *
    * Zero-copy: Wraps existing array without copying. Builder should not be used after calling
    * result().
    */
  def result(): Chunk[A] = {
    new Chunk[A](array, size)
  }

  /** Clear all elements (reuse array). */
  def clear(): Unit = {
    size = 0
  }

  private def ensureCapacity(required: Int): Unit = {
    if (required > array.length) {
      val newSize = math.max(required, array.length * 2)
      val newArray = new Array[Any](newSize)
      System.arraycopy(array, 0, newArray, 0, size)
      array = newArray
    }
  }
}
