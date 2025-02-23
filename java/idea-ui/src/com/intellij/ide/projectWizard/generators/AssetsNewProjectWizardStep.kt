// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.projectWizard.generators

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.starters.local.GeneratorAsset
import com.intellij.ide.starters.local.GeneratorTemplateFile
import com.intellij.ide.starters.local.generator.AssetsProcessor
import com.intellij.ide.wizard.*
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.file.CanonicalPathUtil.toNioPath
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.file.system.LocalFileSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.UIBundle
import org.jetbrains.annotations.ApiStatus
import java.util.*

@ApiStatus.Experimental
abstract class AssetsNewProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

  lateinit var outputDirectory: String
  private val assets = ArrayList<GeneratorAsset>()
  private val templateProperties = HashMap<String, Any>()
  private val filesToOpen = HashSet<String>()

  @ApiStatus.Internal
  internal fun getTemplateProperties(): Map<String, Any> {
    return templateProperties
  }

  fun addAssets(vararg assets: GeneratorAsset) =
    addAssets(assets.toList())

  fun addAssets(assets: Iterable<GeneratorAsset>) {
    this.assets.addAll(assets)
  }

  fun addTemplateAsset(sourcePath: String, templateName: String, vararg properties: Pair<String, Any>) {
    addTemplateAsset(sourcePath, templateName, properties.toMap())
  }

  fun addTemplateAsset(sourcePath: String, templateName: String, properties: Map<String, Any>) {
    val templateManager = FileTemplateManager.getDefaultInstance()
    val template = templateManager.getInternalTemplate(templateName)
    addAssets(GeneratorTemplateFile(sourcePath, template))
    templateProperties.putAll(properties)
  }

  fun addFilesToOpen(vararg relativeCanonicalPaths: String) =
    addFilesToOpen(relativeCanonicalPaths.toList())

  private fun addFilesToOpen(relativeCanonicalPaths: Iterable<String>) {
    filesToOpen.addAll(relativeCanonicalPaths.map { "$outputDirectory/$it" })
  }

  abstract fun setupAssets(project: Project)

  override fun setupProject(project: Project) {
    setupProjectSafe(project, UIBundle.message("error.project.wizard.new.project.sample.code", context.isCreatingNewProjectInt)) {
      setupAssets(project)

      val generatedFiles = invokeAndWaitIfNeeded {
        runWriteAction {
          AssetsProcessor.getInstance().generateSources(outputDirectory.toNioPath(), assets, templateProperties)
        }
      }

      whenProjectCreated(project) { //IDEA-244863
        reformatCode(project, generatedFiles.mapNotNull { LocalFileSystemUtil.findFile(it) })
        openFilesInEditor(project, filesToOpen.mapNotNull { LocalFileSystemUtil.findFile(it) })
      }
    }
  }

  private fun reformatCode(project: Project, files: List<VirtualFile>) {
    val psiManager = PsiManager.getInstance(project)
    val psiFiles = files.mapNotNull { psiManager.findFile(it) }

    ReformatCodeProcessor(project, psiFiles.toTypedArray(), null, false).run()
  }

  private fun openFilesInEditor(project: Project, files: List<VirtualFile>) {
    val fileEditorManager = FileEditorManager.getInstance(project)
    val projectView = ProjectView.getInstance(project)
    for (file in files) {
      fileEditorManager.openFile(file, true)
      projectView.select(null, file, false)
    }
  }
}