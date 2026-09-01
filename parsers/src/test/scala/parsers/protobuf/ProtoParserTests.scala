package parsers.protobuf

import munit.FunSuite

import parser.core.*
import parser.syntax.*

class ProtoParserTests extends FunSuite {
  import ProtoDefinition.*

  test("parse syntax statement") {
    val proto = """syntax = "proto3";"""
    val result = parseProto(proto)
    assert(result.isSuccess)
    val file = result.toOption.get
    assertEquals(file.syntax, "proto3")
  }

  test("parse package statement") {
    val proto = """syntax = "proto3";
package example.v1;
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
    val file = result.toOption.get
    assert(
      file.definitions.exists {
        case Package(name) => name == "example.v1"
        case _ => false
      },
      "Expected package definition for example.v1"
    )
  }

  test("parse simple message") {
    val proto = """syntax = "proto3";

message Person {
  string name = 1;
  int32 age = 2;
}
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
    val file = result.toOption.get
    val msg = file.definitions.collectFirst { case m: Message =>
      m
    }
    assert(msg.isDefined)
    assertEquals(msg.get.name, "Person")
    assertEquals(msg.get.fields.length, 2)
  }

  test("parse message with repeated field") {
    val proto = """syntax = "proto3";

message Container {
  repeated string items = 1;
}
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
  }

  test("parse enum") {
    val proto = """syntax = "proto3";

enum Status {
  UNKNOWN = 0;
  ACTIVE = 1;
  INACTIVE = 2;
}
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
    val file = result.toOption.get
    val enumDef = file.definitions.collectFirst { case e: Enum =>
      e
    }
    assert(enumDef.isDefined)
    assertEquals(enumDef.get.name, "Status")
    assertEquals(enumDef.get.values.length, 3)
  }

  test("parse service with RPC") {
    val proto = """syntax = "proto3";

service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
}
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
    val file = result.toOption.get
    val service = file.definitions.collectFirst { case s: Service =>
      s
    }
    assert(service.isDefined)
    assertEquals(service.get.name, "Greeter")
    assertEquals(service.get.methods.length, 1)
  }

  test("parse import") {
    val proto = """syntax = "proto3";

import "google/protobuf/timestamp.proto";
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
  }

  test("parse complete proto file") {
    val proto = """syntax = "proto3";

package example;

import "common.proto";

message User {
  string id = 1;
  string name = 2;
  int32 age = 3;
  repeated string emails = 4;
}

enum Role {
  USER = 0;
  ADMIN = 1;
}

service UserService {
  rpc GetUser (UserRequest) returns (User);
  rpc ListUsers (ListRequest) returns (stream User);
}
"""
    val result = parseProto(proto)
    assert(result.isSuccess)
  }
}
