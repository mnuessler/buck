/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.ReactNativeBundle;
import com.facebook.buck.js.ReactNativeLibraryArgs;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;

import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
public class NewNativeTargetProjectMutator {
  private static final Logger LOG = Logger.get(NewNativeTargetProjectMutator.class);
  private static final String REACT_NATIVE_PACKAGE_TEMPLATE = "rn-package.st";

  public static class Result {
    public final PBXNativeTarget target;
    public final PBXGroup targetGroup;

    private Result(PBXNativeTarget target, PBXGroup targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;

  private ProductType productType = ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName = "";
  private ImmutableList<String> targetGroupPath = ImmutableList.of();
  private Optional<String> gid = Optional.absent();
  private ImmutableSet<SourceWithFlags> sourcesWithFlags = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeSources = ImmutableSet.of();
  private ImmutableSet<SourcePath> publicHeaders = ImmutableSet.of();
  private ImmutableSet<SourcePath> privateHeaders = ImmutableSet.of();
  private Optional<SourcePath> prefixHeader = Optional.absent();
  private boolean shouldGenerateCopyHeadersPhase = true;
  private ImmutableSet<FrameworkPath> frameworks = ImmutableSet.of();
  private ImmutableSet<PBXFileReference> archives = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescription.Arg> recursiveResources = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescription.Arg> directResources = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescription.Arg> recursiveAssetCatalogs = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescription.Arg> directAssetCatalogs = ImmutableSet.of();
  private Path assetCatalogBuildScript = Paths.get("");
  private Iterable<TargetNode<?>> preBuildRunScriptPhases = ImmutableList.of();
  private Iterable<PBXBuildPhase> copyFilesPhases = ImmutableList.of();
  private Iterable<TargetNode<?>> postBuildRunScriptPhases = ImmutableList.of();
  private boolean skipRNBundle = false;
  private Collection<Path> additionalRunScripts = ImmutableList.of();

  public NewNativeTargetProjectMutator(
      PathRelativizer pathRelativizer,
      Function<SourcePath, Path> sourcePathResolver) {
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
  }

  /**
   * Set product related configuration.
   *
   * @param productType       declared product type
   * @param productName       product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      ProductType productType,
      String productName,
      Path productOutputPath) {
    this.productName = productName;
    this.productType = productType;
    this.productOutputPath = productOutputPath;
    return this;
  }

  public NewNativeTargetProjectMutator setGid(Optional<String> gid) {
    this.gid = gid;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetName(String targetName) {
    this.targetName = targetName;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetGroupPath(ImmutableList<String> targetGroupPath) {
    this.targetGroupPath = targetGroupPath;
    return this;
  }

  public NewNativeTargetProjectMutator setSourcesWithFlags(
      Set<SourceWithFlags> sourcesWithFlags) {
    this.sourcesWithFlags = ImmutableSet.copyOf(sourcesWithFlags);
    return this;
  }

  public NewNativeTargetProjectMutator setExtraXcodeSources(
      Set<SourcePath> extraXcodeSources) {
    this.extraXcodeSources = ImmutableSet.copyOf(extraXcodeSources);
    return this;
  }

  public NewNativeTargetProjectMutator setPublicHeaders(
      Set<SourcePath> publicHeaders) {
    this.publicHeaders = ImmutableSet.copyOf(publicHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrivateHeaders(
      Set<SourcePath> privateHeaders) {
    this.privateHeaders = ImmutableSet.copyOf(privateHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrefixHeader(Optional<SourcePath> prefixHeader) {
    this.prefixHeader = prefixHeader;
    return this;
  }

  public NewNativeTargetProjectMutator setShouldGenerateCopyHeadersPhase(boolean value) {
    this.shouldGenerateCopyHeadersPhase = value;
    return this;
  }

  public NewNativeTargetProjectMutator setFrameworks(Set<FrameworkPath> frameworks) {
    this.frameworks = ImmutableSet.copyOf(frameworks);
    return this;
  }

  public NewNativeTargetProjectMutator setArchives(Set<PBXFileReference> archives) {
    this.archives = ImmutableSet.copyOf(archives);
    return this;
  }

  public NewNativeTargetProjectMutator setRecursiveResources(
      Set<AppleResourceDescription.Arg> recursiveResources) {
    this.recursiveResources = ImmutableSet.copyOf(recursiveResources);
    return this;
  }

  public NewNativeTargetProjectMutator setDirectResources(
      ImmutableSet<AppleResourceDescription.Arg> directResources) {
    this.directResources = directResources;
    return this;
  }

  public NewNativeTargetProjectMutator setPreBuildRunScriptPhases(Iterable<TargetNode<?>> phases) {
    preBuildRunScriptPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator setCopyFilesPhases(Iterable<PBXBuildPhase> phases) {
    copyFilesPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator setPostBuildRunScriptPhases(Iterable<TargetNode<?>> phases) {
    postBuildRunScriptPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator skipReactNativeBundle(boolean skipRNBundle) {
    this.skipRNBundle = skipRNBundle;
    return this;
  }

  public void setAdditionalRunScripts(Collection<Path> scripts) {
    additionalRunScripts = scripts;
  }

  /**
   * @param assetCatalogBuildScript Path of the asset catalog build script relative to repo root.
   * @param recursiveAssetCatalogs List of asset catalog targets of targetNode and dependencies of
   *                               targetNode.
   */
  public NewNativeTargetProjectMutator setRecursiveAssetCatalogs(
      Path assetCatalogBuildScript,
      Set<AppleAssetCatalogDescription.Arg> recursiveAssetCatalogs) {
    this.assetCatalogBuildScript = assetCatalogBuildScript;
    this.recursiveAssetCatalogs = ImmutableSet.copyOf(recursiveAssetCatalogs);
    return this;
  }

  /**
   * @param directAssetCatalogs List of asset catalog targets targetNode directly depends on
   */
  public NewNativeTargetProjectMutator setDirectAssetCatalogs(
      Set<AppleAssetCatalogDescription.Arg> directAssetCatalogs) {
    this.directAssetCatalogs = ImmutableSet.copyOf(directAssetCatalogs);
    return this;
  }

  public Result buildTargetAndAddToProject(PBXProject project)
      throws NoSuchBuildTargetException {
    PBXNativeTarget target = new PBXNativeTarget(targetName);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateDescendantGroupByPath(targetGroupPath);
    targetGroup = targetGroup.getOrCreateChildGroupByName(targetName);

    if (gid.isPresent()) {
      target.setGlobalID(gid.get());
    }

    // Phases
    addRunScriptBuildPhases(target, preBuildRunScriptPhases);
    addPhasesAndGroupsForSources(target, targetGroup);
    addFrameworksBuildPhase(project, target);
    addResourcesFileReference(targetGroup);
    addResourcesBuildPhase(target, targetGroup);
    addAssetCatalogFileReference(targetGroup);
    addAssetCatalogBuildPhase(target);
    target.getBuildPhases().addAll((Collection<? extends PBXBuildPhase>) copyFilesPhases);
    addRunScriptBuildPhases(target, postBuildRunScriptPhases);
    addRawScriptBuildPhases(target);

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
            productOutputPath,
            Optional.<String>absent()));
    target.setProductName(productName);
    target.setProductReference(productReference);
    target.setProductType(productType);

