package org.jetbrains.plugins.cucumber.java;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiDirectoryFactory;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.CucumberJvmExtensionPoint;
import org.jetbrains.plugins.cucumber.StepDefinitionCreator;
import org.jetbrains.plugins.cucumber.java.steps.JavaStepDefinition;
import org.jetbrains.plugins.cucumber.java.steps.JavaStepDefinitionCreator;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;
import org.jetbrains.plugins.cucumber.steps.CucumberStepsIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * User: Andrey.Vokin
 * Date: 7/16/12
 */
public class JavaCucumberExtension implements CucumberJvmExtensionPoint {
  @Override
  public boolean isStepLikeFile(@NotNull PsiElement child, @NotNull PsiElement parent) {
    return child instanceof PsiJavaFile;
  }

  @NotNull
  @Override
  public List<AbstractStepDefinition> getStepDefinitions(@NotNull PsiFile psiFile) {
    final List<AbstractStepDefinition> newDefs = new ArrayList<AbstractStepDefinition>();
    psiFile.acceptChildren(new JavaRecursiveElementVisitor() {
      @Override
      public void visitMethod(PsiMethod method) {
        super.visitMethod(method);
        if (CucumberJavaUtil.isStepDefinition(method)) {
          newDefs.add(new JavaStepDefinition(method));
        }
      }
    });
    return newDefs;
  }

  @NotNull
  @Override
  public FileType getStepFileType() {
    return JavaFileType.INSTANCE;
  }

  @NotNull
  @Override
  public StepDefinitionCreator getStepDefinitionCreator() {
    return new JavaStepDefinitionCreator();
  }

  @NotNull
  @Override
  public String getDefaultStepFileName() {
    return "StepDef";
  }

  @Override
  public void collectAllStepDefsProviders(@NotNull List<VirtualFile> providers, @NotNull Project project) {
    final Module[] modules = ModuleManager.getInstance(project).getModules();
    for (Module module : modules) {
      if (ModuleType.get(module) instanceof JavaModuleType) {
        final VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        ContainerUtil.addAll(providers, roots);
      }
    }
  }

  @Override
  public void loadStepDefinitionRootsFromLibraries(Module module,
                                                   List<PsiDirectory> newAbstractStepDefinitionsRoots,
                                                   @NotNull Set<String> processedStepDirectories) {
    //To change body of implemented methods use File | Settings | File Templates.
  }

  @Override
  public ResolveResult[] resolveStep(@NotNull final PsiElement element) {
    final CucumberStepsIndex index = CucumberStepsIndex.getInstance(element.getProject());

    if (element instanceof GherkinStep) {
      final GherkinStep step = (GherkinStep)element;
      final List<ResolveResult> result = new ArrayList<ResolveResult>();

      final Set<String> substitutedNameList = step.getSubstitutedNameList();
      if (substitutedNameList.size() == 0) {
        return ResolveResult.EMPTY_ARRAY;
      }
      for (String s : substitutedNameList) {
        final AbstractStepDefinition definition = index.findStepDefinition(element.getContainingFile(), s);
        if (definition != null) {
          result.add(new ResolveResult() {
            @Override
            public PsiElement getElement() {
              return definition.getElement();
            }

            @Override
            public boolean isValidResult() {
              return true;
            }
          });
        }
      }
      return result.toArray(new ResolveResult[result.size()]);
    }

    return ResolveResult.EMPTY_ARRAY;
  }

  @Override
  public void findRelatedStepDefsRoots(@NotNull final Module module, @NotNull final PsiFile featureFile,
                                       @NotNull final List<PsiDirectory> newStepDefinitionsRoots,
                                       @NotNull final Set<String> processedStepDirectories) {

    final ContentEntry[] contentEntries = ModuleRootManager.getInstance(module).getContentEntries();
    for (final ContentEntry contentEntry : contentEntries) {
      final SourceFolder[] sourceFolders = contentEntry.getSourceFolders();
      for (SourceFolder sf : sourceFolders) {
        if (sf.isTestSource()) {
          VirtualFile sfDirectory = sf.getFile();
          if (sfDirectory != null && sfDirectory.isDirectory()) {
            PsiDirectory sourceRoot = PsiDirectoryFactory.getInstance(module.getProject()).createDirectory(sfDirectory);
            newStepDefinitionsRoots.add(sourceRoot);
          }
        }
      }
    }
  }
}