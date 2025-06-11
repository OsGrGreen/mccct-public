ThisBuild / scalaVersion := "3.7.1"

lazy val core = (project in file("core"))
  .settings(
    name := "core",
    libraryDependencies += "ch.epfl.lamp" %% "gears" % "0.2.0"
  )

lazy val sample = (project in file("sample"))
  .settings(
    name := "core",
    libraryDependencies += "ch.epfl.lamp" %% "gears" % "0.2.0"
  )
  .dependsOn(core)
