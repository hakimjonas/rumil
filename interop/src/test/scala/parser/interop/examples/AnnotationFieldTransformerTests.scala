package parser.interop.examples

import munit.FunSuite

/**
 * Tests demonstrating the annotation-based FieldTransformer example.
 *
 * These tests show how to use Scala 3 inline metaprogramming to read
 * field annotations and generate customized FieldTransformers.
 */
class AnnotationFieldTransformerTests extends FunSuite {

  test("derive reads @Rename annotation") {
    case class User(
      @Rename("user_name") name: String,
      @Rename("user_age") age: Int
    )

    val transformer = AnnotationFieldTransformer.derive[User]

    assertEquals(transformer.transformFieldName("name"), "user_name")
    assertEquals(transformer.transformFieldName("age"), "user_age")
  }

  test("derive handles fields without annotations") {
    case class Person(
      @Rename("first_name") firstName: String,
      lastName: String,
      age: Int
    )

    val transformer = AnnotationFieldTransformer.derive[Person]

    assertEquals(transformer.transformFieldName("firstName"), "first_name")
    assertEquals(transformer.transformFieldName("lastName"), "lastName")
    assertEquals(transformer.transformFieldName("age"), "age")
  }

  test("derive reads @Ignore annotation") {
    case class Config(
      host: String,
      port: Int,
      @Ignore internal: String = "default"
    )

    val transformer = AnnotationFieldTransformer.derive[Config]

    assertEquals(transformer.shouldIncludeField("host"), true)
    assertEquals(transformer.shouldIncludeField("port"), true)
    assertEquals(transformer.shouldIncludeField("internal"), false)
  }

  test("derive handles mixed @Rename and @Ignore") {
    case class Mixed(
      @Rename("custom_name") name: String,
      normalField: Int,
      @Ignore skipped: Boolean = false,
      @Rename("renamed_field") another: String
    )

    val transformer = AnnotationFieldTransformer.derive[Mixed]

    // Check renames
    assertEquals(transformer.transformFieldName("name"), "custom_name")
    assertEquals(transformer.transformFieldName("normalField"), "normalField")
    assertEquals(transformer.transformFieldName("another"), "renamed_field")

    // Check includes
    assertEquals(transformer.shouldIncludeField("name"), true)
    assertEquals(transformer.shouldIncludeField("normalField"), true)
    assertEquals(transformer.shouldIncludeField("skipped"), false)
    assertEquals(transformer.shouldIncludeField("another"), true)
  }

  test("derive with no annotations behaves like identity") {
    case class Plain(name: String, age: Int, active: Boolean)

    val transformer = AnnotationFieldTransformer.derive[Plain]

    assertEquals(transformer.transformFieldName("name"), "name")
    assertEquals(transformer.transformFieldName("age"), "age")
    assertEquals(transformer.transformFieldName("active"), "active")
    assertEquals(transformer.shouldIncludeField("name"), true)
    assertEquals(transformer.shouldIncludeField("age"), true)
    assertEquals(transformer.shouldIncludeField("active"), true)
  }

  test("derive with special characters in @Rename") {
    case class Special(
      @Rename("field-name") fieldName: String,
      @Rename("field.value") fieldValue: Int,
      @Rename("field:type") fieldType: String
    )

    val transformer = AnnotationFieldTransformer.derive[Special]

    assertEquals(transformer.transformFieldName("fieldName"), "field-name")
    assertEquals(transformer.transformFieldName("fieldValue"), "field.value")
    assertEquals(transformer.transformFieldName("fieldType"), "field:type")
  }

  test("derive with empty string in @Rename") {
    case class EmptyName(
      @Rename("") field: String
    )

    val transformer = AnnotationFieldTransformer.derive[EmptyName]

    assertEquals(transformer.transformFieldName("field"), "")
  }

  test("derive example: snake_case API to camelCase Scala") {
    case class ApiResponse(
      @Rename("user_name") userName: String,
      @Rename("user_age") userAge: Int,
      @Rename("is_admin") isAdmin: Boolean,
      @Ignore internalId: String = "generated"
    )

    val transformer = AnnotationFieldTransformer.derive[ApiResponse]

    // This transformer would help decode from snake_case JSON
    assertEquals(transformer.transformFieldName("userName"), "user_name")
    assertEquals(transformer.transformFieldName("userAge"), "user_age")
    assertEquals(transformer.transformFieldName("isAdmin"), "is_admin")
    assertEquals(transformer.shouldIncludeField("internalId"), false)
  }
}
