ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "dev.fungal"

javacOptions ++= Seq(
  "--release", "25"
)

lazy val root = (project in file("."))
  .settings(
    name := "parser-combinators",

    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
      "org.scalameta" %% "munit" % "1.2.1" % Test
    ),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wsafe-init",
      "-Wunused:all",
      "-Wvalue-discard",
      "-explain",
      "-no-indent",        // Braces only - no significant indentation
      "-old-syntax"        // Prefer braces
    ),

    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow"  // Suppress Java 25 Unsafe warnings (Scala 3.7.4 issue)
    ),

    Test / fork := false
  )
