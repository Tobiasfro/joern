package io.joern.pysrc2cpg.passes

import io.joern.pysrc2cpg.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language._

class DynamicTypeHintFullNamePassTests extends PySrc2CpgFixture(withOssDataflow = false) {

  "dynamic type hints" should {
    lazy val cpg = code("""
        |from foo.bar import Woo
        |
        |def m() -> Woo:
        |   x
        |
        |""".stripMargin)

    "take into accounts imports" in {
      cpg.method("m").methodReturn.dynamicTypeHintFullName.l shouldBe List("foo.bar.Woo")
    }
  }

}
