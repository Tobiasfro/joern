package io.joern.kotlin2cpg.passes

import io.joern.kotlin2cpg.ast.Nodes._
import io.joern.kotlin2cpg.Constants
import io.joern.kotlin2cpg.KtFileWithMeta
import io.joern.kotlin2cpg.psi.PsiUtils._
import io.joern.kotlin2cpg.types.{CallKinds, TypeConstants, TypeInfoProvider}
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.codepropertygraph.generated._
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal
import io.joern.x2cpg.{Ast, Defines}
import io.joern.x2cpg.datastructures.Stack._
import io.joern.x2cpg.utils.NodeBuilders.methodReturnNode

import java.util.UUID.randomUUID
import org.jetbrains.kotlin.psi._
import org.jetbrains.kotlin.lexer.{KtToken, KtTokens}
import overflowdb.traversal.iterableToTraversal

import scala.annotation.unused
import scala.jdk.CollectionConverters._

trait KtPsiToAst {
  this: AstCreator =>

  def astForFile(fileWithMeta: KtFileWithMeta)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val ktFile = fileWithMeta.f

    val importDirectives = ktFile.getImportList.getImports.asScala
    val importAsts       = importDirectives.toList.map(astForImportDirective)
    val namespaceBlocksForImports =
      for {
        node <- importAsts.flatMap(_.root.collectAll[NewImport])
        name = getName(node)
      } yield Ast(namespaceBlockNode(name, name, relativizedPath))

    val packageName = ktFile.getPackageFqName.toString
    val node =
      if (packageName == Constants.root)
        namespaceBlockNode(
          NamespaceTraversal.globalNamespaceName,
          NamespaceTraversal.globalNamespaceName,
          relativizedPath
        )
      else {
        val name = packageName.split("\\.").lastOption.getOrElse("")
        namespaceBlockNode(name, packageName, relativizedPath)
      }

    methodAstParentStack.push(node)
    val declarationsAsts = ktFile.getDeclarations.asScala.flatMap(astsForDeclaration)
    val fileNode         = NewFile().name(fileWithMeta.relativizedPath)
    val lambdaTypeDecls =
      lambdaBindingInfoQueue.flatMap(_.edgeMeta.collect { case (node: NewTypeDecl, _, _) => Ast(node) })
    methodAstParentStack.pop()

