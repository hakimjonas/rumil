# Simpler Decoder Derivation Approach

The issue: We're trying to store `Decoder[JsonValue, SpecificType]` in a homogeneous list as `Decoder[JsonValue, Any]`, which requires casting.

## Solution: Use Parser.Custom with runtime decoder lookup

Instead of generating complex macro code, use a runtime approach:

```scala
inline def derived[From, To](using m: Mirror.ProductOf[To]): Decoder[From, To] =
  new Decoder[From, To] {
    def decode(value: From): Result[DecodeError, To] = value match {
      case jsonObj: JsonValue.Object =>
        decodeObject[To](jsonObj.fields, m)
      case other =>
        Result.Failure(
          List(DecodeError.TypeMismatch("Object", "<other>", (1, 1, 0))),
          (1, 1, 0)
        )
    }
  }

def decodeObject[A](
  fields: Map[String, JsonValue],
  mirror: Mirror.ProductOf[A]
): Result[DecodeError, A] = {
  // Use mirror to get field names and types
  // Decode each field using summon
  // Reconstruct using mirror.fromProduct
}
```

This avoids macros entirely for the first version and we can optimize later.
