//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:1.0.0-alpha"
//> using dep "net.ghoula::rumil-interop:1.0.0-alpha"
//> using dep "net.ghoula::rumil-parsers:1.0.0-alpha"

package examples.nestedstructures

import parser.core.*
import parser.interop.*
import parser.interop.JsonDecoders.given
import parsers.json.{parseJson, JsonValue}

/**
 * Example: Parsing Nested Structures
 *
 * This example demonstrates parsing deeply nested JSON into nested case classes.
 * It models a simple blog system with authors, posts, and tags.
 */
@main def nestedStructuresExample(): Unit = {
  // Define our domain model
  case class Tag(name: String)
  case class Post(title: String, content: String, tags: List[Tag])
  case class Author(name: String, email: String, posts: List[Post])
  case class Blog(title: String, authors: List[Author])

  // Derive decoders for each type
  // Note: Order matters! Define decoders for nested types first
  given Decoder[JsonValue, Tag] = Decoder.derived
  given Decoder[JsonValue, Post] = Decoder.derived
  given Decoder[JsonValue, Author] = Decoder.derived
  given Decoder[JsonValue, Blog] = Decoder.derived

  // Example 1: Parse a single author with multiple posts
  println("--- Example 1: Single Author ---")

  val authorJson = """{
    "name": "Alice Smith",
    "email": "alice@example.com",
    "posts": [
      {
        "title": "Getting Started with Scala",
        "content": "Scala is a powerful language...",
        "tags": [
          {"name": "scala"},
          {"name": "tutorial"},
          {"name": "beginner"}
        ]
      },
      {
        "title": "Advanced Functional Programming",
        "content": "Let's explore advanced FP concepts...",
        "tags": [
          {"name": "scala"},
          {"name": "functional-programming"},
          {"name": "advanced"}
        ]
      }
    ]
  }"""

  val authorResult = parseJson(authorJson).flatMap(json =>
    Decoder[JsonValue, Author].decode(json)
  )

  authorResult match {
    case Result.Success(author, _) =>
      println(s"✓ Parsed author: ${author.name} <${author.email}>")
      println(s"  Posts (${author.posts.length}):")
      author.posts.foreach { post =>
        println(s"    - \"${post.title}\"")
        println(s"      Tags: ${post.tags.map(_.name).mkString(", ")}")
      }

    case Result.Failure(errors, _) =>
      println(s"✗ Failed to parse author:")
      errors.foreach(err => println(s"  - $err"))

    case Result.Partial(author, errors, _) =>
      println(s"⚠ Partially parsed: ${author.name}")
      println(s"  Errors: $errors")
  }

  // Example 2: Parse an entire blog with multiple authors
  println("\n--- Example 2: Entire Blog ---")

  val blogJson = """{
    "title": "My Tech Blog",
    "authors": [
      {
        "name": "Alice Smith",
        "email": "alice@example.com",
        "posts": [
          {
            "title": "Getting Started with Scala",
            "content": "Scala is a powerful language...",
            "tags": [{"name": "scala"}, {"name": "tutorial"}]
          }
        ]
      },
      {
        "name": "Bob Johnson",
        "email": "bob@example.com",
        "posts": [
          {
            "title": "Introduction to Parser Combinators",
            "content": "Parser combinators are...",
            "tags": [{"name": "parsing"}, {"name": "compilers"}]
          },
          {
            "title": "Building a JSON Parser",
            "content": "Let's build a JSON parser from scratch...",
            "tags": [{"name": "json"}, {"name": "parsing"}, {"name": "tutorial"}]
          }
        ]
      }
    ]
  }"""

  val blogResult = parseJson(blogJson).flatMap(json =>
    Decoder[JsonValue, Blog].decode(json)
  )

  blogResult match {
    case Result.Success(blog, _) =>
      println(s"✓ Parsed blog: \"${blog.title}\"")
      println(s"  Authors: ${blog.authors.length}")

      val totalPosts = blog.authors.map(_.posts.length).sum
      println(s"  Total posts: $totalPosts")

      blog.authors.foreach { author =>
        println(s"\n  Author: ${author.name}")
        author.posts.foreach { post =>
          println(s"    - ${post.title} (${post.tags.length} tags)")
        }
      }

    case Result.Failure(errors, _) =>
      println(s"✗ Failed to parse blog:")
      errors.foreach(err => println(s"  - $err"))

    case Result.Partial(blog, errors, _) =>
      println(s"⚠ Partially parsed: ${blog.title}")
      println(s"  Errors: $errors")
  }

  // Example 3: Error handling with missing fields
  println("\n--- Example 3: Error Handling ---")

  val incompleteJson = """{
    "name": "Charlie Brown",
    "posts": [
      {
        "title": "My First Post",
        "content": "Hello world!",
        "tags": [{"name": "intro"}]
      }
    ]
  }"""

  val incompleteResult = parseJson(incompleteJson).flatMap(json =>
    Decoder[JsonValue, Author].decode(json)
  )

  incompleteResult match {
    case Result.Success(author, _) =>
      println(s"✓ Parsed: ${author.name}")

    case Result.Failure(errors, _) =>
      println(s"✗ Decoding failed:")
      errors.foreach(err => println(s"  - $err"))

    case Result.Partial(author, errors, _) =>
      println(s"⚠ Partial parse:")
      println(s"  Name: ${author.name}")
      println(s"  Email: ${author.email}")
      println(s"  Posts: ${author.posts.length}")
      println(s"  Errors:")
      errors.foreach(err => println(s"    - $err"))
  }
}
