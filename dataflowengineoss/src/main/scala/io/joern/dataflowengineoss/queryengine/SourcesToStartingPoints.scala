package io.joern.dataflowengineoss.queryengine

import io.joern.x2cpg.Defines
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.language.operatorextension.allAssignmentTypes
import org.slf4j.LoggerFactory
import overflowdb.traversal.Traversal

import java.util.concurrent.{ForkJoinPool, ForkJoinTask, RecursiveTask, RejectedExecutionException}
import scala.util.{Failure, Success, Try}

case class StartingPointWithSource(startingPoint: CfgNode, source: StoredNode)

object SourcesToStartingPoints {

  private val log = LoggerFactory.getLogger(SourcesToStartingPoints.getClass)

  def sourceTravsToStartingPoints[NodeType](sourceTravs: Traversal[NodeType]*): List[StartingPointWithSource] = {
    val fjp = ForkJoinPool.commonPool()
    try {
      fjp.invoke(new SourceTravsToStartingPointsTask(sourceTravs: _*)).distinct
    } catch {
      case e: RejectedExecutionException =>
        log.error("Unable to execute 'SourceTravsToStartingPoints` task", e); List()
    } finally {
      fjp.shutdown()
    }
  }

}

class SourceTravsToStartingPointsTask[NodeType](sourceTravs: Traversal[NodeType]*)
    extends RecursiveTask[List[StartingPointWithSource]] {

  private val log = LoggerFactory.getLogger(this.getClass)

  override def compute(): List[StartingPointWithSource] = {
    val sources: List[StoredNode] = sourceTravs
      .flatMap(_.toList)
      .collect { case n: StoredNode => n }
      .dedup
      .toList
      .sortBy(_.id)
    val tasks = sources.map(src => (src, new SourceToStartingPoints(src).fork()))
    tasks.flatMap { case (src, t: ForkJoinTask[List[CfgNode]]) =>
      Try(t.get()) match {
        case Failure(e)       => log.error("Unable to complete 'SourceToStartingPoints' task", e); List()
        case Success(sources) => sources.map(s => StartingPointWithSource(s, src))
      }
    }
  }
}

/** The code below deals with member variables, and specifically with the situation where literals that initialize
  * static members are passed to `reachableBy` as sources. In this case, we determine the first usages of this member in
  * each method, traversing the AST from left to right. This isn't fool-proof, e.g., goto-statements would be
  * problematic, but it works quite well in practice.
  */
class SourceToStartingPoints(src: StoredNode) extends RecursiveTask[List[CfgNode]] {

  private val cpg = Cpg(src.graph())

  override def compute(): List[CfgNode] = sourceToStartingPoints(src)

  private def sourceToStartingPoints(src: StoredNode): List[CfgNode] = {
    src match {
      case methodReturn: MethodReturn =>
        methodReturn.method.callIn.l
      case lit: Literal =>
        // `firstUsagesOfLHSIdentifiers` is required to handle children methods referencing the identifier this literal
        // is being passed to. Perhaps not the most sound as this doesn't handle re-assignment super well but it's
        // difficult to check the control flow of when the method ref might use the value
        val firstUsagesOfLHSIdentifiers =
          lit.inAssignment.argument(1).isIdentifier.refsTo.flatMap(identifiersFromCapturedScopes).l.distinctBy(_.method)
        List(lit) ++ usages(
          targetsToClassIdentifierPair(literalToInitializedMembers(lit))
        ) ++ firstUsagesOfLHSIdentifiers
      case member: Member =>
        usages(targetsToClassIdentifierPair(List(member)))
      case x: Declaration =>
        List(x).collectAll[CfgNode].toList ++ identifiersFromCapturedScopes(x)
      case x: Identifier =>
        List(x).collectAll[CfgNode].toList ++ x.refsTo.collectAll[Local].flatMap(sourceToStartingPoints)
      case x => List(x).collect { case y: CfgNode => y }
    }
  }

  private def identifiersFromCapturedScopes(i: Declaration): List[Identifier] =
    i.capturedByMethodRef.referencedMethod.ast.isIdentifier
      .nameExact(i.name)
      .sortBy(x => (x.lineNumber, x.columnNumber))
      .l

