#!/usr/bin/env amm

import ammonite.ops._
import scalaj.http._

@main
def main(db_endpoint: String): Unit = {
  val coveragePath =
    ((ls! pwd/"target"/"scala-2.11"/"scoverage-report-unit") |? (_.ext == "csv")).head
  val coverageData = read.bytes(coveragePath)
  Http(db_endpoint)
    .postData(coverageData)
    .header("Content-Type", "text/csv")
    .asString
    .throwError

  println("Published unit test coverage data.")
}