    project.getTargets().add(target);
    return new Result(target, targetGroup);
  }

  private void addPhasesAndGroupsForSources(PBXNativeTarget target, PBXGroup targetGroup) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    traverseGroupsTreeAndHandleSources(
        sourcesGroup,
        sourcesBuildPhase,
        // We still want to create groups for header files even if header build phases
        // are replaced with header maps.
        !shouldGenerateCopyHeadersPhase
            ? Optional.<PBXHeadersBuildPhase>absent()
            : Optional.of(headersBuildPhase),
        RuleUtils.createGroupsFromSourcePaths(
            new Function<SourcePath, Path>() {
              @Override
              public Path apply(SourcePath sourcePath) {
                return pathRelativizer.outputPathToSourcePath(sourcePath);
              }
            },
            sourcesWithFlags,
            extraXcodeSources,
            publicHeaders,
            privateHeaders));

    if (prefixHeader.isPresent()) {
      SourceTreePath prefixHeaderSourceTreePath = new SourceTreePath(
          PBXReference.SourceTree.GROUP,
          pathRelativizer.outputPathToSourcePath(prefixHeader.get()),
          Optional.<String>absent());
      sourcesGroup.getOrCreateFileReferenceBySourceTreePath(prefixHeaderSourceTreePath);
    }

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private void traverseGroupsTreeAndHandleSources(
      final PBXGroup sourcesGroup,
      final PBXSourcesBuildPhase sourcesBuildPhase,
      final Optional<PBXHeadersBuildPhase> headersBuildPhase,
      Iterable<GroupedSource> groupedSources) {
    GroupedSource.Visitor visitor = new GroupedSource.Visitor() {
      @Override
      public void visitSourceWithFlags(SourceWithFlags sourceWithFlags) {
        addSourcePathToSourcesBuildPhase(
            sourceWithFlags,
            sourcesGroup,
            sourcesBuildPhase);
      }

      @Override
      public void visitPublicHeader(SourcePath publicHeader) {
        addSourcePathToHeadersBuildPhase(
            publicHeader,
            sourcesGroup,
            headersBuildPhase,
            HeaderVisibility.PUBLIC);
      }

      @Override
      public void visitPrivateHeader(SourcePath privateHeader) {
        addSourcePathToHeadersBuildPhase(
            privateHeader,
            sourcesGroup,
            headersBuildPhase,
            HeaderVisibility.PRIVATE);
      }

      @Override
      public void visitSourceGroup(
          String sourceGroupName,
          Path sourceGroupPathRelativeToTarget,
          List<GroupedSource> sourceGroup) {
        PBXGroup newSourceGroup = sourcesGroup.getOrCreateChildGroupByName(sourceGroupName);
        newSourceGroup.setSourceTree(PBXReference.SourceTree.SOURCE_ROOT);
        newSourceGroup.setPath(sourceGroupPathRelativeToTarget.toString());
        // Sources groups stay in the order in which they're in the GroupedSource.
        newSourceGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
        traverseGroupsTreeAndHandleSources(
            newSourceGroup,
            sourcesBuildPhase,
            headersBuildPhase,
            sourceGroup);
      }
    };
    for (GroupedSource groupedSource : groupedSources) {
      groupedSource.visit(visitor);
    }
  }

