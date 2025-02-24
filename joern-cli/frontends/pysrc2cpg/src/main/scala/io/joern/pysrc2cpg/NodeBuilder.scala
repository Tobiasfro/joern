package io.joern.pysrc2cpg

import io.joern.pysrc2cpg.PythonAstVisitor.{allBuiltinClasses, builtinPrefix, typingClassesV3, typingPrefix}
import io.joern.pythonparser.ast
import io.joern.x2cpg.Defines
import io.joern.x2cpg.utils.NodeBuilders
import io.shiftleft.codepropertygraph.generated.{DispatchTypes, EvaluationStrategies, nodes}
import overflowdb.BatchedUpdate.DiffGraphBuilder

class NodeBuilder(diffGraph: DiffGraphBuilder) {

  private def addNodeToDiff[T <: nodes.NewNode](node: T): T = {
    diffGraph.addNode(node)
    node
  }

  def callNode(code: String, name: String, dispatchType: String, lineAndColumn: LineAndColumn): nodes.NewCall = {
    val callNode = nodes
      .NewCall()
      .code(code)
      .name(name)
      .methodFullName(if (dispatchType == DispatchTypes.STATIC_DISPATCH) name else Defines.DynamicCallUnknownFallName)
      .dispatchType(dispatchType)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(callNode)
  }

  def typeNode(name: String, fullName: String): nodes.NewType = {
    val typeNode = nodes
      .NewType()
      .name(name)
      .fullName(fullName)
      .typeDeclFullName(fullName)
    addNodeToDiff(typeNode)
  }

  def typeDeclNode(
    name: String,
    fullName: String,
    fileName: String,
    inheritsFromFullNames: collection.Seq[String],
    lineAndColumn: LineAndColumn
  ): nodes.NewTypeDecl = {
    val typeDeclNode = nodes
      .NewTypeDecl()
      .name(name)
      .fullName(fullName)
      .isExternal(false)
      .filename(fileName)
      .inheritsFromTypeFullName(inheritsFromFullNames)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(typeDeclNode)
  }

  def typeRefNode(code: String, typeFullName: String, lineAndColumn: LineAndColumn): nodes.NewTypeRef = {
    val typeRefNode = nodes
      .NewTypeRef()
      .code(code)
      .typeFullName(typeFullName)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(typeRefNode)
  }

  def memberNode(name: String, dynamicTypeHintFullName: String): nodes.NewMember = {
    val memberNode = nodes
      .NewMember()
      .code(name)
      .name(name)
      .typeFullName(Constants.ANY)
      .dynamicTypeHintFullName(dynamicTypeHintFullName :: Nil)
    addNodeToDiff(memberNode)
  }

  def bindingNode(): nodes.NewBinding = {
    val bindingNode = nodes
      .NewBinding()
      .name("")
      .signature("")

    addNodeToDiff(bindingNode)
  }

  def methodNode(name: String, fullName: String, fileName: String, lineAndColumn: LineAndColumn): nodes.NewMethod = {
    val methodNode = nodes
      .NewMethod()
      .name(name)
      .fullName(fullName)
      .filename(fileName)
      .isExternal(false)
      .lineNumber(lineAndColumn.line)
      .lineNumberEnd(lineAndColumn.endLine)
      .columnNumber(lineAndColumn.column)
      .columnNumberEnd(lineAndColumn.endColumn)
    addNodeToDiff(methodNode)
  }

  def methodRefNode(name: String, fullName: String, lineAndColumn: LineAndColumn): nodes.NewMethodRef = {
    val methodRefNode = nodes
      .NewMethodRef()
      .code(name)
      .methodFullName(fullName)
      .typeFullName(fullName)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(methodRefNode)
  }

  def closureBindingNode(closureBindingId: String, closureOriginalName: String): nodes.NewClosureBinding = {
    val closureBindingNode = nodes
      .NewClosureBinding()
      .closureBindingId(Some(closureBindingId))
      .evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
      .closureOriginalName(Some(closureOriginalName))
    addNodeToDiff(closureBindingNode)
  }

  def methodParameterNode(
    name: String,
    isVariadic: Boolean,
    lineAndColumn: LineAndColumn,
    index: Option[Int] = None,
    typeHint: Option[ast.iexpr] = None
  ): nodes.NewMethodParameterIn = {
    val methodParameterNode = nodes
      .NewMethodParameterIn()
      .name(name)
      .code(name)
      .evaluationStrategy(EvaluationStrategies.BY_SHARING)
      .typeFullName(Constants.ANY)
      .isVariadic(isVariadic)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    index.foreach(idx => methodParameterNode.index(idx))
    methodParameterNode.dynamicTypeHintFullName(extractTypesFromHint(typeHint))
    addNodeToDiff(methodParameterNode)
  }

