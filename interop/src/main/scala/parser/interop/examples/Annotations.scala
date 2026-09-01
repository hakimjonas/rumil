package parser.interop.examples

import scala.annotation.StaticAnnotation

/** Example field annotations for use with custom FieldTransformer implementations.
  *
  * IMPORTANT: These annotations are examples, not part of the core Rumil API. They demonstrate how
  * to implement field customization using Scala 3 inline metaprogramming.
  *
  * To use these annotations, you need to:
  *   1. Implement a FieldTransformer that reads these annotations using Scala 3 reflection
  *   2. Integrate your transformer with Decoder derivation
  *
  * See the tests/examples for complete implementations.
  *
  * Why are these examples and not part of core?
  *   - Rumil is a parser combinator library, not a serialization framework
  *   - Different projects have different annotation needs
  *   - Providing extension points is more flexible than baking in specific annotations
  *   - Similar to how the parsers module provides example parsers (JSON, TOML, etc.)
  *
  * @since 0.2.0
  */

/** Example: Rename a field in the serialized format.
  *
  * Maps a Scala field name to a different name in the source data. Commonly used for converting
  * between naming conventions (snake_case ↔ camelCase).
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
  * Standard equivalents:
  *   - Jackson (Java): @JsonProperty("name")
  *   - Serde (Rust): #[serde(rename = "name")]
  *   - Go: `json:"name"`
  *
  * @param name
  *   The name to use in the serialized format
  */
final class Rename(val name: String) extends StaticAnnotation

/** Example: Ignore a field during decoding.
  *
  * Fields annotated with @Ignore are skipped during decoding. They must have a default value in the
  * case class definition.
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
  * Standard equivalents:
  *   - Jackson (Java): @JsonIgnore
  *   - Serde (Rust): #[serde(skip)]
  *   - Go: `json:"-"`
  */
final class Ignore() extends StaticAnnotation

/** Example: Specify alternative names for a field (aliases).
  *
  * The decoder tries each name in order until one is found. Useful for handling multiple API
  * versions or legacy field names.
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
  * Standard equivalents:
  *   - Serde (Rust): #[serde(alias = "name1", alias = "name2")]
  *   - Jackson: Not built-in
  *   - Go: Not standard
  *
  * @param names
  *   Alternative names to try, in order of preference
  */
final class Aliases(val names: String*) extends StaticAnnotation
