// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.resolve.processors.inference

import com.intellij.psi.PsiType
import com.intellij.psi.impl.source.resolve.graphInference.constraints.ConstraintFormula
import com.intellij.psi.impl.source.resolve.graphInference.constraints.TypeCompatibilityConstraint
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyMethodResult
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil

class ReferenceExpressionConstraint(private val callRef: GrReferenceExpression, private val leftType: PsiType?) : GrConstraintFormula() {

  override fun reduce(session: GroovyInferenceSession, constraints: MutableList<ConstraintFormula>): Boolean {
    val resolved = callRef.advancedResolve()
    if (resolved !is GroovyMethodResult) return true
    resolved.candidate?.let {
      val typeParameters = it.method.typeParameters
      val nestedSession = GroovyInferenceSession(typeParameters, it.siteSubstitutor, callRef, emptyList(), session.skipClosureBlock)
      session.nestedSessions[resolved] = nestedSession
      nestedSession.propagateVariables(session)

      nestedSession.addConstraint(MethodCallConstraint(callRef, it))
      nestedSession.repeatInferencePhases()
      val returnType = session.siteSubstitutor.substitute(PsiUtil.getSmartReturnType(it.method))
      val substitutedLeft = session.siteSubstitutor.substitute(session.substituteWithInferenceVariables(leftType))
      if (returnType != null && PsiType.VOID != returnType && leftType != null) {
        nestedSession.addConstraint(TypeConstraint(substitutedLeft, returnType, callRef))
        nestedSession.repeatInferencePhases()
      }

      session.propagateVariables(nestedSession)
      for (pair in nestedSession.myIncorporationPhase.captures) {
        session.myIncorporationPhase.addCapture(pair.first, pair.second)
      }
      return true
    }

    leftType ?: return true
    callRef.type?.let {
      constraints.add(TypeCompatibilityConstraint(leftType, it))
    }
    return true
  }
}
