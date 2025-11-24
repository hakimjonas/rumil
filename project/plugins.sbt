addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.2")
addSbtPlugin("org.scalameta"       % "sbt-scalafmt"     % "2.5.4")
addSbtPlugin("ch.epfl.scala"       % "sbt-scalafix"     % "0.13.0")
addSbtPlugin("org.scoverage"       % "sbt-scoverage"    % "2.0.9")

// Benchmarking
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

// Publishing plugins
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"   % "3.11.3")
addSbtPlugin("com.github.sbt" % "sbt-pgp"        % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.2")
