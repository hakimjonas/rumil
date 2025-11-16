package parser.interop

import parser.core._

/**
 * Error type for decoding failures.
 *
 * Represents errors that occur when converting parsed structured data
 * (JsonValue, XmlNode, etc.) into Scala types.
 *
 * Unlike ParseError which indicates syntax errors in the input text,
 * DecodeError indicates semantic errors in the structure (missing fields,
 * type mismatches, invalid values, etc.).
 *
 * Example:
 * {{{
 * // JSON is valid, but "age" is a string instead of a number
 * {"name": "Alice", "age": "thirty"}
 * // DecodeError.TypeMismatch("Int", "String", location)
 * }}}
 */
enum DecodeError {

  /**
   * A required field is missing from the input structure.
   *
   * @param field The name of the missing field
   * @param location The location where the field was expected
   */
  case MissingField(field: String, location: Location)

  /**
   * A field has the wrong type.
   *
   * @param expected The expected type name
   * @param actual The actual type or value found
   * @param location The location of the type mismatch
   */
  case TypeMismatch(expected: String, actual: String, location: Location)

  /**
   * A field has an invalid value.
   *
   * This is used for validation errors beyond type checking,
   * such as an integer being out of range, a string not matching
   * a pattern, etc.
   *
   * @param message Description of why the value is invalid
   * @param location The location of the invalid value
   */
  case InvalidValue(message: String, location: Location)

  /**
   * A custom error message.
   *
   * Used for application-specific validation or decoding logic.
   *
   * @param message The error message
   * @param location The location of the error
   */
  case Custom(message: String, location: Location)
}
