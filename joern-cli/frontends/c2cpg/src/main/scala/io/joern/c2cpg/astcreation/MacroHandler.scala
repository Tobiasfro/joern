package io.joern.c2cpg.astcreation

import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.codepropertygraph.generated.nodes.{AstNodeNew, ExpressionNew, NewBlock, NewFieldIdentifier, NewNode}
import io.joern.x2cpg.Ast
import org.apache.commons.lang.StringUtils
import org.eclipse.cdt.core.dom.ast.{IASTMacroExpansionLocation, IASTNode, IASTPreprocessorMacroDefinition}
import org.eclipse.cdt.internal.core.model.ASTStringUtil
import org.eclipse.cdt.internal.core.parser.scanner.MacroArgumentExtractor

import scala.annotation.nowarn
import scala.collection.mutable

trait MacroHandler { this: AstCreator =>

  private val nodeOffsetMacroPairs: mutable.Stack[(Int, IASTPreprocessorMacroDefinition)] = {
    mutable.Stack.from(
      cdtAst.getNodeLocations.toList
        .collect { case exp: IASTMacroExpansionLocation =>
          (exp.asFileLocation().getNodeOffset, exp.getExpansion.getMacroDefinition)
        }
        .sortBy(_._1)
    )
  }

  /** For the given node, determine if it is expanded from a macro, and if so, create a Call node to represent the macro
    * invocation and attach `ast` as its child.
    */
  def asChildOfMacroCall(node: IASTNode, ast: Ast): Ast = {
    val matchingMacro = extractMatchingMacro(node)
    val macroCallAst  = matchingMacro.map { case (mac, args) => createMacroCallAst(ast, node, mac, args) }
    macroCallAst match {
      case Some(callAst) =>
        val newAst = ast.subTreeCopy(ast.root.get.asInstanceOf[AstNodeNew], argIndex = 1)
        // We need to wrap the copied AST as it may contain CPG nodes not being allowed
        // to be connected via AST edges under a CALL. E.g., LOCALs but only if its not already a BLOCK.
        val childAst = newAst.root match {
          case Some(_: NewBlock) =>
            newAst
          case _ =>
            val b = NewBlock().argumentIndex(1).typeFullName(registerType(Defines.voidTypeName))
            blockAst(b, List(newAst))
        }
        callAst.withChild(childAst)
      case None => ast
    }
  }

  /** For the given node, determine if it is expanded from a macro, and if so, find the first matching (offset, macro)
    * pair in nodeOffsetMacroPairs, removing non-matching elements from the start of nodeOffsetMacroPairs. Returns
    * (Some(macroDefinition, arguments)) if a macro definition matches and None otherwise.
    */
  private def extractMatchingMacro(node: IASTNode): Option[(IASTPreprocessorMacroDefinition, List[String])] = {
    val expansionLocations = expandedFromMacro(node).filterNot(isExpandedFrom(node.getParent, _))
    val nodeOffset         = node.getFileLocation.getNodeOffset
    var matchingMacro      = Option.empty[(IASTPreprocessorMacroDefinition, List[String])]

    expansionLocations.foreach { macroLocation =>
      while (matchingMacro.isEmpty && nodeOffsetMacroPairs.headOption.exists(_._1 <= nodeOffset)) {
        val (_, macroDefinition) = nodeOffsetMacroPairs.pop()
        val macroExpansionName   = ASTStringUtil.getSimpleName(macroLocation.getExpansion.getMacroDefinition.getName)
        val macroDefinitionName  = ASTStringUtil.getSimpleName(macroDefinition.getName)
        if (macroExpansionName == macroDefinitionName) {
          val arguments = new MacroArgumentExtractor(cdtAst, node.getFileLocation).getArguments
          matchingMacro = Option((macroDefinition, arguments))
        }
      }
    }

    matchingMacro
  }

  /** Determine whether `node` is expanded from the macro expansion at `loc`.
    */
  private def isExpandedFrom(node: IASTNode, loc: IASTMacroExpansionLocation): Boolean =
    expandedFromMacro(node).map(_.getExpansion.getMacroDefinition).contains(loc.getExpansion.getMacroDefinition)

  private def argumentTrees(arguments: List[String], ast: Ast): List[Option[Ast]] = {
    arguments.zipWithIndex.map { case (arg, i) =>
      val rootNode = argForCode(arg, ast)
      rootNode.map(x => ast.subTreeCopy(x.asInstanceOf[AstNodeNew], i + 1))
    }
  }

  private def argForCode(code: String, ast: Ast): Option[NewNode] = {
    val normalizedCode = code.replace(" ", "")
    if (normalizedCode == "") {
      None
    } else {
      ast.nodes.collectFirst {
        case x: ExpressionNew if !x.isInstanceOf[NewFieldIdentifier] && x.code == normalizedCode => x
      }
    }
  }

  /** Create an AST that represents a macro expansion as a call. The AST is rooted in a CALL node and contains sub trees
    * for arguments. These are also connected to the AST via ARGUMENT edges. We include line number information in the
    * CALL node that is picked up by the MethodStubCreator.
    */
  private def createMacroCallAst(
    ast: Ast,
    node: IASTNode,
    macroDef: IASTPreprocessorMacroDefinition,
    arguments: List[String]
  ): Ast = {
    val name    = ASTStringUtil.getSimpleName(macroDef.getName)
    val code    = node.getRawSignature.stripSuffix(";")
    val argAsts = argumentTrees(arguments, ast).map(_.getOrElse(Ast()))

    val callNode = newCallNode(
      node,
      StringUtils.normalizeSpace(name),
      StringUtils.normalizeSpace(fullName(macroDef, argAsts)),
      DispatchTypes.INLINED
    ).code(code).typeFullName(typeFor(node))

    callAst(callNode, argAsts)
  }

  /** Create a full name field that encodes line information that can be picked up by the MethodStubCreator in order to
    * create a METHOD node with the correct location information.
    */
  private def fullName(macroDef: IASTPreprocessorMacroDefinition, argAsts: List[Ast]) = {
    val name      = ASTStringUtil.getSimpleName(macroDef.getName)
    val filename  = fileName(macroDef)
    val lineNo    = line(macroDef).getOrElse(-1)
    val lineNoEnd = lineEnd(macroDef).getOrElse(-1)
    s"$filename:$lineNo:$lineNoEnd:$name:${argAsts.size}"
  }

  /** The CDT utility method is unfortunately in a class that is marked as deprecated, however, this is because the CDT
    * team would like to discourage its use but at the same time does not plan to remove this code.
    */
  @nowarn
  def nodeSignature(node: IASTNode): String = {
    import org.eclipse.cdt.core.dom.ast.ASTSignatureUtil.getNodeSignature
    if (isExpandedFromMacro(node)) {
      val sig = getNodeSignature(node)
      if (sig.isEmpty) {
        node.getRawSignature
      } else {
        sig
      }
    } else {
      node.getRawSignature
    }
  }

  private def isExpandedFromMacro(node: IASTNode): Boolean = expandedFromMacro(node).nonEmpty

  private def expandedFromMacro(node: IASTNode): Option[IASTMacroExpansionLocation] = {
    val locations = node.getNodeLocations
    if (locations.nonEmpty) {
      node.getNodeLocations.headOption.collect { case x: IASTMacroExpansionLocation => x }
    } else {
      None
    }
  }

}
