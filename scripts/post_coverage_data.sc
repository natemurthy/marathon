#!/usr/bin/env amm

import ammonite.ops._
import scalaj.http._

@main
def main(db_endpoint: String): Unit = {
  val coverageData =
    read.bytes(pwd/"target"/"scala-2.11"/"scoverage-report-unit"/"unit_test_coverage_2017-04-20_16:19:0.csv")
  Http(db_endpoint)
    .postData(coverageData)
    .header("Content-Type", "text/csv")
    .asString
    .throwError
}
