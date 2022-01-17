package io.joern.kotlin2cpg.querying

import io.joern.kotlin2cpg.Kt2CpgTestContext
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.semanticcpg.language._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SubscriptTests extends AnyFreeSpec with Matchers {

  "CPG for code with array index access call" - {
    lazy val cpg = Kt2CpgTestContext.buildCpg("""
      |fun foo(): Int {
      |  val names = listOf(1, 2, 3)
      |  return names[0]
      |}
      |""".stripMargin)

    "should contain a CALL node for `Operators.indexAccess`" in {
      val List(c) = cpg.call(Operators.indexAccess).l
      c.code shouldBe "names[0]"
      c.typeFullName shouldBe "kotlin.Int"
      c.lineNumber shouldBe Some(3)
      c.columnNumber shouldBe Some(9)
      c.methodFullName shouldBe Operators.indexAccess
    }
  }
}