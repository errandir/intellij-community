/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.ShortenReferences
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.resolve.jvm.diagnostics.ErrorsJvm
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.flexibility
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

class CastExpressionFix(element: KtExpression, private val type: KotlinType) : KotlinQuickFixAction<KtExpression>(element) {
    override fun getFamilyName() = "Cast expression"
    override fun getText() = "Cast expression '${element.text}' to '${IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_IN_TYPES.renderType(type)}'"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile): Boolean {
        if (!super.isAvailable(project, editor, file)) return false

        val expressionType = element.analyze(BodyResolveMode.PARTIAL).getType(element) ?: return false
        return type.isSubtypeOf(expressionType) || expressionType.isSubtypeOf(type) // donwcast/upcast
    }

    public override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val renderedType = IdeDescriptorRenderers.SOURCE_CODE.renderType(type)
        val expressionToInsert = KtPsiFactory(file).createExpressionByPattern("$0 as $1", element, renderedType)
        val newExpression = element.replaced(expressionToInsert)
        ShortenReferences.DEFAULT.process((KtPsiUtil.safeDeparenthesize(newExpression) as KtBinaryExpressionWithTypeRHS).right!!)
        editor?.caretModel?.moveToOffset(newExpression.endOffset)
    }

    abstract class Factory : KotlinSingleIntentionActionFactoryWithDelegate<KtExpression, KotlinType>() {
        override fun getElementOfInterest(diagnostic: Diagnostic) = diagnostic.psiElement as? KtExpression
        override fun createFix(originalElement: KtExpression, data: KotlinType) = CastExpressionFix(originalElement, data)
    }

    object SmartCastImpossibleFactory: Factory() {
        override fun extractFixData(element: KtExpression, diagnostic: Diagnostic) = Errors.SMARTCAST_IMPOSSIBLE.cast(diagnostic).a
    }

    object GenericVarianceConversion : Factory() {
        override fun extractFixData(element: KtExpression, diagnostic: Diagnostic): KotlinType? {
            return ErrorsJvm.JAVA_TYPE_MISMATCH.cast(diagnostic).b.flexibility().upperBound
        }
    }
}