    val namespaceBlockAst = Ast(node).withChildren(importAsts ++ declarationsAsts ++ lambdaAstQueue ++ lambdaTypeDecls)
    Ast(fileNode).withChildren(namespaceBlockAst :: namespaceBlocksForImports)
  }

  def astForImportDirective(directive: KtImportDirective): Ast = {
    val isWildcard = directive.getLastChild.getText == Constants.wildcardImportName || directive.getImportedName == null
    val node =
      NewImport()
        .isWildcard(isWildcard)
        .isExplicit(true)
        .importedEntity(directive.getImportPath.getPathStr)
        .code(s"${Constants.importKeyword} ${directive.getImportPath.getPathStr}")
        .lineNumber(line(directive))
        .columnNumber(column(directive))
    Ast(node)
  }

  @unused
  def astForPackageDeclaration(packageName: String): Ast = {
    val node =
      if (packageName == Constants.root)
        namespaceBlockNode(
          NamespaceTraversal.globalNamespaceName,
          NamespaceTraversal.globalNamespaceName,
          relativizedPath
        )
      else {
        val name = packageName.split("\\.").lastOption.getOrElse("")
        namespaceBlockNode(name, packageName, relativizedPath)
      }
    Ast(node)
  }

  def astsForDeclaration(decl: KtDeclaration)(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    decl match {
      case c: KtClass                => astsForClassOrObject(c)
      case o: KtObjectDeclaration    => astsForClassOrObject(o)
      case n: KtNamedFunction        => astsForMethod(n)
      case t: KtTypeAlias            => Seq(astForTypeAlias(t))
      case s: KtSecondaryConstructor => Seq(astForUnknown(s, None))
      case p: KtProperty             => Seq(astForUnknown(p, None)) // TODO: these are globals, represent them correctly
      case unhandled =>
        logger.error(
          s"Unknown declaration type encountered with text `${unhandled.getText}` and class `${unhandled.getClass}`!"
        )
        Seq()
    }
  }

  def astForTypeAlias(typeAlias: KtTypeAlias)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val node = typeDeclNode(
      typeAlias.getName,
      registerType(typeInfoProvider.fullName(typeAlias, TypeConstants.any)),
      relativizedPath,
      Seq(),
      Option(registerType(typeInfoProvider.aliasTypeFullName(typeAlias, TypeConstants.any))),
      line(typeAlias),
      column(typeAlias)
    )
    Ast(node)
  }

  def componentNMethodAsts(typeDecl: NewTypeDecl, parameters: Seq[KtParameter])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Seq[Ast] = {
    parameters.zipWithIndex.map { case (valueParam, idx) =>
      val typeFullName = registerType(typeInfoProvider.typeFullName(valueParam, TypeConstants.any))

      val thisParam = methodParameterNode(Constants.this_, typeDecl.fullName).order(0)
      val thisIdentifier =
        identifierNode(Constants.this_, typeDecl.fullName).dynamicTypeHintFullName(Seq(typeDecl.fullName))
      val thisAst = Ast(thisIdentifier).withRefEdge(thisIdentifier, thisParam)

      val fieldIdentifier = fieldIdentifierNode(valueParam.getName, line(valueParam), column(valueParam))
      val fieldAccessCall =
        operatorCallNode(Operators.fieldAccess, s"${Constants.this_}.${valueParam.getName}", Option(typeFullName))
      val fieldAccessCallAst = callAst(fieldAccessCall, List(thisAst, Ast(fieldIdentifier)))
      val methodBlockAst = blockAst(
        blockNode(fieldAccessCall.code, typeFullName),
        List(returnAst(returnNode(Constants.ret), List(fieldAccessCallAst)))
      )

      val componentIdx  = idx + 1
      val componentName = s"${Constants.componentNPrefix}$componentIdx"
      val signature     = s"$typeFullName()"
      val fullName      = s"${typeDecl.fullName}.$componentName:$signature"
      methodAst(
        methodNode(componentName, fullName, signature, relativizedPath),
        Seq(thisParam),
        methodBlockAst,
        methodReturnNode(typeFullName, None, None, None)
      )
    }
  }

  def secondaryCtorAsts(ctors: Seq[KtSecondaryConstructor], classFullName: String, primaryCtorCall: NewCall)(implicit
    typeInfoProvider: TypeInfoProvider
  ): Seq[Ast] = {
    ctors.map { ctor =>
      val constructorParams     = ctor.getValueParameters.asScala.toList
      val defaultSignature      = typeInfoProvider.anySignature(constructorParams)
      val defaultFullName       = s"$classFullName.${TypeConstants.initPrefix}:$defaultSignature"
      val (fullName, signature) = typeInfoProvider.fullNameWithSignature(ctor, (defaultFullName, defaultSignature))
      val secondaryCtorMethodNode =
        methodNode(Constants.init, fullName, signature, relativizedPath, line(ctor), column(ctor))
      scope.pushNewScope(secondaryCtorMethodNode)

      val ctorThisParam = methodParameterNode(Constants.this_, classFullName)
        .dynamicTypeHintFullName(Seq(classFullName))
        .order(0)
      scope.addToScope(Constants.this_, ctorThisParam)

      val constructorParamsAsts = Seq(Ast(ctorThisParam)) ++
        withIndex(constructorParams) { (p, idx) => astForParameter(p, idx) }
      val ctorMethodBlockAst =
        astsForBlock(ctor.getBodyExpression, None, preStatements = Option(Seq(Ast(primaryCtorCall))))
      scope.popScope()

      val ctorMethodReturnNode =
        methodReturnNode(TypeConstants.void, None, Option(line(ctor)), Option(column(ctor)))
      val ctorParams = constructorParamsAsts.flatMap(_.root.collectAll[NewMethodParameterIn])

      // TODO: see if necessary to take the other asts for the ctorMethodBlock
      methodAst(secondaryCtorMethodNode, ctorParams, ctorMethodBlockAst.head, ctorMethodReturnNode)
    }
  }

  def memberSetCallAst(param: KtParameter, classFullName: String)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val typeFullName       = registerType(typeInfoProvider.typeFullName(param, TypeConstants.any))
    val paramName          = param.getName
    val paramIdentifier    = identifierNode(paramName, typeFullName)
    val paramIdentifierAst = astWithRefEdgeMaybe(paramName, paramIdentifier)
    val thisIdentifier     = identifierNode(Constants.this_, classFullName).dynamicTypeHintFullName(Set(classFullName))
    val thisAst            = astWithRefEdgeMaybe(Constants.this_, thisIdentifier)

    val fieldIdentifier = fieldIdentifierNode(paramName)
    val fieldAccessCall =
      operatorCallNode(Operators.fieldAccess, s"${Constants.this_}.$paramName", Option(typeFullName))
    val fieldAccessCallAst = callAst(fieldAccessCall, List(thisAst, Ast(fieldIdentifier)))

    val assignmentNode =
      operatorCallNode(Operators.assignment, s"${fieldAccessCall.code} = ${paramIdentifier.code}")
    callAst(assignmentNode, List(fieldAccessCallAst, paramIdentifierAst))
  }

  def astsForClassOrObject(ktClass: KtClassOrObject)(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val className = ktClass.getName
    val explicitFullName = {
      val fqName = ktClass.getContainingKtFile.getPackageFqName.toString
      s"$fqName.$className"
    }
    val classFullName = registerType(typeInfoProvider.fullName(ktClass, explicitFullName))
    val explicitBaseTypeFullNames = ktClass.getSuperTypeListEntries.asScala
      .map(_.getTypeAsUserType)
      .collect { case t if t != null => t.getText }
      .map { typ => typeInfoProvider.typeFromImports(typ, ktClass.getContainingKtFile).getOrElse(typ) }
      .toList

    val baseTypeFullNames    = typeInfoProvider.inheritanceTypes(ktClass, explicitBaseTypeFullNames)
    val outBaseTypeFullNames = Option(baseTypeFullNames).filter(_.nonEmpty).getOrElse(Seq(TypeConstants.javaLangObject))
    val typeDecl = typeDeclNode(
      className,
      classFullName,
      relativizedPath,
      outBaseTypeFullNames,
      None,
      line(ktClass),
      column(ktClass)
    )
    scope.pushNewScope(typeDecl)

    val constructorParams = ktClass.getPrimaryConstructorParameters.asScala.toList
    val defaultSignature = Option(ktClass.getPrimaryConstructor)
      .map { _ => typeInfoProvider.anySignature(constructorParams) }
      .getOrElse(s"${TypeConstants.void}()")
    val defaultFullName = s"$classFullName.${TypeConstants.initPrefix}:$defaultSignature"
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(ktClass.getPrimaryConstructor, (defaultFullName, defaultSignature))
    val primaryCtorMethodNode = methodNode(
      TypeConstants.initPrefix,
      fullName,
      signature,
      relativizedPath,
      line(ktClass.getPrimaryConstructor),
      column(ktClass.getPrimaryConstructor)
    )
    val ctorThisParam =
      methodParameterNode(Constants.this_, classFullName)
        .dynamicTypeHintFullName(Seq(classFullName))
        .order(0)
    scope.addToScope(Constants.this_, ctorThisParam)

    val constructorParamsAsts = Seq(Ast(ctorThisParam)) ++ withIndex(constructorParams) { (p, idx) =>
      astForParameter(p, idx)
    }

    val memberSetCalls = constructorParams.collect {
      case ctorParam if ctorParam.hasValOrVar =>
        memberSetCallAst(ctorParam, classFullName)
    }

    val anonymousInitExpressions = ktClass.getAnonymousInitializers.asScala
    val anonymousInitAsts        = anonymousInitExpressions.flatMap(astsForExpression(_, None))

    val constructorMethodReturn = methodReturnNode(
      TypeConstants.void,
      None,
      Option(line(ktClass.getPrimaryConstructor)),
      Option(column(ktClass.getPrimaryConstructor))
    )
    val constructorAst = methodAst(
      primaryCtorMethodNode,
      constructorParamsAsts.flatMap(_.root.collectAll[NewMethodParameterIn]),
      blockAst(blockNode("", TypeConstants.void), memberSetCalls ++ anonymousInitAsts),
      constructorMethodReturn
    )
    val node = bindingNode(primaryCtorMethodNode.name, primaryCtorMethodNode.signature, primaryCtorMethodNode.fullName)
    val ctorBindingInfo =
      BindingInfo(node, List((typeDecl, node, EdgeTypes.BINDS), (node, primaryCtorMethodNode, EdgeTypes.REF)))

    val membersFromPrimaryCtorAsts = ktClass.getPrimaryConstructorParameters.asScala.toList.collect {
      case param if param.hasValOrVar =>
        val typeFullName = registerType(typeInfoProvider.parameterType(param, TypeConstants.any))
        Ast(memberNode(param.getName, typeFullName, line(param), column(param)))
    }

    val primaryCtorCall =
      callNode(
        TypeConstants.initPrefix,
        primaryCtorMethodNode.name,
        primaryCtorMethodNode.fullName,
        primaryCtorMethodNode.signature,
        constructorMethodReturn.typeFullName,
        DispatchTypes.STATIC_DISPATCH
      )

    val secondaryConstructorAsts =
      secondaryCtorAsts(ktClass.getSecondaryConstructors.asScala.toSeq, classFullName, primaryCtorCall)
    val _componentNMethodAsts = ktClass match {
      case typedExpr: KtClass if typedExpr.isData =>
        componentNMethodAsts(typeDecl, ktClass.getPrimaryConstructor.getValueParameters.asScala.toSeq)
      case _ => Seq()
    }
    val componentNBindingsInfo = _componentNMethodAsts.flatMap(_.root.collectAll[NewMethod]).map { methodNode =>
      val node = bindingNode(methodNode.name, methodNode.signature, methodNode.fullName)
      BindingInfo(node, List((typeDecl, node, EdgeTypes.BINDS), (node, methodNode, EdgeTypes.REF)))
    }

    val classDeclarations = Option(ktClass.getBody)
      .map(_.getDeclarations.asScala.filterNot(_.isInstanceOf[KtNamedFunction]))
      .getOrElse(List())
    val memberAsts = classDeclarations.toSeq.map(astForMember)
    val innerTypeDeclAsts =
      classDeclarations.toSeq
        .collectAll[KtClassOrObject]
        .filterNot(typeInfoProvider.isCompanionObject)
        .map(astsForDeclaration(_))
        .flatten

    val classFunctions = Option(ktClass.getBody)
      .map(_.getFunctions.asScala.collect { case f: KtNamedFunction => f })
      .getOrElse(List())
    val methodAsts = classFunctions.toSeq.flatMap { classFn =>
      astsForMethod(classFn, needsThisParameter = true, withVirtualModifier = true)
    }
    val bindingsInfo = methodAsts.flatMap(_.root.collectAll[NewMethod]).map { _methodNode =>
      val node = bindingNode(_methodNode.name, _methodNode.signature, _methodNode.fullName)
      BindingInfo(node, List((typeDecl, node, EdgeTypes.BINDS), (node, _methodNode, EdgeTypes.REF)))
    }

    val children = methodAsts ++ List(constructorAst) ++ membersFromPrimaryCtorAsts ++ secondaryConstructorAsts ++
      _componentNMethodAsts.toList ++ memberAsts
    val ast = Ast(typeDecl).withChildren(children)

    (List(ctorBindingInfo) ++ bindingsInfo ++ componentNBindingsInfo).foreach(bindingInfoQueue.prepend)

    val finalAst = if (typeInfoProvider.isCompanionObject(ktClass)) {
      val companionMemberTypeFullName = ktClass.getParent.getParent match {
        case c: KtClassOrObject => typeInfoProvider.typeFullName(c, TypeConstants.any)
        case _                  => TypeConstants.any
      }
      registerType(companionMemberTypeFullName)

      val companionObjectMember = memberNode(Constants.companionObjectMemberName, companionMemberTypeFullName)
      ast.withChild(Ast(companionObjectMember))
    } else {
      ast
    }
    val companionObjectAsts = ktClass.getCompanionObjects.asScala.flatMap(astsForClassOrObject)
    scope.popScope()

    Seq(finalAst) ++ companionObjectAsts ++ innerTypeDeclAsts
  }

  def astsForMethod(ktFn: KtNamedFunction, needsThisParameter: Boolean = false, withVirtualModifier: Boolean = false)(
    implicit typeInfoProvider: TypeInfoProvider
  ): Seq[Ast] = {
    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(ktFn, ("", ""))
    val _methodNode = methodNode(
      ktFn.getName,
      fullName,
      signature,
      relativizedPath,
      line(ktFn),
      column(ktFn),
      lineEnd(ktFn),
      columnEnd(ktFn)
    )
    scope.pushNewScope(_methodNode)
    methodAstParentStack.push(_methodNode)

    val thisParameterMaybe = if (needsThisParameter) {
      val typeDeclFullName = registerType(typeInfoProvider.containingTypeDeclFullName(ktFn, TypeConstants.any))
      val node =
        methodParameterNode(Constants.this_, typeDeclFullName, line(ktFn), column(ktFn))
          .dynamicTypeHintFullName(Seq(typeDeclFullName))
          .order(0)
      scope.addToScope(Constants.this_, node)
      Option(node)
    } else None

    val parameters = thisParameterMaybe.map(List(_)).getOrElse(List()) ++
      withIndex(ktFn.getValueParameters.asScala.toSeq) { (p, idx) => astForParameter(p, idx) }
        .flatMap(_.root.collectAll[NewMethodParameterIn])
    val bodyAsts = Option(ktFn.getBodyBlockExpression) match {
      case Some(bodyBlockExpression) => astsForBlock(bodyBlockExpression, None)
      case None =>
        val bodyBlock = blockNode("", "")
        Option(ktFn.getBodyExpression)
          .map { expr => Seq(blockAst(bodyBlock, astsForExpression(expr, None).toList)) }
          .getOrElse(Seq(blockAst(bodyBlock, List())))
    }
    methodAstParentStack.pop()
    scope.popScope()

    val bodyAst           = bodyAsts.head
    val otherBodyAsts     = bodyAsts.drop(1)
    val explicitTypeName  = Option(ktFn.getTypeReference).map(_.getText).getOrElse(TypeConstants.any)
    val typeFullName      = registerType(typeInfoProvider.returnType(ktFn, explicitTypeName))
    val _methodReturnNode = methodReturnNode(typeFullName, None, Option(line(ktFn)), Option(column(ktFn)))

    val modifierAsts =
      if (withVirtualModifier) Seq(Ast(modifierNode(ModifierTypes.VIRTUAL)))
      else Seq()
    Seq(
      methodAst(_methodNode, parameters, bodyAst, _methodReturnNode)
        .withChildren(modifierAsts)
        .withChildren(otherBodyAsts)
    )
  }

  def astsForBlock(
    expr: KtBlockExpression,
    argIdx: Option[Int],
    pushToScope: Boolean = true,
    localsForCaptures: List[NewLocal] = List(),
    implicitReturnAroundLastStatement: Boolean = false,
    preStatements: Option[Seq[Ast]] = None
  )(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node = withArgumentIndex(
      blockNode(expr.getStatements.asScala.map(_.getText).mkString("\n"), typeFullName, line(expr), column(expr)),
      argIdx
    )
    if (pushToScope) scope.pushNewScope(node)
    val statements = expr.getStatements.asScala.toSeq.filter { stmt =>
      !stmt.isInstanceOf[KtNamedFunction] && !stmt.isInstanceOf[KtClassOrObject]
    }
    val declarations = expr.getStatements.asScala.toSeq.collect {
      case fn: KtNamedFunction         => fn
      case classOrObj: KtClassOrObject => classOrObj
    }
    val declarationAsts          = declarations.flatMap(astsForDeclaration)
    val allStatementsButLast     = statements.dropRight(1)
    val allStatementsButLastAsts = allStatementsButLast.map(astsForExpression(_, None)).flatten

    val lastStatementAsts =
      if (implicitReturnAroundLastStatement && statements.nonEmpty) {
        val _returnNode = returnNode(Constants.retCode, line(statements.last), column(statements.last))
        Seq(returnAst(_returnNode, astsForExpression(statements.last, Some(1))))
      } else if (statements.nonEmpty) astsForExpression(statements.last, None)
      else Seq()

    if (pushToScope) scope.popScope()
    Seq(
      blockAst(
        node,
        localsForCaptures.map(Ast(_)) ++ preStatements.getOrElse(Seq()) ++ allStatementsButLastAsts ++ lastStatementAsts
      )
    ) ++ declarationAsts
  }

  def astForReturnExpression(expr: KtReturnExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val children = astsForExpression(expr.getReturnedExpression, None)
    returnAst(returnNode(expr.getText, line(expr), column(expr)), children.toList)
  }

  def astForIsExpression(expr: KtIsExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val args = astsForExpression(expr.getLeftHandSide, None) ++
      Seq(astForTypeReference(expr.getTypeReference, None, argName))
    val node = operatorCallNode(Operators.is, expr.getText, None, line(expr), column(expr))
    callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args.toList)
  }

  def astForBinaryExprWithTypeRHS(expr: KtBinaryExpressionWithTypeRHS, argIdx: Option[Int], argName: Option[String])(
    implicit typeInfoProvider: TypeInfoProvider
  ): Ast = {
    registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val args = astsForExpression(expr.getLeft, None) ++ Seq(astForTypeReference(expr.getRight, None, None))
    val node = operatorCallNode(Operators.cast, expr.getText, None, line(expr), column(expr))
    callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args.toList)
  }

  def astForTypeReference(expr: KtTypeReference, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.typeFullName(expr, TypeConstants.any))
    val node         = typeRefNode(expr.getText, typeFullName, line(expr), column(expr))
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
  }

  def astForSuperExpression(expr: KtSuperExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node = withArgumentName(
      withArgumentIndex(identifierNode(expr.getText, typeFullName, line(expr), column(expr)), argIdx),
      argName
    )
    astWithRefEdgeMaybe(expr.getText, node)
  }

  def astForThisExpression(expr: KtThisExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node = withArgumentName(
      withArgumentIndex(identifierNode(expr.getText, typeFullName, line(expr), column(expr)), argIdx),
      argName
    )
    astWithRefEdgeMaybe(expr.getText, node)
  }

  def astForClassLiteral(expr: KtClassLiteralExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(expr, ("", "")) // TODO: fix the fallback names
    val typeFullName          = registerType(typeInfoProvider.expressionType(expr, TypeConstants.javaLangObject))
    val node = callNode(
      expr.getText,
      TypeConstants.classLiteralReplacementMethodName,
      fullName,
      signature,
      typeFullName,
      DispatchTypes.STATIC_DISPATCH,
      line(expr),
      column(expr)
    )
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
  }

  def astForLambda(expr: KtLambdaExpression, argIdx: Option[Int])(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(expr, lambdaKeyPool)
    val lambdaMethodNode =
      methodNode(Constants.lambdaName, fullName, signature, relativizedPath, line(expr), column(expr))

    case class NodeContext(node: NewNode, name: String, typeFullName: String)
    val closureBindingEntriesForCaptured = scope
      .pushClosureScope(lambdaMethodNode)
      .collect {
        case node: NewMethodParameterIn => NodeContext(node, node.name, node.typeFullName)
        case node: NewLocal             => NodeContext(node, node.name, node.typeFullName)
        case node: NewMember            => NodeContext(node, node.name, node.typeFullName)
      }
      .map { capturedNodeContext =>
        // TODO: remove the randomness here, two CPGs created from the same codebase should be the same
        val closureBindingId   = randomUUID().toString
        val closureBindingNode = closureBinding(closureBindingId, capturedNodeContext.name)
        (closureBindingNode, capturedNodeContext)
      }

    val localsForCaptured = closureBindingEntriesForCaptured.map { case (closureBindingNode, capturedNodeContext) =>
      val node =
        localNode(capturedNodeContext.name, capturedNodeContext.typeFullName, closureBindingNode.closureBindingId)
      scope.addToScope(capturedNodeContext.name, node)
      node
    }
    val parametersAsts = typeInfoProvider.implicitParameterName(expr) match {
      case Some(implicitParamName) =>
        val node = methodParameterNode(implicitParamName, TypeConstants.any)
        scope.addToScope(implicitParamName, node)
        Seq(Ast(node))
      case None =>
        withIndex(expr.getValueParameters.asScala.toSeq) { (p, idx) =>
          astForParameter(p, idx)
        }
    }

    val lastChildNotReturnExpression = !expr.getBodyExpression.getLastChild.isInstanceOf[KtReturnExpression]
    val needsReturnExpression =
      lastChildNotReturnExpression && !typeInfoProvider.hasApplyOrAlsoScopeFunctionParent(expr)
    val bodyAsts = Option(expr.getBodyExpression)
      .map(
        astsForBlock(
          _,
          None,
          pushToScope = false,
          localsForCaptured,
          implicitReturnAroundLastStatement = needsReturnExpression
        )
      )
      .getOrElse(Seq(Ast(NewBlock())))

    val returnTypeFullName     = registerType(typeInfoProvider.returnTypeFullName(expr))
    val lambdaTypeDeclFullName = fullName.split(":").head

    val bodyAst = bodyAsts.head
    val lambdaMethodAst = methodAst(
      lambdaMethodNode,
      parametersAsts.flatMap(_.root.collectAll[NewMethodParameterIn]),
      bodyAst,
      methodReturnNode(returnTypeFullName, None, Some(line(expr)), Some(column(expr)))
    ).withChild(Ast(modifierNode(ModifierTypes.VIRTUAL)))

    val _methodRefNode =
      withArgumentIndex(methodRefNode(expr.getText, fullName, lambdaTypeDeclFullName, line(expr), column(expr)), argIdx)

    val lambdaTypeDecl = typeDeclNode(
      Constants.lambdaTypeDeclName,
      lambdaTypeDeclFullName,
      relativizedPath,
      Seq(registerType(s"${TypeConstants.kotlinFunctionXPrefix}${expr.getValueParameters.size}"))
    )

    val lambdaBinding = bindingNode(Constants.lambdaBindingName, signature, lambdaMethodNode.fullName)
    val bindingInfo = BindingInfo(
      lambdaBinding,
      Seq((lambdaTypeDecl, lambdaBinding, EdgeTypes.BINDS), (lambdaBinding, lambdaMethodNode, EdgeTypes.REF))
    )
    scope.popScope()
    val closureBindingDefs = closureBindingEntriesForCaptured.collect { case (closureBinding, node) =>
      ClosureBindingDef(closureBinding, _methodRefNode, node.node)
    }
    closureBindingDefs.foreach(closureBindingDefQueue.prepend)
    lambdaBindingInfoQueue.prepend(bindingInfo)
    lambdaAstQueue.prepend(lambdaMethodAst)
    Ast(_methodRefNode)
  }

  def astForArrayAccess(expression: KtArrayAccessExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val arrayExpr     = expression.getArrayExpression
    val typeFullName  = registerType(typeInfoProvider.expressionType(expression, TypeConstants.any))
    val identifier    = identifierNode(arrayExpr.getText, typeFullName, line(arrayExpr), column(arrayExpr))
    val identifierAst = astWithRefEdgeMaybe(arrayExpr.getText, identifier)
    val astsForIndexExpr = expression.getIndexExpressions.asScala.zipWithIndex.map { case (expr, idx) =>
      astsForExpression(expr, Option(idx + 1))
    }.flatten
    val callNode =
      operatorCallNode(
        Operators.indexAccess,
        expression.getText,
        Option(typeFullName),
        line(expression),
        column(expression)
      )
    callAst(withArgumentName(withArgumentIndex(callNode, argIdx), argName), List(identifierAst) ++ astsForIndexExpr)
  }

  def astForPostfixExpression(expr: KtPostfixExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val operatorType = ktTokenToOperator(forPostfixExpr = true).applyOrElse(
      KtPsiUtil.getOperationToken(expr),
      { (token: KtToken) =>
        logger.warn(s"Unsupported token type encountered: $token")
        Constants.unknownOperator
      }
    )
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val args = List(astsForExpression(expr.getBaseExpression, None).headOption.getOrElse(Ast()))
      .filterNot(_.root == null)
    val node = operatorCallNode(operatorType, expr.getText, Option(typeFullName), line(expr), column(expr))
    callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args)
  }

  def astForPrefixExpression(expr: KtPrefixExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val operatorType = ktTokenToOperator(forPostfixExpr = false).applyOrElse(
      KtPsiUtil.getOperationToken(expr),
      { (token: KtToken) =>
        logger.warn(s"Unsupported token type encountered: $token")
        Constants.unknownOperator
      }
    )
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val args = List(astsForExpression(expr.getBaseExpression, None).headOption.getOrElse(Ast()))
      .filterNot(_.root == null)
    val node = operatorCallNode(operatorType, expr.getText, Option(typeFullName), line(expr), column(expr))
    callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args)
  }

  /*
   _______ example lowering _________
  | -> val (one, two) = makeA("AMESSAGE")
  | -> LOCAL one
  | -> LOCAL two
  | -> LOCAL tmp
  | -> tmp = makeA("AMESSAGE")
  | -> CALL one = tmp.component1()
  | -> CALL two = tmp.component2()
  |__________________________________
   */
  private def astsForDestructuringDeclarationWithNonCtorCallRHS(
    expr: KtDestructuringDeclaration
  )(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val initExpr             = expr.getInitializer
    val destructuringEntries = nonUnderscoreDestructuringEntries(expr)
    val localsForEntries = destructuringEntries.map { entry =>
      val typeFullName = registerType(typeInfoProvider.typeFullName(entry, TypeConstants.any))
      Ast(localNode(entry.getName, typeFullName, None, line(entry), column(entry)))
    }

    val callRhsTypeFullName = registerType(typeInfoProvider.expressionType(initExpr, Defines.UnresolvedNamespace))
    val tmpName             = s"${Constants.tmpLocalPrefix}${tmpKeyPool.next}"
    val localForTmpNode     = localNode(tmpName, callRhsTypeFullName)
    scope.addToScope(localForTmpNode.name, localForTmpNode)

    val assignmentLhsNode = identifierNode(tmpName, localForTmpNode.typeFullName, line(expr), column(expr))
    val assignmentLhsAst  = Ast(assignmentLhsNode).withRefEdge(assignmentLhsNode, localForTmpNode)

    val assignmentNode   = operatorCallNode(Operators.assignment, s"$tmpName = ${initExpr.getText}", None)
    val assignmentRhsAst = astsForExpression(initExpr, None).head
    val assignmentAst    = callAst(assignmentNode, List(assignmentLhsAst, assignmentRhsAst))
    registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))

    val assignmentsForEntries = destructuringEntries.zipWithIndex.map { case (entry, idx) =>
      assignmentAstForDestructuringEntry(entry, localForTmpNode.name, localForTmpNode.typeFullName, idx + 1)
    }
    localsForEntries ++ Seq(Ast(localForTmpNode)) ++
      Seq(assignmentAst) ++ assignmentsForEntries
  }

  /*
   _______ example lowering _________
  | -> val (one, two) = Person("a", "b")
  | -> LOCAL one
  | -> LOCAL two
  | -> LOCAL tmp
  | -> tmp = alloc
  | -> tmp.<init>
  | -> CALL one = tmp.component1()
  | -> CALL two = tmp.component2()
  |__________________________________
   */
  private def astsForDestructuringDeclarationWithCtorRHS(
    expr: KtDestructuringDeclaration
  )(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val typedInit = Option(expr.getInitializer).collect { case e: KtCallExpression => e }
    if (typedInit.isEmpty) {
      logger.warn(s"Unhandled case for destructuring declaration: `${expr.getText}`.")
      return Seq()
    }
    val ctorCall = typedInit.get

    val destructuringEntries = nonUnderscoreDestructuringEntries(expr)
    val localsForEntries = destructuringEntries.map { entry =>
      val typeFullName = registerType(typeInfoProvider.typeFullName(entry, TypeConstants.any))
      val node         = localNode(entry.getName, typeFullName, None, line(entry), column(entry))
      scope.addToScope(node.name, node)
      Ast(node)
    }

    val ctorTypeFullName = registerType(typeInfoProvider.expressionType(ctorCall, Defines.UnresolvedNamespace))
    val tmpName          = s"${Constants.tmpLocalPrefix}${tmpKeyPool.next}"
    val localForTmpNode  = localNode(tmpName, ctorTypeFullName)
    scope.addToScope(localForTmpNode.name, localForTmpNode)
    val localForTmpAst = Ast(localForTmpNode)

    val assignmentRhsNode =
      operatorCallNode(Operators.alloc, Constants.alloc, Option(localForTmpNode.typeFullName), line(expr), column(expr))
    val assignmentLhsNode = identifierNode(tmpName, localForTmpNode.typeFullName, line(expr), column(expr))
    val assignmentLhsAst  = Ast(assignmentLhsNode).withRefEdge(assignmentLhsNode, localForTmpNode)

    val assignmentNode = operatorCallNode(Operators.assignment, s"$tmpName  = ${Constants.alloc}", None)
    val assignmentAst  = callAst(assignmentNode, List(assignmentLhsAst, Ast(assignmentRhsNode)))

    val initReceiverNode = identifierNode(tmpName, localForTmpNode.typeFullName, line(expr), column(expr))
      .argumentIndex(0)
    val initReceiverAst = Ast(initReceiverNode).withRefEdge(initReceiverNode, localForTmpNode)

    val argAsts = withIndex(ctorCall.getValueArguments.asScala.toSeq) { case (arg, idx) =>
      astsForExpression(arg.getArgumentExpression, Some(idx))
    }.flatten

    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(ctorCall, (TypeConstants.any, TypeConstants.any))
    registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val initCallNode = callNode(
      Constants.init,
      Constants.init,
      fullName,
      signature,
      TypeConstants.void,
      DispatchTypes.STATIC_DISPATCH,
      line(expr),
      column(expr)
    )
    val initCallAst = Ast(initCallNode)
      .withChildren(List(initReceiverAst) ++ argAsts)
      .withArgEdges(initCallNode, Seq(initReceiverNode) ++ argAsts.flatMap(_.root))

    val assignmentsForEntries = destructuringEntries.zipWithIndex.map { case (entry, idx) =>
      assignmentAstForDestructuringEntry(entry, localForTmpNode.name, localForTmpNode.typeFullName, idx + 1)
    }

    localsForEntries ++ Seq(localForTmpAst) ++
      Seq(assignmentAst) ++ Seq(initCallAst) ++ assignmentsForEntries
  }

  private def assignmentAstForDestructuringEntry(
    entry: KtDestructuringDeclarationEntry,
    componentNReceiverName: String,
    componentNTypeFullName: String,
    componentIdx: Integer
  )(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val entryTypeFullName = registerType(typeInfoProvider.typeFullName(entry, TypeConstants.any))
    val assignmentLHSNode = identifierNode(entry.getText, entryTypeFullName, line(entry), column(entry))
    val assignmentLHSAst =
      scope.lookupVariable(entry.getText) match {
        case Some(refTo) => Ast(assignmentLHSNode).withRefEdge(assignmentLHSNode, refTo)
        case None        => Ast(assignmentLHSNode)
      }

    val componentNIdentifierNode =
      identifierNode(componentNReceiverName, componentNTypeFullName, line(entry), column(entry))
        .argumentIndex(0)

    val fallbackSignature = s"${Defines.UnresolvedNamespace}()"
    val fallbackFullName =
      s"${Defines.UnresolvedNamespace}${Constants.componentNPrefix}$componentIdx:$fallbackSignature"
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(entry, (fallbackFullName, fallbackSignature))
    val componentNCallCode = s"$componentNReceiverName.${Constants.componentNPrefix}$componentIdx()"
    val componentNCallNode = callNode(
      componentNCallCode,
      s"${Constants.componentNPrefix}$componentIdx",
      fullName,
      signature,
      entryTypeFullName,
      DispatchTypes.DYNAMIC_DISPATCH,
      line(entry),
      column(entry)
    )

    val componentNIdentifierAst = astWithRefEdgeMaybe(componentNIdentifierNode.name, componentNIdentifierNode)
    val componentNAst =
      callAst(componentNCallNode, Seq(), Option(componentNIdentifierAst))

    val assignmentCallNode = operatorCallNode(
      Operators.assignment,
      s"${entry.getText} = $componentNCallCode",
      None,
      line(entry),
      column(entry)
    )
    callAst(assignmentCallNode, List(assignmentLHSAst, componentNAst))
  }

  /*
   _______ example lowering _________
  | val (one, two) = person
  |
  | -> LOCAL one
  | -> LOCAL two
  | -> CALL one = person.component1()
  | -> CALL two = person.component2()
  |__________________________________
   */
  private def astsForDestructuringDeclarationWithVarRHS(
    expr: KtDestructuringDeclaration
  )(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val typedInit = Option(expr.getInitializer).collect { case e: KtNameReferenceExpression => e }
    if (typedInit.isEmpty) {
      logger.warn(s"Unhandled case for destructuring declaration: `${expr.getText}`.")
      return Seq()
    }
    val destructuringRHS = typedInit.get

    val initTypeFullName = registerType(typeInfoProvider.typeFullName(typedInit.get, TypeConstants.any))
    val assignmentsForEntries =
      nonUnderscoreDestructuringEntries(expr).zipWithIndex.map { case (entry, idx) =>
        assignmentAstForDestructuringEntry(entry, destructuringRHS.getText, initTypeFullName, idx + 1)
      }
    val localsForEntries = nonUnderscoreDestructuringEntries(expr).map { entry =>
      val typeFullName = registerType(typeInfoProvider.typeFullName(entry, TypeConstants.any))
      val node         = localNode(entry.getName, typeFullName, None, line(entry), column(entry))
      scope.addToScope(node.name, node)
      Ast(node)
    }
    localsForEntries ++ assignmentsForEntries
  }

  def astsForDestructuringDeclaration(
    expr: KtDestructuringDeclaration
  )(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val hasNonRefExprRHS = expr.getInitializer match {
      case _: KtNameReferenceExpression => false
      case _: KtExpression              => true
      case null                         => false
    }
    val isCtor = expr.getInitializer match {
      case typedExpr: KtCallExpression => typeInfoProvider.isConstructorCall(typedExpr).getOrElse(false)
      case _                           => false
    }
    if (isCtor) astsForDestructuringDeclarationWithCtorRHS(expr)
    else if (hasNonRefExprRHS) astsForDestructuringDeclarationWithNonCtorCallRHS(expr)
    else astsForDestructuringDeclarationWithVarRHS(expr)
  }

  def astForUnknown(expr: KtExpression, argIdx: Option[Int]): Ast = {
    val node = unknownNode(
      Option(expr).map(_.getText).getOrElse(Constants.codePropUndefinedValue),
      Constants.parserTypeName,
      line(expr),
      column(expr)
    )
    Ast(withArgumentIndex(node, argIdx))
  }

  def astForStringTemplate(expr: KtStringTemplateExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    if (expr.hasInterpolation) {
      val args = expr.getEntries.filter(_.getExpression != null).zipWithIndex.map { case (entry, idx) =>
        val entryTypeFullName = registerType(typeInfoProvider.expressionType(entry.getExpression, TypeConstants.any))
        val valueCallNode = operatorCallNode(
          Operators.formattedValue,
          entry.getExpression.getText,
          Option(entryTypeFullName),
          line(entry.getExpression),
          column(entry.getExpression)
        )
        val valueArgs = astsForExpression(entry.getExpression, Some(idx + 1))
        callAst(valueCallNode, valueArgs.toList)
      }
      val node = operatorCallNode(Operators.formatString, expr.getText, Option(typeFullName), line(expr), column(expr))
      callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args.toIndexedSeq.toList)
    } else {
      val node = literalNode(expr.getText, typeFullName, line(expr), column(expr))
      Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
    }
  }

  private def astForQualifiedExpressionFieldAccess(expr: KtQualifiedExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val receiverAst = astsForExpression(expr.getReceiverExpression, Some(1)).head
    val argAsts     = selectorExpressionArgAsts(expr)
    registerType(typeInfoProvider.containingDeclType(expr, TypeConstants.any))
    val retType = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node = withArgumentIndex(
      operatorCallNode(Operators.fieldAccess, expr.getText, Option(retType), line(expr), column(expr)),
      argIdx
    )
    callAst(node, List(receiverAst) ++ argAsts)
  }

  private def astForQualifiedExpressionExtensionCall(expr: KtQualifiedExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val receiverAst = astsForExpression(expr.getReceiverExpression, Some(0)).head
    val argAsts     = selectorExpressionArgAsts(expr)

    val (astDerivedMethodFullName, astDerivedSignature) = astDerivedFullNameWithSignature(expr, argAsts)
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(expr, (astDerivedMethodFullName, astDerivedSignature))
    registerType(typeInfoProvider.containingDeclType(expr, TypeConstants.any))
    val retType    = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val methodName = expr.getSelectorExpression.getFirstChild.getText
    val node =
      withArgumentIndex(
        callNode(
          expr.getText,
          methodName,
          fullName,
          signature,
          retType,
          DispatchTypes.STATIC_DISPATCH,
          line(expr),
          column(expr)
        ),
        argIdx
      )
    callAst(node, argAsts, Option(receiverAst))
  }

  private def selectorExpressionArgAsts(
    expr: KtQualifiedExpression
  )(implicit typeInfoProvider: TypeInfoProvider): List[Ast] = {
    expr.getSelectorExpression match {
      case typedExpr: KtCallExpression =>
        withIndex(typedExpr.getValueArguments.asScala.toSeq) { case (arg, idx) =>
          astsForExpression(arg.getArgumentExpression, Some(idx))
        }.flatten.toList
      case typedExpr: KtNameReferenceExpression =>
        val node = fieldIdentifierNode(typedExpr.getText).argumentIndex(2)
        List(Ast(node))
      case _ => List()
    }
  }

  private def astForQualifiedExpressionCallToSuper(expr: KtQualifiedExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val receiverAst = astsForExpression(expr.getReceiverExpression, Some(0)).head
    val argAsts     = selectorExpressionArgAsts(expr)

    val (astDerivedMethodFullName, astDerivedSignature) = astDerivedFullNameWithSignature(expr, argAsts)
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(expr, (astDerivedMethodFullName, astDerivedSignature))
    registerType(typeInfoProvider.containingDeclType(expr, TypeConstants.any))
    val retType    = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val methodName = expr.getSelectorExpression.getFirstChild.getText
    val node =
      withArgumentIndex(
        callNode(
          expr.getText,
          methodName,
          fullName,
          signature,
          retType,
          DispatchTypes.STATIC_DISPATCH,
          line(expr),
          column(expr)
        ),
        argIdx
      )
    callAst(node, argAsts, Option(receiverAst))
  }

  private def astForQualifiedExpressionWithNoAstForReceiver(expr: KtQualifiedExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val receiverAst = astsForExpression(expr.getReceiverExpression, Some(1)).head
    val argAsts     = selectorExpressionArgAsts(expr)

    val (astDerivedMethodFullName, astDerivedSignature) = astDerivedFullNameWithSignature(expr, argAsts)
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(expr, (astDerivedMethodFullName, astDerivedSignature))
    registerType(typeInfoProvider.containingDeclType(expr, TypeConstants.any))
    val retType      = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val methodName   = expr.getSelectorExpression.getFirstChild.getText
    val dispatchType = DispatchTypes.STATIC_DISPATCH

    val node = withArgumentIndex(
      callNode(expr.getText, methodName, fullName, signature, retType, dispatchType, line(expr), column(expr)),
      argIdx
    )
    Ast(node)
      .withChild(receiverAst)
      .withChildren(argAsts)
      .withArgEdges(node, argAsts.map(_.root.get))
  }

  private def astForQualifiedExpressionWithReceiverEdge(
    expr: KtQualifiedExpression,
    callKind: CallKinds.CallKind,
    argIdx: Option[Int]
  )(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val isDynamicCall = callKind == CallKinds.DynamicCall
    val isStaticCall  = callKind == CallKinds.StaticCall
    val argIdxForReceiver =
      if (isDynamicCall) 0
      else if (isStaticCall) 1
      else 1
    val dispatchType =
      if (callKind == CallKinds.DynamicCall) DispatchTypes.DYNAMIC_DISPATCH
      else DispatchTypes.STATIC_DISPATCH

    val receiverAst = astsForExpression(expr.getReceiverExpression, Some(argIdxForReceiver)).head
    val argAsts     = selectorExpressionArgAsts(expr)

    val (astDerivedMethodFullName, astDerivedSignature) = astDerivedFullNameWithSignature(expr, argAsts)
    val (fullName, signature) =
      typeInfoProvider.fullNameWithSignature(expr, (astDerivedMethodFullName, astDerivedSignature))
    registerType(typeInfoProvider.containingDeclType(expr, TypeConstants.any))
    val retType    = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val methodName = expr.getSelectorExpression.getFirstChild.getText

    val node = withArgumentIndex(
      callNode(expr.getText, methodName, fullName, signature, retType, dispatchType, line(expr), column(expr)),
      argIdx
    )
    val receiverNode =
      if (argAsts.sizeIs == 1 && argAsts.head.root.get.isInstanceOf[NewMethodRef]) argAsts.head.root.get
      else receiverAst.root.get

    Ast(node)
      .withChild(receiverAst)
      .withArgEdge(node, receiverNode)
      .withChildren(argAsts)
      .withArgEdges(node, argAsts.map(_.root.get))
      .withReceiverEdge(node, receiverNode)
  }

  private def astDerivedFullNameWithSignature(expr: KtQualifiedExpression, argAsts: List[Ast])(implicit
    typeInfoProvider: TypeInfoProvider
  ): (String, String) = {
    val astDerivedMethodFullName = expr.getSelectorExpression match {
      case expression: KtCallExpression =>
        val receiverPlaceholderType = Defines.UnresolvedNamespace
        val shortName               = expr.getSelectorExpression.getFirstChild.getText
        val args                    = expression.getValueArguments
        s"$receiverPlaceholderType.$shortName:${typeInfoProvider.anySignature(args.asScala.toList)}"
      case _: KtNameReferenceExpression =>
        Operators.fieldAccess
      case _ =>
        // TODO: add more test cases for this scenario
        ""
    }

    val astDerivedSignature = typeInfoProvider.anySignature(argAsts)
    (astDerivedMethodFullName, astDerivedSignature)
  }

  // TODO: clean up this whole fn
  def astForQualifiedExpression(expr: KtQualifiedExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val callKind        = typeInfoProvider.bindingKind(expr)
    val isExtensionCall = callKind == CallKinds.ExtensionCall

    val hasThisSuperOrNameRefReceiver = expr.getReceiverExpression match {
      case _: KtThisExpression          => true
      case _: KtNameReferenceExpression => true
      case _: KtSuperExpression         => true
      case _                            => false
    }
    val hasNameRefSelector = expr.getSelectorExpression.isInstanceOf[KtNameReferenceExpression]
    val isFieldAccessCall  = hasThisSuperOrNameRefReceiver && hasNameRefSelector
    val isCallToSuper = expr.getReceiverExpression match {
      case _: KtSuperExpression => true
      case _                    => false
    }
    val isStaticMethodCall = typeInfoProvider.isStaticMethodCall(expr)
    val hasRefToClassReceiver = expr.getReceiverExpression match {
      case r: KtNameReferenceExpression =>
        typeInfoProvider.isReferenceToClass(r)
      case _ =>
        false
    }
    val noAstForReceiver = isStaticMethodCall && hasRefToClassReceiver
    if (isFieldAccessCall) {
      astForQualifiedExpressionFieldAccess(expr, argIdx)
    } else if (isExtensionCall) {
      astForQualifiedExpressionExtensionCall(expr, argIdx)
    } else if (isCallToSuper) {
      astForQualifiedExpressionCallToSuper(expr, argIdx)
    } else if (noAstForReceiver) {
      astForQualifiedExpressionWithNoAstForReceiver(expr, argIdx)
    } else {
      astForQualifiedExpressionWithReceiverEdge(expr, callKind, argIdx)
    }
  }

  def astForBreak(expr: KtBreakExpression): Ast = {
    val node = controlStructureNode(expr.getText, ControlStructureTypes.BREAK, line(expr), column(expr))
    Ast(node)
  }

  def astForContinue(expr: KtContinueExpression): Ast = {
    val node = controlStructureNode(expr.getText, ControlStructureTypes.CONTINUE, line(expr), column(expr))
    Ast(node)
  }

  private def astForTryAsStatement(expr: KtTryExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val tryAstOption = astsForExpression(expr.getTryBlock, None).headOption
      .getOrElse(Ast())
    val clauseAsts = withIndex(expr.getCatchClauses.asScala.toSeq) { (entry, _) =>
      astsForExpression(entry.getCatchBody, None)
    }.flatten
    val finallyAsts = Option(expr.getFinallyBlock)
      .map(_.getFinalExpression)
      .map(astsForExpression(_, None))
      .getOrElse(Seq())
    val node = controlStructureNode(expr.getText, ControlStructureTypes.TRY, line(expr), column(expr))
    controlStructureAst(node, None, tryAstOption :: (clauseAsts ++ finallyAsts).toList)
  }

  private def astForTryAsExpression(expr: KtTryExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(
      // TODO: remove the `last`
      typeInfoProvider.expressionType(expr.getTryBlock.getStatements.asScala.last, TypeConstants.any)
    )
    val tryBlockAst = astsForExpression(expr.getTryBlock, None).headOption.getOrElse(Ast())
    val clauseAsts = expr.getCatchClauses.asScala.toSeq.map { entry =>
      astsForExpression(entry.getCatchBody, None)
    }.flatten
    val node = operatorCallNode(Operators.tryCatch, expr.getText, Option(typeFullName), line(expr), column(expr))
    callAst(withArgumentIndex(node, argIdx), List(tryBlockAst) ++ clauseAsts)
  }

  // TODO: handle parameters passed to the clauses
  def astForTry(expr: KtTryExpression, argIdx: Option[Int])(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    if (KtPsiUtil.isStatement(expr)) astForTryAsStatement(expr)
    else astForTryAsExpression(expr, argIdx)
  }

  def astForWhile(expr: KtWhileExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val stmtAsts     = astsForExpression(expr.getBody, None)
    val code         = Option(expr.getText)
    val lineNumber   = Option(Integer.valueOf(line(expr)))
    val columnNumber = Option(Integer.valueOf(column(expr)))

    whileAst(conditionAst, stmtAsts, code, lineNumber, columnNumber)
  }

  def astForDoWhile(expr: KtDoWhileExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val stmtAsts     = astsForExpression(expr.getBody, None)
    val code         = Option(expr.getText)
    val lineNumber   = Option(Integer.valueOf(line(expr)))
    val columnNumber = Option(Integer.valueOf(column(expr)))

    doWhileAst(conditionAst, stmtAsts, code, lineNumber, columnNumber)
  }

  // e.g. lowering:
  // for `for (one in l) { <statements> }`
  // BLOCK
  //     LOCAL iterator
  //     loweringOf{iterator = l.iterator()}
  //     CONTROL_STRUCTURE (while)
  //         --AST[order.1]--> loweringOf{iterator.hasNext()}
  //         --AST[order.2]--> BLOCK
  //                            |-> LOCAL one
  //                            |-> loweringOf{one = iterator.next()}
  //                            |-> <statements>
  //
  private def astForForWithSimpleVarLHS(expr: KtForExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val loopRangeText         = expr.getLoopRange.getText
    val iteratorName          = s"${Constants.iteratorPrefix}${iteratorKeyPool.next()}"
    val iteratorLocal         = localNode(iteratorName, TypeConstants.any)
    val iteratorAssignmentLhs = identifierNode(iteratorName, TypeConstants.any)
    val iteratorLocalAst      = Ast(iteratorLocal).withRefEdge(iteratorAssignmentLhs, iteratorLocal)

    val loopRangeExprTypeFullName = registerType(typeInfoProvider.expressionType(expr.getLoopRange, TypeConstants.any))

    val iteratorAssignmentRhsIdentifier = identifierNode(loopRangeText, loopRangeExprTypeFullName)
      .argumentIndex(0)
    val iteratorAssignmentRhs = callNode(
      s"$loopRangeText.${Constants.getIteratorMethodName}()",
      Constants.getIteratorMethodName,
      s"$loopRangeExprTypeFullName.${Constants.getIteratorMethodName}:${Constants.javaUtilIterator}()",
      s"${Constants.javaUtilIterator}()",
      Constants.javaUtilIterator,
      DispatchTypes.DYNAMIC_DISPATCH
    )

    val iteratorAssignmentRhsAst =
      callAst(iteratorAssignmentRhs, Seq(), Option(Ast(iteratorAssignmentRhsIdentifier)))
    val iteratorAssignment =
      operatorCallNode(Operators.assignment, s"$iteratorName = ${iteratorAssignmentRhs.code}", None)

    val iteratorAssignmentAst = callAst(iteratorAssignment, List(Ast(iteratorAssignmentLhs), iteratorAssignmentRhsAst))
    val controlStructure = controlStructureNode(expr.getText, ControlStructureTypes.WHILE, line(expr), column(expr))

    val conditionIdentifier = identifierNode(loopRangeText, loopRangeExprTypeFullName).argumentIndex(0)

    val hasNextFullName =
      s"${Constants.collectionsIteratorName}.${Constants.hasNextIteratorMethodName}:${TypeConstants.javaLangBoolean}()"
    val controlStructureCondition = callNode(
      s"$iteratorName.${Constants.hasNextIteratorMethodName}()",
      Constants.hasNextIteratorMethodName,
      hasNextFullName,
      s"${TypeConstants.javaLangBoolean}()",
      TypeConstants.javaLangBoolean,
      DispatchTypes.DYNAMIC_DISPATCH
    ).argumentIndex(0)
    val controlStructureConditionAst =
      callAst(controlStructureCondition, List(), Option(Ast(conditionIdentifier)))

    val loopParameterTypeFullName = registerType(
      typeInfoProvider.typeFullName(expr.getLoopParameter, TypeConstants.any)
    )
    val loopParameterName  = expr.getLoopParameter.getText
    val loopParameterLocal = localNode(loopParameterName, loopParameterTypeFullName)
    scope.addToScope(loopParameterName, loopParameterLocal)

    val loopParameterIdentifier = identifierNode(loopParameterName, TypeConstants.any)
    val loopParameterAst        = Ast(loopParameterLocal).withRefEdge(loopParameterIdentifier, loopParameterLocal)

    val iteratorNextIdentifier    = identifierNode(iteratorName, TypeConstants.any).argumentIndex(0)
    val iteratorNextIdentifierAst = Ast(iteratorNextIdentifier).withRefEdge(iteratorNextIdentifier, iteratorLocal)

    val iteratorNextCall = callNode(
      s"$iteratorName.${Constants.nextIteratorMethodName}()",
      Constants.nextIteratorMethodName,
      s"${Constants.collectionsIteratorName}.${Constants.nextIteratorMethodName}:${TypeConstants.javaLangObject}()",
      s"${TypeConstants.javaLangObject}()",
      TypeConstants.javaLangObject,
      DispatchTypes.DYNAMIC_DISPATCH
    )
    val iteratorNextCallAst =
      callAst(iteratorNextCall, Seq(), Option(iteratorNextIdentifierAst))
    val loopParameterNextAssignment =
      operatorCallNode(Operators.assignment, s"$loopParameterName = ${iteratorNextCall.code}", None)
    val loopParameterNextAssignmentAst =
      callAst(loopParameterNextAssignment, List(Ast(loopParameterIdentifier), iteratorNextCallAst))

    val stmtAsts             = astsForExpression(expr.getBody, Some(3))
    val controlStructureBody = blockNode("", "")
    val controlStructureBodyAst =
      blockAst(controlStructureBody, List(loopParameterAst, loopParameterNextAssignmentAst) ++ stmtAsts)

    val controlStructureAst = Ast(controlStructure)
      .withChildren(List(controlStructureConditionAst, controlStructureBodyAst))
      .withConditionEdge(controlStructure, controlStructureCondition)
    blockAst(
      blockNode(Constants.codeForLoweredForBlock, ""),
      List(iteratorLocalAst, iteratorAssignmentAst, controlStructureAst)
    )
  }

  // e.g. lowering:
  // for `for ((d1, d2) in l) { <statements> }`
  // BLOCK
  //     LOCAL iterator
  //     loweringOf{iterator = l.iterator()}
  //     CONTROL_STRUCTURE (while)
  //         --AST[order.1]--> loweringOf{iterator.hasNext()}
  //         --AST[order.2]--> BLOCK
  //                            |-> LOCAL d1
  //                            |-> LOCAL d2
  //                            |-> LOCAL tmp
  //                            |-> loweringOf{tmp = iterator.next()}
  //                            |-> loweringOf{d1 = tmp.component1()}
  //                            |-> loweringOf{d2 = tmp.component2()}
  //                            |-> <statements>
  //
  private def astForForWithDestructuringLHS(expr: KtForExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val loopRangeText         = expr.getLoopRange.getText
    val iteratorName          = s"${Constants.iteratorPrefix}${iteratorKeyPool.next()}"
    val localForIterator      = localNode(iteratorName, TypeConstants.any)
    val iteratorAssignmentLhs = identifierNode(iteratorName, TypeConstants.any)
    val iteratorLocalAst      = Ast(localForIterator).withRefEdge(iteratorAssignmentLhs, localForIterator)

    // TODO: maybe use a different method here, one which does not translate `kotlin.collections.List` to `java.util.List`
    val loopRangeExprTypeFullName = registerType(typeInfoProvider.expressionType(expr.getLoopRange, TypeConstants.any))
    val iteratorAssignmentRhsIdentifier = identifierNode(loopRangeText, loopRangeExprTypeFullName)
      .argumentIndex(0)
    val iteratorAssignmentRhs = callNode(
      s"$loopRangeText.${Constants.getIteratorMethodName}()",
      Constants.getIteratorMethodName,
      s"$loopRangeExprTypeFullName.${Constants.getIteratorMethodName}:${Constants.javaUtilIterator}()",
      s"${Constants.javaUtilIterator}()",
      Constants.javaUtilIterator,
      DispatchTypes.DYNAMIC_DISPATCH
    )

    val iteratorAssignmentRhsAst =
      callAst(iteratorAssignmentRhs, Seq(), Option(Ast(iteratorAssignmentRhsIdentifier)))

    val iteratorAssignment =
      operatorCallNode(Operators.assignment, s"$iteratorName = ${iteratorAssignmentRhs.code}", None)
    val iteratorAssignmentAst = callAst(iteratorAssignment, List(Ast(iteratorAssignmentLhs), iteratorAssignmentRhsAst))

    val controlStructure    = controlStructureNode(expr.getText, ControlStructureTypes.WHILE, line(expr), column(expr))
    val conditionIdentifier = identifierNode(loopRangeText, loopRangeExprTypeFullName).argumentIndex(0)

    val hasNextFullName =
      s"${Constants.collectionsIteratorName}.${Constants.hasNextIteratorMethodName}:${TypeConstants.javaLangBoolean}()"
    val controlStructureCondition = callNode(
      s"$iteratorName.${Constants.hasNextIteratorMethodName}()",
      Constants.hasNextIteratorMethodName,
      hasNextFullName,
      s"${TypeConstants.javaLangBoolean}()",
      TypeConstants.javaLangBoolean,
      DispatchTypes.DYNAMIC_DISPATCH
    ).argumentIndex(0)
    val controlStructureConditionAst =
      callAst(controlStructureCondition, List(), Option(Ast(conditionIdentifier)))

    val destructuringDeclEntries = expr.getDestructuringDeclaration.getEntries
    val localsForDestructuringVars = destructuringDeclEntries.asScala.map { entry =>
      val entryTypeFullName = registerType(typeInfoProvider.typeFullName(entry, TypeConstants.any))
      val entryName         = entry.getText
      val node              = localNode(entryName, entryTypeFullName, None, line(entry), column(entry))
      scope.addToScope(entryName, node)
      Ast(node)
    }.toList

    val tmpName     = s"${Constants.tmpLocalPrefix}${tmpKeyPool.next}"
    val localForTmp = localNode(tmpName, TypeConstants.any)
    scope.addToScope(localForTmp.name, localForTmp)
    val localForTmpAst = Ast(localForTmp)

    val tmpIdentifier             = identifierNode(tmpName, TypeConstants.any)
    val tmpIdentifierAst          = Ast(tmpIdentifier).withRefEdge(tmpIdentifier, localForTmp)
    val iteratorNextIdentifier    = identifierNode(iteratorName, TypeConstants.any).argumentIndex(0)
    val iteratorNextIdentifierAst = Ast(iteratorNextIdentifier).withRefEdge(iteratorNextIdentifier, localForIterator)

    val iteratorNextCall = callNode(
      s"${iteratorNextIdentifier.code}.${Constants.nextIteratorMethodName}()",
      Constants.nextIteratorMethodName,
      s"${Constants.collectionsIteratorName}.${Constants.nextIteratorMethodName}:${TypeConstants.javaLangObject}()",
      s"${TypeConstants.javaLangObject}()",
      TypeConstants.javaLangObject,
      DispatchTypes.DYNAMIC_DISPATCH
    )

    val iteratorNextCallAst =
      callAst(iteratorNextCall, Seq(), Option(iteratorNextIdentifierAst))
    val tmpParameterNextAssignment    = operatorCallNode(Operators.assignment, s"$tmpName = ${iteratorNextCall.code}")
    val tmpParameterNextAssignmentAst = callAst(tmpParameterNextAssignment, List(tmpIdentifierAst, iteratorNextCallAst))

    val assignmentsForEntries =
      destructuringDeclEntries.asScala.zipWithIndex.map { case (entry, idx) =>
        assignmentAstForDestructuringEntry(entry, localForTmp.name, localForTmp.typeFullName, idx + 1)
      }

    val stmtAsts             = astsForExpression(expr.getBody, None)
    val controlStructureBody = blockNode("", "")
    val controlStructureBodyAst = blockAst(
      controlStructureBody,
      localsForDestructuringVars ++
        List(localForTmpAst, tmpParameterNextAssignmentAst) ++
        assignmentsForEntries ++
        stmtAsts
    )

    val controlStructureAst = Ast(controlStructure)
      .withChildren(List(controlStructureConditionAst, controlStructureBodyAst))
      .withConditionEdge(controlStructure, controlStructureCondition)
    blockAst(
      blockNode(Constants.codeForLoweredForBlock, ""),
      List(iteratorLocalAst, iteratorAssignmentAst, controlStructureAst)
    )
  }

  def astForFor(expr: KtForExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    if (expr.getDestructuringDeclaration != null) astForForWithDestructuringLHS(expr)
    else astForForWithSimpleVarLHS(expr)
  }

  def astForWhen(expr: KtWhenExpression, argIdx: Option[Int])(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val astForSubject = astsForExpression(expr.getSubjectExpression, Some(1)).headOption.getOrElse(Ast())
    val finalAstForSubject = expr.getSubjectExpression match {
      case _: KtProperty =>
        val block = blockNode("", "").argumentIndex(1)
        blockAst(block, List(astForSubject))
      case _ => astForSubject
    }
    val astsForEntries = withIndex(expr.getEntries.asScala.toList) { (e, idx) =>
      astsForWhenEntry(e, idx)
    }.flatten

    val switchBlockNode =
      blockNode(expr.getEntries.asScala.map(_.getText).mkString("\n"), TypeConstants.any, line(expr), column(expr))
    val astForBlock = blockAst(switchBlockNode, astsForEntries.toList)
    val codeForSwitch = Option(expr.getSubjectExpression)
      .map(_.getText)
      .map { text => s"${Constants.when}($text)" }
      .getOrElse(Constants.when)
    val switchNode = controlStructureNode(codeForSwitch, ControlStructureTypes.SWITCH, line(expr), column(expr))
    val ast        = Ast(withArgumentIndex(switchNode, argIdx)).withChildren(List(astForSubject, astForBlock))
    // TODO: rewrite this as well
    finalAstForSubject.root match {
      case Some(root) => ast.withConditionEdge(switchNode, root)
      case None       => ast
    }
  }

  def astsForWhenEntry(entry: KtWhenEntry, argIdx: Int)(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    // TODO: get all conditions with entry.getConditions()
    val name =
      if (entry.getElseKeyword == null) Constants.defaultCaseNode
      else s"${Constants.caseNodePrefix}$argIdx"
    val jumpNode = jumpTargetNode(entry.getText, name, Constants.caseNodeParserTypeName, line(entry), column(entry))
      .argumentIndex(argIdx)
    val exprNode = astsForExpression(entry.getExpression, Some(argIdx + 1)).headOption.getOrElse(Ast())
    Seq(Ast(jumpNode), exprNode)
  }

  def astForIf(expr: KtIfExpression, argIdx: Option[Int])(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val isChildOfControlStructureBody = expr.getParent.isInstanceOf[KtContainerNodeForControlStructureBody]
    if (KtPsiUtil.isStatement(expr) && !isChildOfControlStructureBody) astForIfAsControlStructure(expr)
    else astForIfAsExpression(expr, argIdx)
  }

  def astForIfAsControlStructure(expr: KtIfExpression)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val thenAsts     = astsForExpression(expr.getThen, None)
    val elseAsts     = astsForExpression(expr.getElse, None)

    val node = controlStructureNode(expr.getText, ControlStructureTypes.IF, line(expr), column(expr))
    controlStructureAst(node, conditionAst, List(thenAsts ++ elseAsts).flatten)
  }

  def astForIfAsExpression(expr: KtIfExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val conditionAsts = astsForExpression(expr.getCondition, None)
    val thenAsts      = astsForExpression(expr.getThen, None)
    val elseAsts      = astsForExpression(expr.getElse, None)

    val returnTypeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node =
      operatorCallNode(Operators.conditional, expr.getText, Option(returnTypeFullName), line(expr), column(expr))
    callAst(withArgumentIndex(node, argIdx), (conditionAsts ++ thenAsts ++ elseAsts).toList)
  }

  private def astForCtorCall(expr: KtCallExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, Defines.UnresolvedNamespace))
    val tmpBlockNode = blockNode("", typeFullName)
    val tmpName      = s"${Constants.tmpLocalPrefix}${tmpKeyPool.next}"
    val tmpLocalNode = localNode(tmpName, typeFullName)
    val assignmentRhsNode =
      operatorCallNode(Operators.alloc, Constants.alloc, Option(typeFullName), line(expr), column(expr))
    val assignmentLhsNode = identifierNode(tmpName, typeFullName, line(expr), column(expr))
    val assignmentNode    = operatorCallNode(Operators.assignment, Operators.assignment)
    val assignmentAst     = callAst(assignmentNode, List(assignmentLhsNode, assignmentRhsNode).map(Ast(_)))
    val initReceiverNode = identifierNode(tmpName, typeFullName, line(expr), column(expr))
      .argumentIndex(0)
    val initReceiverAst = Ast(initReceiverNode)

    val argAsts = withIndex(expr.getValueArguments.asScala.toSeq) { case (arg, idx) =>
      val argNameOpt = if (arg.isNamed) Option(arg.getArgumentName.getAsName.toString) else None
      astsForExpression(arg.getArgumentExpression, Option(idx), argNameOpt)
    }.flatten

    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(expr, (TypeConstants.any, TypeConstants.any))
    registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))

    val initCallNode = callNode(
      expr.getText,
      Constants.init,
      fullName,
      signature,
      TypeConstants.void,
      DispatchTypes.STATIC_DISPATCH,
      line(expr),
      column(expr)
    )
    val initCallAst       = callAst(initCallNode, argAsts, Option(initReceiverAst))
    val lastIdentifier    = identifierNode(tmpName, typeFullName, line(expr), column(expr))
    val lastIdentifierAst = Ast(lastIdentifier)

    val tmpLocalAst = Ast(tmpLocalNode)
      .withRefEdge(assignmentLhsNode, tmpLocalNode)
      .withRefEdge(initReceiverNode, tmpLocalNode)
      .withRefEdge(lastIdentifier, tmpLocalNode)
    blockAst(withArgumentIndex(tmpBlockNode, argIdx), List(tmpLocalAst, assignmentAst, initCallAst, lastIdentifierAst))
  }

  def astsForProperty(expr: KtProperty)(implicit typeInfoProvider: TypeInfoProvider): Seq[Ast] = {
    val explicitTypeName = Option(expr.getTypeReference).map(_.getText).getOrElse(TypeConstants.any)
    val elem             = expr.getIdentifyingElement
    val typeFullName     = registerType(typeInfoProvider.propertyType(expr, explicitTypeName))
    val node             = localNode(expr.getName, typeFullName, None, line(expr), column(expr))
    scope.addToScope(expr.getName, node)

    val hasRHSCtorCall = expr.getDelegateExpressionOrInitializer match {
      case typed: KtCallExpression => typeInfoProvider.isConstructorCall(typed).getOrElse(false)
      case _                       => false
    }
    if (hasRHSCtorCall) {
      val typedCall = expr.getDelegateExpressionOrInitializer.asInstanceOf[KtCallExpression]

      val typeFullName = registerType(
        typeInfoProvider.expressionType(expr.getDelegateExpressionOrInitializer, Defines.UnresolvedNamespace)
      )
      val rhsAst = Ast(operatorCallNode(Operators.alloc, Operators.alloc, Option(typeFullName)))

      val identifier = identifierNode(elem.getText, typeFullName, line(elem), column(elem))
      val localAst   = Ast(node).withRefEdge(identifier, node) // TODO: use the scope here maybe?

      val assignmentNode    = operatorCallNode(Operators.assignment, expr.getText, None, line(expr), column(expr))
      val assignmentCallAst = callAst(assignmentNode, List(Ast(identifier)) ++ List(rhsAst))

      val (fullName, signature) =
        typeInfoProvider.fullNameWithSignature(typedCall, (TypeConstants.any, TypeConstants.any))
      val initCallNode = callNode(
        typedCall.getText,
        Constants.init,
        fullName,
        signature,
        TypeConstants.void,
        DispatchTypes.STATIC_DISPATCH,
        line(expr),
        column(expr)
      )
      val initReceiverNode = identifierNode(identifier.name, identifier.typeFullName, line(expr), column(expr))
      val initReceiverAst  = Ast(initReceiverNode).withRefEdge(initReceiverNode, node)

      val argAsts = withIndex(typedCall.getValueArguments.asScala.toSeq) { case (arg, idx) =>
        val argNameOpt = if (arg.isNamed) Option(arg.getArgumentName.getAsName.toString) else None
        astsForExpression(arg.getArgumentExpression, Option(idx), argNameOpt)
      }.flatten

      val initAst = callAst(initCallNode, argAsts, Option(initReceiverAst))
      Seq(localAst, assignmentCallAst, initAst)
    } else {
      val rhsAsts        = astsForExpression(expr.getDelegateExpressionOrInitializer, Some(2))
      val identifier     = identifierNode(elem.getText, typeFullName, line(elem), column(elem))
      val assignmentNode = operatorCallNode(Operators.assignment, expr.getText, None, line(expr), column(expr))
      val call           = callAst(assignmentNode, List(Ast(identifier)) ++ rhsAsts)

      val localAst = Ast(node).withRefEdge(identifier, node)
      Seq(localAst, call)
    }
  }

  def astForNameReference(expr: KtNameReferenceExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    if (typeInfoProvider.isReferenceToClass(expr)) astForNameReferenceToType(expr, argIdx)
    else if (typeInfoProvider.isReferencingMember(expr)) {
      astForNameReferenceToMember(expr, argIdx)
    } else {
      astForNonSpecialNameReference(expr, argIdx, argName)
    }
  }

  private def astForNameReferenceToType(expr: KtNameReferenceExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName              = registerType(typeInfoProvider.typeFullName(expr, TypeConstants.any))
    val referencesCompanionObject = typeInfoProvider.isRefToCompanionObject(expr)
    if (referencesCompanionObject) {
      val argAsts = List(
        // TODO: change this to a TYPE_REF node as soon as the closed source data-flow engine supports it
        identifierNode(expr.getIdentifier.getText, typeFullName, line(expr), column(expr)),
        fieldIdentifierNode(Constants.companionObjectMemberName, line(expr), column(expr))
      ).map(Ast(_))
      val node = operatorCallNode(Operators.fieldAccess, expr.getText, Option(typeFullName), line(expr), column(expr))
      callAst(withArgumentIndex(node, argIdx), argAsts)
    } else {
      val node = typeRefNode(expr.getIdentifier.getText, typeFullName, line(expr), column(expr))
      Ast(withArgumentIndex(node, argIdx))
    }
  }

  private def astForNameReferenceToMember(expr: KtNameReferenceExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.typeFullName(expr, TypeConstants.any))
    val referenceTargetTypeFullName = registerType(
      typeInfoProvider.referenceTargetTypeFullName(expr, TypeConstants.any)
    )
    val thisNode             = identifierNode(Constants.this_, referenceTargetTypeFullName, line(expr), column(expr))
    val thisAst              = astWithRefEdgeMaybe(Constants.this_, thisNode)
    val _fieldIdentifierNode = fieldIdentifierNode(expr.getReferencedName, line(expr), column(expr))
    val node = operatorCallNode(
      Operators.fieldAccess,
      s"${Constants.this_}.${expr.getReferencedName}",
      Option(typeFullName),
      line(expr),
      column(expr)
    )
    callAst(withArgumentIndex(node, argIdx), List(thisAst, Ast(_fieldIdentifierNode)))
  }

  private def astForNonSpecialNameReference(
    expr: KtNameReferenceExpression,
    argIdx: Option[Int],
    argName: Option[String] = None
  )(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val fallBackTypeName = scope.lookupVariable(expr.getIdentifier.getText) match {
      case Some(n: NewLocal)             => n.typeFullName
      case Some(n: NewMethodParameterIn) => n.typeFullName
      case _                             => Defines.UnresolvedNamespace
    }
    val typeFromProvider = typeInfoProvider.typeFullName(expr, fallBackTypeName)
    val typeFullName =
      if (typeFromProvider == Defines.UnresolvedNamespace && fallBackTypeName != Defines.UnresolvedNamespace)
        registerType(fallBackTypeName)
      else
        registerType(typeFromProvider)
    val name = expr.getIdentifier.getText
    val node =
      withArgumentName(withArgumentIndex(identifierNode(name, typeFullName, line(expr), column(expr)), argIdx), argName)
    astWithRefEdgeMaybe(name, node)
  }

  def astForLiteral(expr: KtConstantExpression, argIdx: Option[Int], argName: Option[String])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val typeFullName = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node         = literalNode(expr.getText, typeFullName, line(expr), column(expr))
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
  }

  def astForBinaryExpr(expr: KtBinaryExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val opRef = expr.getOperationReference

    // TODO: add the rest of the operators
    val operatorOption = opRef.getOperationSignTokenType match {
      case KtTokens.ANDAND     => Some(Operators.logicalAnd)
      case KtTokens.DIVEQ      => Some(Operators.assignmentDivision)
      case KtTokens.DIV        => Some(Operators.division)
      case KtTokens.ELVIS      => Some(Operators.elvis)
      case KtTokens.EQEQEQ     => Some(Operators.equals)
      case KtTokens.EQEQ       => Some(Operators.equals)
      case KtTokens.EQ         => Some(Operators.assignment)
      case KtTokens.EXCLEQEQEQ => Some(Operators.notEquals)
      case KtTokens.EXCLEQ     => Some(Operators.notEquals)
      case KtTokens.GTEQ       => Some(Operators.greaterEqualsThan)
      case KtTokens.GT         => Some(Operators.greaterThan)
      case KtTokens.IN_KEYWORD => Some(Operators.in)
      case KtTokens.LTEQ       => Some(Operators.lessEqualsThan)
      case KtTokens.LT         => Some(Operators.lessThan)
      case KtTokens.MINUSEQ    => Some(Operators.assignmentMinus)
      case KtTokens.MINUS      => Some(Operators.subtraction)
      case KtTokens.MUL        => Some(Operators.multiplication)
      case KtTokens.MULTEQ     => Some(Operators.assignmentMultiplication)
      case KtTokens.NOT_IN     => Some(Operators.notIn)
      case KtTokens.OROR       => Some(Operators.logicalOr)
      case KtTokens.PERCEQ     => Some(Operators.assignmentModulo)
      case KtTokens.PERC       => Some(Operators.modulo)
      case KtTokens.PLUSEQ     => Some(Operators.assignmentPlus)
      case KtTokens.PLUS       => Some(Operators.addition)
      case KtTokens.RANGE      => Some(Operators.range)
      case null =>
        val opElement = expr.getOperationReference.getReferencedNameElement
        opElement.getText match {
          case "and"  => Some(Operators.and)
          case "or"   => Some(Operators.or)
          case "shl"  => Some(Operators.shiftLeft)
          case "shr"  => Some(Operators.arithmeticShiftRight)
          case "ushl" => Some(Operators.shiftLeft)
          case "ushr" => Some(Operators.logicalShiftRight)
          case "xor"  => Some(Operators.xor)
          case _      => None
        }
      case _ =>
        logger.warn(
          s"Unhandled operator token type `${opRef.getOperationSignTokenType}` for expression `${expr.getText}`."
        )
        Some(Constants.unknownOperator)
    }
    val (fullName, signature) =
      if (operatorOption.isDefined) (operatorOption.get, TypeConstants.any)
      // TODO: fix the fallback METHOD_FULL_NAME and SIGNATURE here (should be a correct number of ANYs)
      else typeInfoProvider.fullNameWithSignature(expr, (TypeConstants.any, TypeConstants.any))

    val finalSignature =
      // TODO: add test case for this situation
      if (fullName.startsWith(Constants.operatorSuffix)) Constants.empty
      else signature
    val typeFullName = registerType(typeInfoProvider.typeFullName(expr, TypeConstants.any))
    val name =
      if (operatorOption.isDefined) operatorOption.get
      else if (expr.getChildren.toList.sizeIs >= 2) expr.getChildren.toList(1).getText
      else expr.getName
    val node = callNode(
      expr.getText,
      name,
      fullName,
      finalSignature,
      typeFullName,
      DispatchTypes.STATIC_DISPATCH,
      line(expr),
      column(expr)
    )
    val args = astsForExpression(expr.getLeft, None) ++ astsForExpression(expr.getRight, None)
    callAst(withArgumentIndex(node, argIdx), args.toList)
  }

  def astForCall(expr: KtCallExpression, argIdx: Option[Int])(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val isCtorCall = typeInfoProvider.isConstructorCall(expr)
    if (isCtorCall.getOrElse(false)) astForCtorCall(expr, argIdx)
    else astForNonCtorCall(expr, argIdx)
  }

  private def astForNonCtorCall(expr: KtCallExpression, argIdx: Option[Int])(implicit
    typeInfoProvider: TypeInfoProvider
  ): Ast = {
    val declFullNameOption = typeInfoProvider.containingDeclFullName(expr)
    declFullNameOption.foreach(registerType)

    val argAsts = withIndex(expr.getValueArguments.asScala.toSeq) { case (arg, idx) =>
      val argNameOpt = if (arg.isNamed) Option(arg.getArgumentName.getAsName.toString) else None
      astsForExpression(arg.getArgumentExpression, Option(idx), argNameOpt)
    }.flatten

    // TODO: add tests for the empty `referencedName` here
    val referencedName = Option(expr.getFirstChild)
      .collect { case expr: KtNameReferenceExpression => expr }
      .map(_.getText)
      .getOrElse("")
    val nameToClass =
      expr.getContainingKtFile.getDeclarations.asScala.collect { case c: KtClass => c.getName -> c }.toMap

    val imports = expr.getContainingKtFile.getImportList.getImports.asScala.toList
    val importedNames = imports.map { imp =>
      val importedName = Option(imp.getImportedName).map(_.toString).getOrElse(Constants.wildcardImportName)
      importedName -> imp
    }.toMap

    val methodFqName = if (importedNames.isDefinedAt(referencedName)) {
      importedNames(referencedName).getImportedFqName.toString
    } else if (nameToClass.contains(expr.getCalleeExpression.getText)) {
      val klass = nameToClass(expr.getCalleeExpression.getText)
      s"${klass.getContainingKtFile.getPackageFqName.toString}.$referencedName"
    } else {
      s"${expr.getContainingKtFile.getPackageFqName.toString}.$referencedName"
    }
    val explicitSignature     = s"${TypeConstants.any}(${argAsts.map { _ => TypeConstants.any }.mkString(",")})"
    val explicitFullName      = s"$methodFqName:$explicitSignature"
    val (fullName, signature) = typeInfoProvider.fullNameWithSignature(expr, (explicitFullName, explicitSignature))

    // TODO: add test case to confirm whether the ANY fallback makes sense (could be void)
    val returnType = registerType(typeInfoProvider.expressionType(expr, TypeConstants.any))
    val node = callNode(
      expr.getText,
      referencedName,
      fullName,
      signature,
      returnType,
      DispatchTypes.STATIC_DISPATCH,
      line(expr),
      column(expr)
    )
    callAst(withArgumentIndex(node, argIdx), argAsts.toList)
  }

  def astForMember(decl: KtDeclaration)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val name = Option(decl.getName).getOrElse(TypeConstants.any)
    val explicitTypeName = decl.getOriginalElement match {
      case p: KtProperty if p.getTypeReference != null => p.getTypeReference.getText
      case _                                           => TypeConstants.any
    }
    val typeFullName = decl match {
      case typed: KtProperty => typeInfoProvider.propertyType(typed, explicitTypeName)
      case _                 => explicitTypeName
    }
    registerType(typeFullName)

    val node = memberNode(name, typeFullName, line(decl), column(decl))
    scope.addToScope(name, node)
    Ast(node)
  }

  def astForParameter(param: KtParameter, order: Int)(implicit typeInfoProvider: TypeInfoProvider): Ast = {
    val name =
      if (param.getDestructuringDeclaration != null)
        Constants.paramNameLambdaDestructureDecl
      else
        param.getName

    val explicitTypeName = Option(param.getTypeReference).map(_.getText).getOrElse(TypeConstants.any)
    val typeFullName     = registerType(typeInfoProvider.parameterType(param, explicitTypeName))
    val node             = methodParameterNode(name, typeFullName, line(param), column(param)).order(order)
    scope.addToScope(name, node)
    Ast(node)
  }

}
