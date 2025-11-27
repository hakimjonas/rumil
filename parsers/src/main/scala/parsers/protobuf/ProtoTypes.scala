package parsers.protobuf

import scala.language.strictEquality

/**
 * Protocol Buffers field types.
 */
enum ProtoType {
  case Double, Float, Int32, Int64, Uint32, Uint64
  case Sint32, Sint64, Fixed32, Fixed64, Sfixed32, Sfixed64
  case Bool, String, Bytes
  case Message(name: scala.Predef.String)
  case Enum(name: scala.Predef.String)
  case Map(keyType: ProtoType, valueType: ProtoType)
  case Repeated(elementType: ProtoType)
}

given CanEqual[ProtoType, ProtoType] = CanEqual.derived

/**
 * Field rule (proto3 only supports optional and repeated).
 */
enum FieldRule {
  case Optional
  case Repeated
  case Singular // Default in proto3
}

/**
 * Protocol Buffers field definition.
 *
 * @param rule Field rule (optional, repeated, or singular)
 * @param fieldType Field type
 * @param name Field name
 * @param number Field number (tag)
 * @param options Field options
 */
type ProtoField = (
  rule: FieldRule,
  fieldType: ProtoType,
  name: scala.Predef.String,
  number: Int,
  options: Map[scala.Predef.String, scala.Predef.String]
)

/**
 * Enum value definition.
 *
 * @param name Value name
 * @param number Value number
 */
type EnumValue = (name: scala.Predef.String, number: Int)

/**
 * Protocol Buffers definitions.
 */
enum ProtoDefinition {
  case Message(
    name: scala.Predef.String,
    fields: List[ProtoField],
    nested: List[ProtoDefinition]
  )
  case Enum(
    name: scala.Predef.String,
    values: List[EnumValue]
  )
  case Service(
    name: scala.Predef.String,
    methods: List[ProtoMethod]
  )
  case Import(path: scala.Predef.String, isPublic: scala.Boolean)
  case Package(name: scala.Predef.String)
  case Option(name: scala.Predef.String, value: scala.Predef.String)
}

given CanEqual[ProtoDefinition, ProtoDefinition] = CanEqual.derived

/**
 * RPC method definition.
 *
 * @param name Method name
 * @param inputType Input message type
 * @param outputType Output message type
 * @param inputStreaming Whether input is streaming
 * @param outputStreaming Whether output is streaming
 */
type ProtoMethod = (
  name: scala.Predef.String,
  inputType: scala.Predef.String,
  outputType: scala.Predef.String,
  inputStreaming: scala.Boolean,
  outputStreaming: scala.Boolean
)

/**
 * Complete .proto file.
 *
 * @param syntax Protocol Buffers syntax version ("proto3")
 * @param definitions Top-level definitions
 */
type ProtoFile = (
  syntax: scala.Predef.String,
  definitions: List[ProtoDefinition]
)