  private def usages(pairs: List[(TypeDecl, AstNode)]): List[CfgNode] = {
    pairs.flatMap { case (typeDecl, astNode) =>
      val nonConstructorMethods = methodsRecursively(typeDecl)
        .and(
          _.whereNot(_.nameExact(Defines.StaticInitMethodName, Defines.ConstructorMethodName)),
          // handle Python
          _.whereNot(_.name(".*<body>$"))
        )
        .l

      val usagesInSameClass =
        nonConstructorMethods.flatMap { m => firstUsagesOf(astNode, m, typeDecl) }

      val usagesInOtherClasses = cpg.method.flatMap { m =>
        m.fieldAccess
          .where(_.argument(1).isIdentifier.typeFullNameExact(typeDecl.fullName))
          .where { x =>
            astNode match {
              case identifier: Identifier =>
                x.argument(2).isFieldIdentifier.canonicalNameExact(identifier.name)
              case fieldIdentifier: FieldIdentifier =>
                x.argument(2).isFieldIdentifier.canonicalNameExact(fieldIdentifier.canonicalName)
              case _ => List()
            }
          }
          .takeWhile(notLeftHandOfAssignment)
          .headOption
      }.l
      usagesInSameClass ++ usagesInOtherClasses
    }
  }

  /** For given method, determine the first usage of the given expression.
    */
  private def firstUsagesOf(astNode: AstNode, m: Method, typeDecl: TypeDecl): List[Expression] = {
    astNode match {
      case member: Member =>
        usagesForName(member.name, m)
      case identifier: Identifier =>
        usagesForName(identifier.name, m)
      case fieldIdentifier: FieldIdentifier =>
        val fieldIdentifiers = m.ast.isFieldIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
        fieldIdentifiers
          .canonicalNameExact(fieldIdentifier.canonicalName)
          .inFieldAccess
          // TODO `isIdentifier` seems to limit us here
          .where(_.argument(1).isIdentifier.or(_.nameExact("this", "self"), _.typeFullNameExact(typeDecl.fullName)))
          .takeWhile(notLeftHandOfAssignment)
          .l
      case _ => List()
    }
  }

  private def usagesForName(name: String, m: Method): List[Expression] = {
    val identifiers      = m.ast.isIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
    val identifierUsages = identifiers.nameExact(name).takeWhile(notLeftHandOfAssignment).l
    val fieldIdentifiers = m.ast.isFieldIdentifier.sortBy(x => (x.lineNumber, x.columnNumber)).l
    val fieldAccessUsages = fieldIdentifiers.isFieldIdentifier
      .canonicalNameExact(name)
      .inFieldAccess
      .where(_.argument(1).codeExact("this", "self", m.typeDecl.name.head))
      .takeWhile(notLeftHandOfAssignment)
      .l
    (identifierUsages ++ fieldAccessUsages).headOption.toList
  }

  /** For a literal, determine if it is used in the initialization of any member variables. Return list of initialized
    * members. An initialized member is either an identifier or a field-identifier.
    */
  private def literalToInitializedMembers(lit: Literal): List[Expression] = {
    lit.inAssignment
      .or(
        _.method.nameExact(Defines.StaticInitMethodName, Defines.ConstructorMethodName, "__init__"),
        _.method.name(".*<body>.*")
      )
      .target
      .flatMap {
        case identifier: Identifier => List(identifier)
        case call: Call if call.name == Operators.fieldAccess =>
          call.ast.isFieldIdentifier.l
        case _ => List[Expression]()
      }
      .l
  }

  private def methodsRecursively(typeDecl: TypeDecl): List[Method] = {
    def methods(x: AstNode): List[Method] = {
      x match {
        case m: Method => m :: m.astMinusRoot.isMethod.flatMap(methods).l
        case _         => List()
      }
    }
    typeDecl.method.flatMap(methods).l
  }

  private def isTargetInAssignment(identifier: Identifier): List[Identifier] = {
    Traversal(identifier).argumentIndex(1).where(_.inAssignment).l
  }

  private def notLeftHandOfAssignment(x: Expression): Boolean = {
    !(x.argumentIndex == 1 && x.inCall.exists(y => allAssignmentTypes.contains(y.name)))
  }

  private def targetsToClassIdentifierPair(targets: List[AstNode]): List[(TypeDecl, AstNode)] = {
    targets.flatMap {
      case expr: Expression =>
        expr.method.typeDecl.map { typeDecl => (typeDecl, expr) }
      case member: Member =>
        member.typeDecl.map { typeDecl => (typeDecl, member) }
    }
  }

}