  def extractTypesFromHint(typeHint: Option[ast.iexpr] = None): Seq[String] =
    typeHint
      .collect {
        case n: ast.Name => n.id
        // TODO: Definitely a place for follow up handling of generics - currently only take the polymorphic type
        //  without type args. To see the type arguments, see ast.Subscript.slice
        case n: ast.Subscript if n.value.isInstanceOf[ast.Name] => n.value.asInstanceOf[ast.Name].id
      }
      .map { typeName =>
        if (allBuiltinClasses.contains(typeName)) s"$builtinPrefix$typeName"
        else if (typingClassesV3.contains(typeName)) s"$typingPrefix$typeName"
        else typeName
      }
      .toSeq

  def methodReturnNode(dynamicTypeHintFullName: Option[String], lineAndColumn: LineAndColumn): nodes.NewMethodReturn = {
    val methodReturnNode = NodeBuilders
      .methodReturnNode(Constants.ANY, dynamicTypeHintFullName, Some(lineAndColumn.line), Some(lineAndColumn.column))
      .evaluationStrategy(EvaluationStrategies.BY_SHARING)

    addNodeToDiff(methodReturnNode)
  }

  def returnNode(code: String, lineAndColumn: LineAndColumn): nodes.NewReturn = {
    val returnNode = nodes
      .NewReturn()
      .code(code)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)

    addNodeToDiff(returnNode)
  }

  def identifierNode(name: String, lineAndColumn: LineAndColumn): nodes.NewIdentifier = {
    val identifierNode = nodes
      .NewIdentifier()
      .code(name)
      .name(name)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(identifierNode)
  }

  def fieldIdentifierNode(name: String, lineAndColumn: LineAndColumn): nodes.NewFieldIdentifier = {
    val fieldIdentifierNode = nodes
      .NewFieldIdentifier()
      .code(name)
      .canonicalName(name)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(fieldIdentifierNode)
  }

  def numberLiteralNode(number: Int, lineAndColumn: LineAndColumn): nodes.NewLiteral = {
    numberLiteralNode(number.toString, lineAndColumn)
  }

  def numberLiteralNode(number: String, lineAndColumn: LineAndColumn): nodes.NewLiteral = {
    val literalNode = nodes
      .NewLiteral()
      .code(number)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(literalNode)
  }

  def stringLiteralNode(string: String, lineAndColumn: LineAndColumn): nodes.NewLiteral = {
    val literalNode = nodes
      .NewLiteral()
      .code(string)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(literalNode)
  }

  def blockNode(code: String, lineAndColumn: LineAndColumn): nodes.NewBlock = {
    val blockNode = nodes
      .NewBlock()
      .code(code)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(blockNode)
  }

  def controlStructureNode(
    code: String,
    controlStructureName: String,
    lineAndColumn: LineAndColumn
  ): nodes.NewControlStructure = {
    val controlStructureNode = nodes
      .NewControlStructure()
      .code(code)
      .controlStructureType(controlStructureName)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(controlStructureNode)
  }

  def localNode(name: String, closureBindingId: Option[String] = None): nodes.NewLocal = {
    val localNode = nodes
      .NewLocal()
      .code(name)
      .name(name)
      .closureBindingId(closureBindingId)
      .typeFullName(Constants.ANY)
    addNodeToDiff(localNode)
  }

  def fileNode(fileName: String): nodes.NewFile = {
    val fileNode = nodes
      .NewFile()
      .name(fileName)
    addNodeToDiff(fileNode)
  }

  def namespaceBlockNode(name: String, fullName: String, fileName: String): nodes.NewNamespaceBlock = {
    val namespaceBlockNode = nodes
      .NewNamespaceBlock()
      .name(name)
      .fullName(fullName)
      .filename(fileName)
    addNodeToDiff(namespaceBlockNode)
  }

  def modifierNode(modifierType: String): nodes.NewModifier = {
    val modifierNode = nodes
      .NewModifier()
      .modifierType(modifierType)
    addNodeToDiff(modifierNode)
  }

  def metaNode(language: String, version: String): nodes.NewMetaData = {
    val metaNode = nodes
      .NewMetaData()
      .language(language)
      .version(version)
    addNodeToDiff(metaNode)
  }

  def unknownNode(code: String, parserTypeName: String, lineAndColumn: LineAndColumn): nodes.NewUnknown = {
    val unknownNode = nodes
      .NewUnknown()
      .code(code)
      .parserTypeName(parserTypeName)
      .typeFullName(Constants.ANY)
      .lineNumber(lineAndColumn.line)
      .columnNumber(lineAndColumn.column)
    addNodeToDiff(unknownNode)
  }
}
