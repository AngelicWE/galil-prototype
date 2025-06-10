import sbt._

object Coverage extends AutoPlugin {
  import scoverage.ScoverageSbtPlugin
  import ScoverageSbtPlugin.autoImport._

  override def requires: Plugins = ScoverageSbtPlugin

  override def projectSettings: Seq[Setting[?]] = Seq(
    coverageEnabled          := true,
    coverageMinimumStmtTotal := 80,
    // XXX TODO FIXME: Scala3 coverage results may be lower than scala2
    // See https://github.com/scala/scala3/issues/21877
    // coverageFailOnMinimum    := true,
    coverageFailOnMinimum    := false,
    coverageHighlighting     := true,
    coverageOutputCobertura  := true,
    coverageOutputXML        := true
  )

}
