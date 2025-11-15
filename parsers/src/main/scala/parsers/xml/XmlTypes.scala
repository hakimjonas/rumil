package parsers.xml

// ============================================================================
// XML TYPES - Well-Formed XML with Namespaces (Enums, No Case Classes)
// ============================================================================

/**
 * Qualified name with optional namespace prefix.
 *
 * @param prefix Optional namespace prefix (e.g., "xs" in "xs:element")
 * @param localName Local name part (e.g., "element" in "xs:element")
 */
type QName = (prefix: Option[String], localName: String)

/**
 * Helper to create a QName from a string.
 */
def qname(name: String): QName = {
  name.split(':').toList match {
    case List(local) => (prefix = None, localName = local)
    case List(pre, local) => (prefix = Some(pre), localName = local)
    case _ => (prefix = None, localName = name)
  }
}

/**
 * Helper to create a QName with explicit prefix.
 */
def qnameWith(prefix: String, localName: String): QName = {
  (prefix = Some(prefix), localName = localName)
}

/**
 * XML attribute.
 *
 * @param name Attribute name (qualified)
 * @param value Attribute value
 */
type XmlAttribute = (name: QName, value: String)

/**
 * Namespace declaration.
 *
 * @param prefix Namespace prefix (None for default namespace)
 * @param uri Namespace URI
 */
type NamespaceDecl = (prefix: Option[String], uri: String)

/**
 * XML node types.
 */
enum XmlNode {
  /**
   * Element with name, attributes, and children.
   */
  case Element(
    name: QName,
    attributes: List[XmlAttribute],
    children: List[XmlNode]
  )

  /**
   * Text content (character data).
   */
  case Text(content: String)

  /**
   * CDATA section (unparsed character data).
   */
  case CData(content: String)

  /**
   * Comment.
   */
  case Comment(content: String)

  /**
   * Processing instruction.
   */
  case ProcessingInstruction(target: String, content: String)
}

/**
 * XML document with optional prolog.
 *
 * @param version XML version (typically "1.0")
 * @param encoding Character encoding (e.g., "UTF-8")
 * @param standalone Whether document is standalone
 * @param root Root element
 */
type XmlDocument = (
  version: String,
  encoding: Option[String],
  standalone: Option[Boolean],
  root: XmlNode
)

/**
 * Default XML document header values.
 */
val defaultXmlVersion: String = "1.0"
val defaultXmlEncoding: Option[String] = Some("UTF-8")

/**
 * XML parsing configuration.
 *
 * @param preserveWhitespace Whether to preserve whitespace in text nodes
 * @param parseComments Whether to parse and include comments
 * @param parseProcessingInstructions Whether to parse PIs
 * @param expandEntities Whether to expand entity references
 */
type XmlConfig = (
  preserveWhitespace: Boolean,
  parseComments: Boolean,
  parseProcessingInstructions: Boolean,
  expandEntities: Boolean
)

/**
 * Default XML parsing configuration.
 */
val defaultXmlConfig: XmlConfig = (
  preserveWhitespace = false,
  parseComments = true,
  parseProcessingInstructions = true,
  expandEntities = true
)

/**
 * Strict XML parsing configuration (minimal features).
 */
val strictXmlConfig: XmlConfig = (
  preserveWhitespace = true,
  parseComments = false,
  parseProcessingInstructions = false,
  expandEntities = false
)

/**
 * Predefined XML entities.
 */
val xmlEntities: Map[String, String] = Map(
  "lt" -> "<",
  "gt" -> ">",
  "amp" -> "&",
  "quot" -> "\"",
  "apos" -> "'"
)
