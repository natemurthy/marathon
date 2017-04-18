#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scala.util.control.NonFatal


// Find Mesos version
val versionPattern = """.*MesosDebian = "(.*)"""".r
val maybeVersion =
    read.lines(pwd/'project/"Dependencies.scala")
        .collectFirst { case versionPattern(v) => v }

// Install Mesos
def install_mesos(version: String): Unit = {
  %%('sudo, "apt-get", "install", "-y", "--force-yes", "-no-install-recommends", s"mesos=$version")
}

maybeVersion match {
  case Some(version) =>
    try { install_mesos(version) }
    catch {
      case NonFatal(_) =>
        // Let's try again after updating
        %('sudo, "apt-get", "update")
        install_mesos(version)
    }
  case None => throw new IllegalStateException("Could not determine Mesos version.")
}

throw new IllegalStateException("Everything worked!")
