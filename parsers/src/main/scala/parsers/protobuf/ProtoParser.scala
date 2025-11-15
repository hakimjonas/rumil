package parsers.protobuf

import parser.core.*
import parser.syntax.*
import parsers.common.*

// ============================================================================
// PROTOCOL BUFFERS PARSER - Proto3 Syntax
// ============================================================================

/**
 * Protocol Buffers .proto file parser (proto3 syntax).
 *
 * Supports:
 * - Message definitions
 * - Enum definitions
 * - Service definitions with RPC methods
 * - Field types (scalar, message, enum, map, repeated)
 * - Imports and packages
 * - Options
 * - Comments
 */
object ProtoParser {

  /**
   * Parses a .proto file from a string.
   *
   * @param input .proto file content
   * @return Result containing parsed proto file
   */
  def parse(input: scala.Predef.String): Result[ParseError, ProtoFile] = {
    protoFile.run(input)
  }

  // ============================================================================
  // Whitespace and Comments
  // ============================================================================

  private def ws: Parser[ParseError, Unit] = {
    satisfy(c => c == ' ' || c == '\t' || c == '\r' || c == '\n', "whitespace")
      .many.void
  }

  private def lineComment: Parser[ParseError, Unit] = {
    string("//") *> satisfy(_ != '\n', "comment char").many *> (newline.void | eof)
  }

  private def blockComment: Parser[ParseError, Unit] = {
    string("/*") *> satisfy(_ => true, "any char").many.flatMap { chars =>
      val str = chars.mkString
      if (str.contains("*/")) {
        string("*/").void
      } else {
        fail(ParseError.Custom("Unclosed comment", (line = 0, column = 0, offset = 0)))
      }
    }
  }

  private def skip: Parser[ParseError, Unit] = {
    (ws | lineComment | blockComment).many.void
  }

  // ============================================================================
  // Identifiers and Keywords
  // ============================================================================

  private def protoIdentifier: Parser[ParseError, scala.Predef.String] = {
    for {
      first <- letter | char('_')
      rest <- (alphaNum | char('_')).many
    } yield s"$first${rest.mkString}"
  }

  private def fullIdentifier: Parser[ParseError, scala.Predef.String] = {
    protoIdentifier.sepBy1(char('.')).map(_.mkString("."))
  }

  // ============================================================================
  // Types
  // ============================================================================

  private def scalarType: Parser[ParseError, ProtoType] = {
    val types = Map(
      "double" -> ProtoType.Double,
      "float" -> ProtoType.Float,
      "int32" -> ProtoType.Int32,
      "int64" -> ProtoType.Int64,
      "uint32" -> ProtoType.Uint32,
      "uint64" -> ProtoType.Uint64,
      "sint32" -> ProtoType.Sint32,
      "sint64" -> ProtoType.Sint64,
      "fixed32" -> ProtoType.Fixed32,
      "fixed64" -> ProtoType.Fixed64,
      "sfixed32" -> ProtoType.Sfixed32,
      "sfixed64" -> ProtoType.Sfixed64,
      "bool" -> ProtoType.Bool,
      "string" -> ProtoType.String,
      "bytes" -> ProtoType.Bytes
    )

    choice(types.map { case (name, typ) =>
      string(name).as(typ)
    }.toList)
  }

  private def messageType: Parser[ParseError, ProtoType] = {
    fullIdentifier.map(ProtoType.Message.apply)
  }

  private def mapType: Parser[ParseError, ProtoType] = {
    for {
      _ <- string("map")
      _ <- skip *> char('<') *> skip
      keyType <- scalarType
      _ <- skip *> char(',') *> skip
      valueType <- protoType
      _ <- skip *> char('>') *> skip
    } yield ProtoType.Map(keyType, valueType)
  }

  private def protoType: Parser[ParseError, ProtoType] = {
    Parser.Custom { state =>
      val typeParser = mapType | scalarType | messageType
      parser.runtime.interpret(typeParser, state)
    }
  }

  // ============================================================================
  // Fields
  // ============================================================================

  private def field: Parser[ParseError, ProtoField] = {
    for {
      _ <- skip
      repeated <- string("repeated").optional
      _ <- skip
      fieldType <- if (repeated.isDefined) {
        protoType.map(ProtoType.Repeated.apply)
      } else {
        protoType
      }
      _ <- skip
      name <- protoIdentifier
      _ <- skip *> char('=') *> skip
      number <- unsignedInt
      _ <- skip *> char(';') *> skip
    } yield (
      rule = if (repeated.isDefined) FieldRule.Repeated else FieldRule.Singular,
      fieldType = fieldType,
      name = name,
      number = number,
      options = Map.empty
    )
  }

