// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.uast.kotlin.psi

import com.intellij.openapi.components.ServiceManager
import com.intellij.psi.*
import com.intellij.psi.impl.light.LightMethodBuilder
import com.intellij.psi.impl.light.LightModifierList
import com.intellij.psi.impl.light.LightParameterListBuilder
import com.intellij.psi.impl.light.LightReferenceListBuilder
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.asJava.classes.cannotModify
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.uast.UastErrorType
import org.jetbrains.uast.kotlin.BaseKotlinUastResolveProviderService
import org.jetbrains.uast.kotlin.lz

@ApiStatus.Internal
open class UastFakeLightMethod(
    original: KtFunction,
    containingClass: PsiClass,
) : UastFakeLightMethodBase<KtFunction>(original, containingClass) {

    private val _typeParameterList by lz {
        KotlinLightTypeParameterListBuilder(this).also { paramList ->
            for ((i, p) in original.typeParameters.withIndex()) {
                paramList.addParameter(
                    object : KotlinLightTypeParameterBuilder(
                        p.name ?: "__no_name__",
                        this,
                        i,
                        p
                    ) {
                        private val myExtendsList by lz {
                            super.getExtendsList().apply {
                                p.extendsBound?.let { extendsBound ->
                                    baseResolveProviderService.resolveToType(extendsBound, this@UastFakeLightMethod)
                                        ?.safeAs<PsiClassType>()
                                        ?.let { addReference(it) }
                                }
                            }
                        }

                        override fun getExtendsList(): LightReferenceListBuilder = myExtendsList
                    }
                )
            }
        }
    }

    override fun getTypeParameterList(): PsiTypeParameterList = _typeParameterList

    private val _parameterList: PsiParameterList by lz {
        object : LightParameterListBuilder(original.manager, original.language) {
            override fun getParent(): PsiElement = this@UastFakeLightMethod
            override fun getContainingFile(): PsiFile = parent.containingFile

            init {
                val parameterList = this

                original.receiverTypeReference?.let { receiver ->
                    this.addParameter(
                        UastKotlinPsiParameterBase(
                            "\$this\$${original.name}",
                            baseResolveProviderService.resolveToType(receiver, this@UastFakeLightMethod)
                                ?: UastErrorType,
                            parameterList,
                            receiver
                        )
                    )
                }

                for ((i, p) in original.valueParameters.withIndex()) {
                    this.addParameter(
                        UastKotlinPsiParameter(
                            p.name ?: "p$i",
                            p.typeReference?.let { typeReference ->
                                baseResolveProviderService.resolveToType(typeReference, this@UastFakeLightMethod)
                            } ?: UastErrorType,
                            parameterList,
                            original.language,
                            p.isVarArg,
                            p.defaultValue,
                            p
                        )
                    )
                }
            }
        }
    }

    override fun getParameterList(): PsiParameterList = _parameterList
}

@ApiStatus.Internal
class UastFakeLightPrimaryConstructor(
    original: KtClassOrObject,
    lightClass: PsiClass,
) : UastFakeLightMethodBase<KtClassOrObject>(original, lightClass) {
    override fun isConstructor(): Boolean = true
}

@ApiStatus.Internal
abstract class UastFakeLightMethodBase<T: KtDeclaration>(
    val original: T,
    containingClass: PsiClass,
) : LightMethodBuilder(
    original.manager,
    original.language,
    original.name ?: "<no name provided>",
    LightParameterListBuilder(original.manager, original.language),
    LightModifierList(original.manager)
) {

    init {
        this.containingClass = containingClass
        if (original.safeAs<KtNamedFunction>()?.isTopLevel == true) {
            addModifier(PsiModifier.STATIC)
        }
    }

    override fun hasModifierProperty(name: String): Boolean {
        if (name == PsiModifier.PUBLIC || name == PsiModifier.PROTECTED || name == PsiModifier.PRIVATE) {
            if (original.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
                return name == PsiModifier.PRIVATE
            }
            if (original.hasModifier(KtTokens.PROTECTED_KEYWORD)) {
                return name == PsiModifier.PROTECTED
            }

            // TODO: inherited via override

            return name == PsiModifier.PUBLIC
        }

        // TODO: modality, special keywords, such as strictfp, synchronized, external, etc.

        return super.hasModifierProperty(name)
    }

    protected val baseResolveProviderService: BaseKotlinUastResolveProviderService by lz {
        ServiceManager.getService(BaseKotlinUastResolveProviderService::class.java)
            ?: error("${BaseKotlinUastResolveProviderService::class.java.name} is not available for ${this::class.simpleName}")
    }

    private val _annotations: Array<PsiAnnotation> by lz {
        val annotations = SmartList<PsiAnnotation>()

        val isUnitFunction = original is KtFunction && _returnType == PsiType.VOID
        // Do not annotate Unit function
        if (!isUnitFunction) {
            val nullability = baseResolveProviderService.nullability(original)
            if (nullability != null && nullability != KtTypeNullability.UNKNOWN) {
                annotations.add(
                    UastFakeLightNullabilityAnnotation(nullability, this)
                )
            }
        }
        original.annotationEntries.mapTo(annotations) { entry ->
            KtLightAnnotationForSourceEntry(
                name = entry.shortName?.identifier,
                lazyQualifiedName = { baseResolveProviderService.qualifiedAnnotationName(entry) },
                kotlinOrigin = entry,
                parent = original,
            )
        }

        if (annotations.isNotEmpty()) annotations.toTypedArray() else PsiAnnotation.EMPTY_ARRAY
    }

    override fun getAnnotations(): Array<PsiAnnotation> {
        return _annotations
    }

    override fun hasAnnotation(fqn: String): Boolean {
        return _annotations.find { it.hasQualifiedName(fqn) } != null
    }

    override fun isDeprecated(): Boolean {
        return hasAnnotation(StandardClassIds.Annotations.Deprecated.asFqNameString()) ||
                hasAnnotation(CommonClassNames.JAVA_LANG_DEPRECATED) ||
                super.isDeprecated()
    }

    override fun isConstructor(): Boolean {
        return original is KtConstructor<*>
    }

    private val _returnType: PsiType? by lz {
        baseResolveProviderService.getType(original, this)
    }

    override fun getReturnType(): PsiType? {
        return _returnType
    }

    override fun getParent(): PsiElement? = containingClass

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UastFakeLightMethodBase<*>

        if (original != other.original) return false

        return true
    }

    override fun hashCode(): Int = original.hashCode()
}

private class UastFakeLightNullabilityAnnotation(
    private val nullability: KtTypeNullability,
    parent: PsiElement
) : KtLightAbstractAnnotation(parent) {

    override val kotlinOrigin: KtCallElement?
        get() = null

    override fun findAttributeValue(attributeName: String?): PsiAnnotationMemberValue? = null

    override fun findDeclaredAttributeValue(attributeName: String?): PsiAnnotationMemberValue? = null

    override fun getNameReferenceElement(): PsiJavaCodeReferenceElement? = null

    override fun getParameterList(): PsiAnnotationParameterList = KtLightEmptyAnnotationParameterList(this)

    override fun getQualifiedName(): String? =
        when (nullability) {
            KtTypeNullability.NON_NULLABLE -> NotNull::class.qualifiedName
            KtTypeNullability.NULLABLE -> Nullable::class.qualifiedName
            KtTypeNullability.UNKNOWN -> null
        }

    override fun toString() = "@$qualifiedName"

    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?): T = cannotModify()
}