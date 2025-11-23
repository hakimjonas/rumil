package parser.interop

import parser.core._
import parsers.xml.{XmlNode, given_CanEqual_XmlNode_XmlNode}

/**
 * Decoder instances for XML nodes.
 *
 * XML decoding is different from JSON/YAML/TOML because XML is inherently
 * tree-structured rather than value-based. These decoders work with XmlNode
 * and extract values from element text content and attributes.
 *
 * Decoding strategies:
 * - Primitives are extracted from Element text content or Text nodes
 * - Attributes can be decoded separately via XmlAttribute decoders
 * - Case class derivation maps child elements to fields
 *
 * Example:
 * {{{
 * import parser.interop.XmlDecoders.given
 *
 * val textNode = XmlNode.Text("hello")
 * val result = Decoder[XmlNode, String].decode(textNode)
 * // Success("hello", 0)
 * }}}
 */
object XmlDecoders {

  private val defaultLoc: Location = (line = 1, column = 1, offset = 0)

  // ============================================================================
  // Primitive Type Decoders
  // ============================================================================

  /**
   * Decoder for String from XmlNode.
   *
   * - Text nodes return their content directly
   * - Elements return concatenated text content of all child text nodes
   * - CData sections return their content
   */
  given Decoder[XmlNode, String] = new Decoder[XmlNode, String] {
    def decode(value: XmlNode): Result[DecodeError, String] = value match {
      case XmlNode.Text(content)  => Result.Success(content, 0)
      case XmlNode.CData(content) => Result.Success(content, 0)
      case XmlNode.Element(_, _, children) =>
        val textContent = children.collect {
          case XmlNode.Text(t)  => t
          case XmlNode.CData(c) => c
        }.mkString
        Result.Success(textContent, 0)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("String", xmlNodeTypeName(other), defaultLoc)),
          defaultLoc
        )
    }
  }

  /**
   * Decoder for Int from XmlNode.
   *
   * Parses text content as integer.
   */
  given Decoder[XmlNode, Int] = new Decoder[XmlNode, Int] {
    def decode(value: XmlNode): Result[DecodeError, Int] =
      getTextContent(value) match {
        case Result.Success(text, _) =>
          text.trim.toIntOption match {
            case Some(n) => Result.Success(n, 0)
            case None =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch("Int", s"'$text' is not a valid integer", defaultLoc)),
                defaultLoc
              )
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        case Result.Partial(_, errors, _)     => Result.Failure(errors, defaultLoc)
      }
  }

  /**
   * Decoder for Long from XmlNode.
   */
  given Decoder[XmlNode, Long] = new Decoder[XmlNode, Long] {
    def decode(value: XmlNode): Result[DecodeError, Long] =
      getTextContent(value) match {
        case Result.Success(text, _) =>
          text.trim.toLongOption match {
            case Some(n) => Result.Success(n, 0)
            case None =>
              Result.Failure(
                List(DecodeError.TypeMismatch("Long", s"'$text' is not a valid long", defaultLoc)),
                defaultLoc
              )
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        case Result.Partial(_, errors, _)     => Result.Failure(errors, defaultLoc)
      }
  }

  /**
   * Decoder for Double from XmlNode.
   */
  given Decoder[XmlNode, Double] = new Decoder[XmlNode, Double] {
    def decode(value: XmlNode): Result[DecodeError, Double] =
      getTextContent(value) match {
        case Result.Success(text, _) =>
          text.trim.toDoubleOption match {
            case Some(n) => Result.Success(n, 0)
            case None =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch("Double", s"'$text' is not a valid double", defaultLoc)),
                defaultLoc
              )
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        case Result.Partial(_, errors, _)     => Result.Failure(errors, defaultLoc)
      }
  }

  /**
   * Decoder for Float from XmlNode.
   */
  given Decoder[XmlNode, Float] = new Decoder[XmlNode, Float] {
    def decode(value: XmlNode): Result[DecodeError, Float] =
      getTextContent(value) match {
        case Result.Success(text, _) =>
          text.trim.toFloatOption match {
            case Some(n) => Result.Success(n, 0)
            case None =>
              Result.Failure(
                List(
                  DecodeError.TypeMismatch("Float", s"'$text' is not a valid float", defaultLoc)),
                defaultLoc
              )
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        case Result.Partial(_, errors, _)     => Result.Failure(errors, defaultLoc)
      }
  }

  /**
   * Decoder for Boolean from XmlNode.
   *
   * Accepts: true, false, 1, 0, yes, no (case-insensitive)
   */
  given Decoder[XmlNode, Boolean] = new Decoder[XmlNode, Boolean] {
    def decode(value: XmlNode): Result[DecodeError, Boolean] =
      getTextContent(value) match {
        case Result.Success(text, _) =>
          text.trim.toLowerCase match {
            case "true" | "1" | "yes" => Result.Success(true, 0)
            case "false" | "0" | "no" => Result.Success(false, 0)
            case _ =>
              Result.Failure(
                List(
                  DecodeError
                    .TypeMismatch("Boolean", s"'$text' is not a valid boolean", defaultLoc)),
                defaultLoc
              )
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        case Result.Partial(_, errors, _)     => Result.Failure(errors, defaultLoc)
      }
  }

  // ============================================================================
  // Generic Type Decoders
  // ============================================================================

  /**
   * Decoder for Option[A] from XmlNode.
   *
   * Empty elements or elements with only whitespace decode to None.
   */
  given [A](using decoder: Decoder[XmlNode, A]): Decoder[XmlNode, Option[A]] =
    new Decoder[XmlNode, Option[A]] {
      def decode(value: XmlNode): Result[DecodeError, Option[A]] = value match {
        case XmlNode.Element(_, _, children) if children.isEmpty =>
          Result.Success(None, 0)
        case XmlNode.Text(content) if content.trim.isEmpty =>
          Result.Success(None, 0)
        case other =>
          decoder.decode(other) match {
            case Result.Success(a, consumed)         => Result.Success(Some(a), consumed)
            case Result.Partial(a, errors, consumed) => Result.Partial(Some(a), errors, consumed)
            case Result.Failure(errors, furthest)    => Result.Failure(errors, furthest)
          }
      }
    }

  /**
   * Decoder for List[A] from XmlNode.
   *
   * Expects an Element with child elements, each decoded as A.
   */
  given [A](using decoder: Decoder[XmlNode, A]): Decoder[XmlNode, List[A]] =
    new Decoder[XmlNode, List[A]] {
      def decode(value: XmlNode): Result[DecodeError, List[A]] = value match {
        case XmlNode.Element(_, _, children) =>
          // Filter to only element children (skip text/comments)
          val elements = children.collect { case e: XmlNode.Element => e }

          val decoded                                      = scala.collection.mutable.ListBuffer[A]()
          val errors                                       = scala.collection.mutable.ListBuffer[DecodeError]()
          var failed: Option[Result[DecodeError, List[A]]] = None

          for (elem <- elements if failed.isEmpty)
            decoder.decode(elem) match {
              case Result.Success(a, _) =>
                decoded += a
              case Result.Partial(a, errs, _) =>
                decoded += a
                errors ++= errs
              case Result.Failure(errs, _) =>
                failed = Some(Result.Failure(errs, defaultLoc))
            }

          failed.getOrElse {
            if (errors.isEmpty) Result.Success(decoded.toList, 0)
            else Result.Partial(decoded.toList, errors.toList, 0)
          }

        case other =>
          Result.Failure(
            List(DecodeError.TypeMismatch("Element", xmlNodeTypeName(other), defaultLoc)),
            defaultLoc
          )
      }
    }

  /**
   * Decoder for Seq[A] from XmlNode.
   */
  given [A](using decoder: Decoder[XmlNode, A]): Decoder[XmlNode, Seq[A]] =
    new Decoder[XmlNode, Seq[A]] {
      def decode(value: XmlNode): Result[DecodeError, Seq[A]] = {
        val listDecoder = summon[Decoder[XmlNode, List[A]]]
        listDecoder.decode(value) match {
          case Result.Success(list, consumed) => Result.Success(list.toSeq, consumed)
          case Result.Partial(list, errors, consumed) =>
            Result.Partial(list.toSeq, errors, consumed)
          case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        }
      }
    }

  /**
   * Decoder for Vector[A] from XmlNode.
   */
  given [A](using decoder: Decoder[XmlNode, A]): Decoder[XmlNode, Vector[A]] =
    new Decoder[XmlNode, Vector[A]] {
      def decode(value: XmlNode): Result[DecodeError, Vector[A]] = {
        val listDecoder = summon[Decoder[XmlNode, List[A]]]
        listDecoder.decode(value) match {
          case Result.Success(list, consumed) => Result.Success(list.toVector, consumed)
          case Result.Partial(list, errors, consumed) =>
            Result.Partial(list.toVector, errors, consumed)
          case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
        }
      }
    }

  // ============================================================================
  // XML-Specific Decoders
  // ============================================================================

  /**
   * Get an attribute value from an element.
   *
   * @param element The XML element
   * @param name The attribute name (local name, ignoring namespace)
   * @return The attribute value if found
   */
  def getAttribute(element: XmlNode.Element, name: String): Option[String] =
    element.attributes.find(_.name.localName == name).map(_.value)

  /**
   * Get a child element by name.
   *
   * @param element The parent element
   * @param name The child element name (local name, ignoring namespace)
   * @return The first matching child element if found
   */
  def getChild(element: XmlNode.Element, name: String): Option[XmlNode.Element] =
    element.children.collectFirst {
      case e: XmlNode.Element if e.name.localName == name => e
    }

  /**
   * Get all child elements by name.
   *
   * @param element The parent element
   * @param name The child element name (local name, ignoring namespace)
   * @return All matching child elements
   */
  def getChildren(element: XmlNode.Element, name: String): List[XmlNode.Element] =
    element.children.collect {
      case e: XmlNode.Element if e.name.localName == name => e
    }

  // ============================================================================
  // Helper Functions
  // ============================================================================

  /**
   * Extract text content from an XML node.
   */
  private def getTextContent(value: XmlNode): Result[DecodeError, String] = value match {
    case XmlNode.Text(content)  => Result.Success(content, 0)
    case XmlNode.CData(content) => Result.Success(content, 0)
    case XmlNode.Element(_, _, children) =>
      val textContent = children.collect {
        case XmlNode.Text(t)  => t
        case XmlNode.CData(c) => c
      }.mkString
      Result.Success(textContent, 0)
    case other =>
      Result.Failure(
        List(DecodeError.TypeMismatch("text content", xmlNodeTypeName(other), defaultLoc)),
        defaultLoc
      )
  }

  private def xmlNodeTypeName(value: XmlNode): String = value match {
    case XmlNode.Element(name, _, _)         => s"Element(${name.localName})"
    case XmlNode.Text(_)                     => "Text"
    case XmlNode.CData(_)                    => "CData"
    case XmlNode.Comment(_)                  => "Comment"
    case XmlNode.ProcessingInstruction(_, _) => "ProcessingInstruction"
  }
}
