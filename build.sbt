ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion     := "3.7.4"
ThisBuild / organization     := "net.ghoula"
ThisBuild / organizationName := "Hakim Ghoula"

ThisBuild / licenses := List(
  "MIT" -> url("https://opensource.org/licenses/MIT")
)

ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://hakim.ghoula.net")
  )
)

// Enable scalafix semantic rules
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Command aliases for convenience
addCommandAlias("testAll", ";core/test;parsers/test")
addCommandAlias("prepare", ";scalafmtAll;scalafixAll")

javacOptions ++= Seq(
  "--release",
  "25"
)

// Core parser combinator library
lazy val core = (project in file("core"))
  .settings(
    name := "rumil-core",
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test,
      "org.scalameta"  %% "munit"      % "1.2.1"  % Test
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
      "-no-indent", // Braces only - no significant indentation
      "-old-syntax" // Prefer braces
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow" // Suppress Java 25 Unsafe warnings (Scala 3.7.4 issue)
    ),
    Test / fork := false
  )

// Format parsers (CSV, JSON, XML, YAML, etc.)
lazy val parsers = (project in file("parsers"))
  .settings(
    name := "rumil-parsers",
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test,
      "org.scalameta"  %% "munit"      % "1.2.1"  % Test
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
      "-no-indent",
      "-old-syntax"
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow"
    ),
    Test / fork := false
  )
  .dependsOn(core % "compile->compile;test->test")

// Idiomatic Scala interop (case class derivation)
lazy val interop = (project in file("interop"))
  .settings(
    name := "rumil-interop",
    libraryDependencies ++= Seq(
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
      "-no-indent",
      "-old-syntax"
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow"
    ),
    Test / fork := false
  )
  .dependsOn(core % "compile->compile;test->test")

// Root aggregator project
lazy val root = (project in file("."))
  .settings(
    name           := "rumil",
    publish / skip := true
  )
  .aggregate(core, parsers, interop)