  private void addSourcePathToSourcesBuildPhase(
      SourceWithFlags sourceWithFlags,
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase) {
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(
                sourcePathResolver.apply(sourceWithFlags.getSourcePath())),
            Optional.<String>absent()));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);
    List<String> customFlags = sourceWithFlags.getFlags();
    if (!customFlags.isEmpty()) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", Joiner.on(' ').join(customFlags));
      buildFile.setSettings(Optional.of(settings));
    }
    LOG.verbose(
        "Added source path %s to group %s, flags %s, PBXFileReference %s",
        sourceWithFlags,
        sourcesGroup.getName(),
        customFlags,
        fileReference);
  }

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      HeaderVisibility visibility) {
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(headerPath),
            Optional.<String>absent()));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    if (visibility != HeaderVisibility.PRIVATE) {
      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(AppleHeaderVisibilities.toXcodeAttribute(visibility))));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    if (headersBuildPhase.isPresent()) {
      headersBuildPhase.get().getFiles().add(buildFile);
      LOG.verbose(
          "Added header path %s to headers group %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          fileReference);
    } else {
      LOG.verbose(
          "Skipped header path %s to headers group %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          fileReference);
    }
  }

  private void addFrameworksBuildPhase(PBXProject project, PBXNativeTarget target) {
    if (frameworks.isEmpty() && archives.isEmpty()) {
      return;
    }

    PBXGroup sharedFrameworksGroup =
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks");
    PBXFrameworksBuildPhase frameworksBuildPhase = new PBXFrameworksBuildPhase();
    target.getBuildPhases().add(frameworksBuildPhase);

    for (FrameworkPath framework : frameworks) {
      SourceTreePath sourceTreePath;
      if (framework.getSourceTreePath().isPresent()) {
        sourceTreePath = framework.getSourceTreePath().get();
      } else if (framework.getSourcePath().isPresent()) {
        sourceTreePath = new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(framework.getSourcePath().get()),
            Optional.<String>absent());
      } else {
        throw new RuntimeException();
      }
      PBXFileReference fileReference =
          sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(sourceTreePath);
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
    }

    for (PBXFileReference archive : archives) {
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(archive));
    }
  }

  private void addResourcesFileReference(PBXGroup targetGroup) {
    addResourcesFileReference(
        targetGroup,
        directResources,
        Functions.<Void>constant(null),
        Functions.<Void>constant(null));
  }

  private PBXBuildPhase addResourcesBuildPhase(PBXNativeTarget target, PBXGroup targetGroup) {
    final PBXBuildPhase phase = new PBXResourcesBuildPhase();
    addResourcesFileReference(
        targetGroup,
        recursiveResources,
        new Function<PBXFileReference, Void>() {
          @Override
          public Void apply(PBXFileReference input) {
            PBXBuildFile buildFile = new PBXBuildFile(input);
            phase.getFiles().add(buildFile);
            return null;
          }
        },
        new Function<PBXVariantGroup, Void>() {
          @Override
          public Void apply(PBXVariantGroup input) {
            PBXBuildFile buildFile = new PBXBuildFile(input);
            phase.getFiles().add(buildFile);
            return null;
          }
        });
    if (!phase.getFiles().isEmpty()) {
      target.getBuildPhases().add(phase);
      LOG.debug("Added resources build phase %s", phase);
    }
    return phase;
  }

  private void addResourcesFileReference(
      PBXGroup targetGroup,
      ImmutableSet<AppleResourceDescription.Arg> resources,
      Function<? super PBXFileReference, Void> resourceCallback,
      Function<? super PBXVariantGroup, Void> variantGroupCallback) {
    if (resources.isEmpty()) {
      return;
    }

    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    for (AppleResourceDescription.Arg resource : resources) {
      for (Path path : Iterables.transform(resource.files, sourcePathResolver)) {
        PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(path),
                Optional.<String>absent()));
        resourceCallback.apply(fileReference);
      }
      for (Path path : Iterables.transform(resource.dirs, sourcePathResolver)) {
        PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(path),
                Optional.of("folder")));
        resourceCallback.apply(fileReference);
      }

      Map<String, PBXVariantGroup> variantGroups = Maps.newHashMap();
      for (SourcePath variantSourcePath : resource.variants.get()) {
        String lprojSuffix = ".lproj";
        Path variantFilePath = sourcePathResolver.apply(variantSourcePath);
        Path variantDirectory = variantFilePath.getParent();
        if (variantDirectory == null || !variantDirectory.toString().endsWith(lprojSuffix)) {
          throw new HumanReadableException(
              "Variant files have to be in a directory with name ending in '.lproj', " +
                  "but '%s' is not.",
              variantFilePath);
        }
        String variantDirectoryName = variantDirectory.getFileName().toString();
        String variantLocalization =
            variantDirectoryName.substring(0, variantDirectoryName.length() - lprojSuffix.length());
        String variantFileName = variantFilePath.getFileName().toString();
        PBXVariantGroup variantGroup = variantGroups.get(variantFileName);
        if (variantGroup == null) {
          variantGroup = resourcesGroup.getOrCreateChildVariantGroupByName(variantFileName);
          variantGroupCallback.apply(variantGroup);
          variantGroups.put(variantFileName, variantGroup);
        }
        SourceTreePath sourceTreePath = new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(variantSourcePath),
            Optional.<String>absent());
        variantGroup.getOrCreateVariantFileReferenceByNameAndSourceTreePath(
            variantLocalization,
            sourceTreePath);
      }
    }
  }

  private void addAssetCatalogFileReference(PBXGroup targetGroup) {
    if (directAssetCatalogs.isEmpty()) {
      return;
    }

    // Asset catalogs go in the resources group also.
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    for (AppleAssetCatalogDescription.Arg assetCatalog : directAssetCatalogs) {
      for (Path dir : assetCatalog.dirs) {
        Path pathRelativeToProjectRoot = pathRelativizer.outputDirToRootRelative(dir);

        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativeToProjectRoot,
                Optional.<String>absent()));

        LOG.debug("Resolved asset catalog path %s, result %s", dir, pathRelativeToProjectRoot);
      }
    }
  }

  private void addAssetCatalogBuildPhase(PBXNativeTarget target) {
    if (recursiveAssetCatalogs.isEmpty()) {
      return;
    }

    ImmutableList.Builder<String> assetCatalogsBuilder = ImmutableList.builder();
    for (AppleAssetCatalogDescription.Arg assetCatalog : recursiveAssetCatalogs) {
      for (Path dir : assetCatalog.dirs) {
        Path pathRelativeToProjectRoot = pathRelativizer.outputDirToRootRelative(dir);

        LOG.debug("Resolved asset catalog path %s, result %s", dir, pathRelativeToProjectRoot);

        String bundlePath = "$PROJECT_DIR/" + pathRelativeToProjectRoot.toString();
        assetCatalogsBuilder.add(bundlePath);
      }
    }

    ImmutableList<String> assetCatalogs = assetCatalogsBuilder.build();

    // Map asset catalog paths to their shell script arguments relative to the project's root
    Path buildScript = pathRelativizer.outputDirToRootRelative(assetCatalogBuildScript);
    StringBuilder scriptBuilder = new StringBuilder("set -e\n");
    scriptBuilder
        .append("TMPDIR=`mktemp -d -t buckAssetCatalogs.XXXXXX`\n")
        .append("trap \"rm -rf '${TMPDIR}'\" exit\n");
    if (assetCatalogs.size() != 0) {
      scriptBuilder
          .append("COMMON_ARGS_FILE=\"${TMPDIR}\"/common_args\n")
          .append("cat <<EOT >\"${COMMON_ARGS_FILE}\"\n");

      Joiner.on('\n').appendTo(scriptBuilder, assetCatalogs);

      scriptBuilder
          .append("\n")
          .append("EOT\n")
          .append("\"${PROJECT_DIR}/\"")
          .append(buildScript.toString())
          .append(" @\"${COMMON_ARGS_FILE}\"\n");
    }

    PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();
    target.getBuildPhases().add(phase);
    phase.setShellPath("/bin/bash");
    phase.setShellScript(scriptBuilder.toString());
    LOG.debug("Added asset catalog build phase %s", phase);
  }

  private void addRunScriptBuildPhases(
      PBXNativeTarget target,
      Iterable<TargetNode<?>> nodes) throws NoSuchBuildTargetException{
    for (TargetNode<?> node : nodes) {
      // TODO(user): Check and validate dependencies of the script. If it depends on libraries etc.
      // we can't handle it currently.
      PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
      target.getBuildPhases().add(shellScriptBuildPhase);
      if (GenruleDescription.TYPE.equals(node.getType())) {
        GenruleDescription.Arg arg = (GenruleDescription.Arg) node.getConstructorArg();
        for (Path path : Iterables.transform(arg.srcs.get(), sourcePathResolver)) {
          shellScriptBuildPhase.getInputPaths().add(
              pathRelativizer.outputDirToRootRelative(path).toString());
        }
        if (arg.cmd.isPresent() && !arg.cmd.get().isEmpty()) {
          shellScriptBuildPhase.setShellScript(arg.cmd.get());
        } else if (arg.bash.isPresent() || arg.cmdExe.isPresent()) {
          throw new IllegalStateException("Shell script phase only supports cmd for genrule.");
        }
        if (!arg.out.isEmpty()) {
          shellScriptBuildPhase.getOutputPaths().add(arg.out);
        }
      } else if (XcodePrebuildScriptDescription.TYPE.equals(node.getType())) {
        XcodePrebuildScriptDescription.Arg arg =
            (XcodePrebuildScriptDescription.Arg) node.getConstructorArg();
        shellScriptBuildPhase.setShellScript(arg.cmd);
      } else if (XcodePostbuildScriptDescription.TYPE.equals(node.getType())) {
        XcodePostbuildScriptDescription.Arg arg =
            (XcodePostbuildScriptDescription.Arg) node.getConstructorArg();
        shellScriptBuildPhase.setShellScript(arg.cmd);
      } else if (IosReactNativeLibraryDescription.TYPE.equals(node.getType())) {
        shellScriptBuildPhase.setShellScript(generateXcodeShellScript(node));
      } else {
        // unreachable
        throw new IllegalStateException("Invalid rule type for shell script build phase");
      }
    }
  }

  private void addRawScriptBuildPhases(PBXNativeTarget target) {
    for (Path runScript : additionalRunScripts) {
      PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();
      phase.setShellScript(runScript.toString());
      target.getBuildPhases().add(phase);
    }
  }

  private String generateXcodeShellScript(TargetNode<?> targetNode) {
    Preconditions.checkArgument(targetNode.getConstructorArg() instanceof ReactNativeLibraryArgs);

    ST template;
    try {
      template = new ST(
          Resources.toString(
              Resources.getResource(
                  NewNativeTargetProjectMutator.class,
                  REACT_NATIVE_PACKAGE_TEMPLATE),
              Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("There was an error loading 'rn_package.st' template", e);
    }

    ReactNativeLibraryArgs args = (ReactNativeLibraryArgs) targetNode.getConstructorArg();

    template.add("bundle_name", args.bundleName);
    template.add("skip_rn_bundle", skipRNBundle);

    ProjectFilesystem filesystem = targetNode.getRuleFactoryParams().getProjectFilesystem();
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Path jsOutput = ReactNativeBundle.getPathToJSBundleDir(buildTarget).resolve(args.bundleName);
    template.add("built_bundle_path", filesystem.resolve(jsOutput));

    Path resourceOutput = ReactNativeBundle.getPathToResources(buildTarget);
    template.add("built_resources_path", filesystem.resolve(resourceOutput));

    Path sourceMap = ReactNativeBundle.getPathToSourceMap(buildTarget);
    template.add("built_source_map_path", filesystem.resolve(sourceMap));

    return template.render();
  }
}
