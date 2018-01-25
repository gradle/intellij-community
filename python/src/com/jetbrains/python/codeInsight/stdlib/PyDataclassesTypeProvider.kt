/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.jetbrains.python.codeInsight.stdlib

import com.intellij.openapi.util.Ref
import com.jetbrains.python.codeInsight.typing.PyTypingTypeProvider
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyCallExpressionNavigator
import com.jetbrains.python.psi.impl.stubs.PyDataclassFieldStubImpl
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.stubs.PyDataclassFieldStub
import com.jetbrains.python.psi.types.*

class PyDataclassesTypeProvider : PyTypeProviderBase() {

  override fun getReferenceExpressionType(referenceExpression: PyReferenceExpression, context: TypeEvalContext): PyType? {
    return getDataclassTypeForCallee(referenceExpression, context)
  }

  override fun getParameterType(param: PyNamedParameter, func: PyFunction, context: TypeEvalContext): Ref<PyType>? {
    if (!param.isPositionalContainer && !param.isKeywordContainer && param.annotationValue == null && func.name == DUNDER_POST_INIT) {
      val cls = func.containingClass

      if (cls != null && parseDataclassParameters(cls, context)?.init == true) {
        var result: Ref<PyType>? = null

        cls.processClassLevelDeclarations { element, _ ->
          if (element is PyTargetExpression && element.name == param.name && element.annotationValue != null) {
            result = Ref.create(getTypeForParameter(element, context))
            false
          }
          else {
            true
          }
        }

        return result
      }
    }

    return null
  }

  private fun getDataclassTypeForCallee(referenceExpression: PyReferenceExpression, context: TypeEvalContext): PyCallableType? {
    if (PyCallExpressionNavigator.getPyCallExpressionByCallee(referenceExpression) == null) return null

    val resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context)
    val resolveResults = referenceExpression.getReference(resolveContext).multiResolve(false)

    return PyUtil.filterTopPriorityResults(resolveResults)
      .asSequence()
      .filterIsInstance<PyClass>()
      .map { getDataclassTypeForClass(it, context) }
      .firstOrNull { it != null }
  }

  private fun getDataclassTypeForClass(cls: PyClass, context: TypeEvalContext): PyCallableType? {
    val dataclassParameters = parseDataclassParameters(cls, context)
    if (dataclassParameters == null || !dataclassParameters.init) {
      return null
    }

    val parameters = ArrayList<PyCallableParameter>()
    val ellipsis = PyElementGenerator.getInstance(cls.project).createEllipsis()

    cls.processClassLevelDeclarations { element, _ ->
      if (element is PyTargetExpression && !PyTypingTypeProvider.isClassVar(element, context)) {
        fieldToParameter(element, ellipsis, context)?.also { parameters.add(it) }
      }

      true
    }

    return PyCallableTypeImpl(parameters, context.getType(cls))
  }

  private fun fieldToParameter(field: PyTargetExpression,
                               ellipsis: PyNoneLiteralExpression,
                               context: TypeEvalContext): PyCallableParameter? {
    val stub = field.stub
    val fieldStub = if (stub == null) PyDataclassFieldStubImpl.create(field) else stub.getCustomStub(PyDataclassFieldStub::class.java)

    return if (fieldStub == null) {
      val value = when {
        context.maySwitchToAST(field) -> field.findAssignedValue()
        field.hasAssignedValue() -> ellipsis
        else -> null
      }

      PyCallableParameterImpl.nonPsi(field.name, getTypeForParameter(field, context), value)
    }
    else if (!fieldStub.initValue()) {
      null
    }
    else {
      val value = if (fieldStub.hasDefault() || fieldStub.hasDefaultFactory()) ellipsis else null
      PyCallableParameterImpl.nonPsi(field.name, getTypeForParameter(field, context), value)
    }
  }

  private fun getTypeForParameter(element: PyTargetExpression, context: TypeEvalContext): PyType? {
    val type = context.getType(element)
    if (type is PyCollectionType && type is PyClassType && type.classQName == DATACLASSES_INITVAR_TYPE) {
      return type.elementTypes.firstOrNull()
    }
    return type
  }
}