  // ============================================================================
  // Messages
  // ============================================================================

  private def messageBody: Parser[ParseError, List[ProtoField]] = {
    Parser.Custom { state =>
      val bodyParser = for {
        _ <- skip *> char('{') *> skip
        fields <- field.many
        _ <- skip *> char('}') *> skip
      } yield fields

      parser.runtime.interpret(bodyParser, state)
    }
  }

  private def messageDef: Parser[ParseError, ProtoDefinition] = {
    for {
      _ <- skip *> string("message") *> skip
      name <- protoIdentifier
      _ <- skip
      fields <- messageBody
    } yield ProtoDefinition.Message(name, fields, List())
  }

  // ============================================================================
  // Enums
  // ============================================================================

  private def enumValue: Parser[ParseError, EnumValue] = {
    for {
      _ <- skip
      name <- protoIdentifier
      _ <- skip *> char('=') *> skip
      number <- unsignedInt
      _ <- skip *> char(';') *> skip
    } yield (name = name, number = number)
  }

  private def enumDef: Parser[ParseError, ProtoDefinition] = {
    for {
      _ <- skip *> string("enum") *> skip
      name <- protoIdentifier
      _ <- skip *> char('{') *> skip
      values <- enumValue.many
      _ <- skip *> char('}') *> skip
    } yield ProtoDefinition.Enum(name, values)
  }

  // ============================================================================
  // Services
  // ============================================================================

  private def rpcMethod: Parser[ParseError, ProtoMethod] = {
    for {
      _ <- skip *> string("rpc") *> skip
      name <- protoIdentifier
      _ <- skip *> char('(') *> skip
      inputStreaming <- string("stream").optional
      _ <- skip
      inputType <- fullIdentifier
      _ <- skip *> char(')') *> skip
      _ <- string("returns") *> skip
      _ <- char('(') *> skip
      outputStreaming <- string("stream").optional
      _ <- skip
      outputType <- fullIdentifier
      _ <- skip *> char(')') *> skip
      _ <- (char('{') *> skip *> char('}') | char(';')).void *> skip
    } yield (
      name = name,
      inputType = inputType,
      outputType = outputType,
      inputStreaming = inputStreaming.isDefined,
      outputStreaming = outputStreaming.isDefined
    )
  }

  private def serviceDef: Parser[ParseError, ProtoDefinition] = {
    Parser.Custom { state =>
      val serviceParser = for {
        _ <- skip *> string("service") *> skip
        name <- protoIdentifier
        _ <- skip *> char('{') *> skip
        methods <- rpcMethod.many
        _ <- skip *> char('}') *> skip
      } yield ProtoDefinition.Service(name, methods)

      parser.runtime.interpret(serviceParser, state)
    }
  }

  // ============================================================================
  // Top-Level Statements
  // ============================================================================

  private def syntaxStatement: Parser[ParseError, scala.Predef.String] = {
    for {
      _ <- skip *> string("syntax") *> skip
      _ <- char('=') *> skip
      _ <- char('"')
      version <- string("proto3") | string("proto2")
      _ <- char('"') *> skip *> char(';') *> skip
    } yield version
  }

  private def packageStatement: Parser[ParseError, ProtoDefinition] = {
    for {
      _ <- skip *> string("package") *> skip
      name <- fullIdentifier
      _ <- skip *> char(';') *> skip
    } yield ProtoDefinition.Package(name)
  }

  private def importStatement: Parser[ParseError, ProtoDefinition] = {
    for {
      _ <- skip *> string("import") *> skip
      isPublic <- string("public").optional
      _ <- skip *> char('"')
      path <- satisfy(_ != '"', "path char").many.map(_.mkString)
      _ <- char('"') *> skip *> char(';') *> skip
    } yield ProtoDefinition.Import(path, isPublic.isDefined)
  }

  // ============================================================================
  // Proto File
  // ============================================================================

  private def protoFile: Parser[ParseError, ProtoFile] = {
    Parser.Custom { state =>
      val fileParser = for {
        _ <- skip
        syntax <- syntaxStatement.optional
        _ <- skip
        definitions <- (
          packageStatement |
          importStatement |
          messageDef |
          enumDef |
          serviceDef
        ).many
        _ <- skip *> eof
      } yield (
        syntax = syntax.getOrElse("proto3"),
        definitions = definitions
      )

      parser.runtime.interpret(fileParser, state)
    }
  }
}
