package parser.interop

import scala.annotation.StaticAnnotation

/**
 * Annotations for customizing decoder behavior on case class fields.
 *
 * These annotations work with the automatic Decoder derivation to control
 * how case class fields are mapped to and decoded from structured data formats
 * (JSON, XML, TOML, YAML, etc.).
 *
 * Example:
 * {{{
 * case class User(
 *   @Rename("user_name") name: String,
 *   @Rename("user_age") age: Int,
 *   @Ignore password: String = "default"
 * )
 * }}}
 */

/**
 * Rename a field in the serialized format.
 *
 * By default, Decoder derivation maps case class field names directly to
 * keys in the structured data. Use @Rename to specify a different name
 * in the serialized format.
 *
 * Example:
 * {{{
 * case class Person(
 *   @Rename("first_name") firstName: String,
 *   @Rename("last_name") lastName: String
 * )
 *
 * // Decodes from: {"first_name": "Alice", "last_name": "Smith"}
 * // Instead of: {"firstName": "Alice", "lastName": "Smith"}
 * }}}
 *
 * @param name The name to use in the serialized format
 */
final class Rename(val name: String) extends StaticAnnotation

/**
 * Ignore a field during decoding.
 *
 * Fields annotated with @Ignore will not be decoded from structured data.
 * They must have a default value in the case class definition.
 *
 * Example:
 * {{{
 * case class Config(
 *   host: String,
 *   port: Int,
 *   @Ignore internalField: String = "computed"
 * )
 *
 * // Only decodes "host" and "port", ignores "internalField"
 * }}}
 *
 * Note: If a field is marked @Ignore but has no default value, the decoder
 * will fail at compile time with a clear error message.
 */
final class Ignore() extends StaticAnnotation

/**
 * Mark a field as optional with a default value if missing.
 *
 * When a field is missing in the input, instead of producing an error,
 * use the provided default value. This is different from Option[T] which
 * represents explicit presence/absence.
 *
 * Example:
 * {{{
 * case class Config(
 *   host: String,
 *   @Default("8080") port: String
 * )
 *
 * // Input: {"host": "localhost"}
 * // Result: Config("localhost", "8080")
 * }}}
 *
 * @param value The default value to use if the field is missing (as a string that will be parsed)
 */
final class Default(val value: String) extends StaticAnnotation

/**
 * Specify alternative names for a field.
 *
 * The decoder will try each name in order until one is found.
 * Useful for handling multiple versions of a format or legacy field names.
 *
 * Example:
 * {{{
 * case class User(
 *   @Aliases("username", "user_name", "login") name: String
 * )
 *
 * // Accepts any of: {"name": ...}, {"username": ...}, {"user_name": ...}, {"login": ...}
 * }}}
 *
 * @param names Alternative names to try, in order of preference
 */
final class Aliases(val names: String*) extends StaticAnnotation

/**
 * Mark a field as required, failing fast if missing.
 *
 * By default, missing fields produce errors but decoding continues (partial result).
 * Use @Required to fail immediately if the field is missing.
 *
 * Example:
 * {{{
 * case class User(
 *   @Required id: String,
 *   name: String
 * )
 *
 * // Input: {"name": "Alice"} → Failure (missing required field "id")
 * }}}
 */
final class Required() extends StaticAnnotation

/**
 * Flatten a nested object's fields into the parent.
 *
 * Instead of having a nested object, decode its fields directly into the parent.
 *
 * Example:
 * {{{
 * case class Address(street: String, city: String)
 * case class Person(
 *   name: String,
 *   @Flatten address: Address
 * )
 *
 * // Input: {"name": "Alice", "street": "123 Main", "city": "Boston"}
 * // Instead of: {"name": "Alice", "address": {"street": "123 Main", "city": "Boston"}}
 * }}}
 */
final class Flatten() extends StaticAnnotation
