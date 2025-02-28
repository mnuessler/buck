/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  private static final SourceList EMPTY_HEADERS = SourceList.ofUnnamedSources(
      ImmutableSortedSet.<SourcePath>of());

  static final String XCASSETS_DIRECTORY_EXTENSION = ".xcassets";
  private static final String MERGED_ASSET_CATALOG_NAME = "Merged";

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  public static Optional<Path> getPathToHeaderSymlinkTree(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderVisibility headerVisibility) {
    if (!targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(
            targetNode.getBuildTarget().getUnflavoredBuildTarget(),
            "%s" + AppleHeaderVisibilities.getHeaderSymlinkTreeSuffix(headerVisibility)));
  }

  public static Path getHeaderPathPrefix(
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    return Paths.get(arg.headerPathPrefix.or(buildTarget.getShortName()));
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPublicCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      AppleNativeTargetDescriptionArg arg) {
    // The exported headers in the populated cxx constructor arg will contain exported headers from
    // the apple constructor arg with the public include style.
    return AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver,
                headerPathPrefix,
                arg.exportedHeaders.or(EMPTY_HEADERS));
  }

  public static ImmutableSortedMap<String, SourcePath> convertAppleHeadersToPrivateCxxHeaders(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      AppleNativeTargetDescriptionArg arg) {
    // The private headers will contain exported headers with the private include style and private
    // headers with both styles.
    return ImmutableSortedMap.<String, SourcePath>naturalOrder()
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver,
                arg.headers.or(EMPTY_HEADERS)))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
                pathResolver,
                headerPathPrefix,
                arg.headers.or(EMPTY_HEADERS)))
        .putAll(
            AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
                pathResolver,
                arg.exportedHeaders.or(EMPTY_HEADERS)))
        .build();
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> parseAppleHeadersForUseFromOtherTargets(
      Function<SourcePath, Path> pathResolver,
      Path headerPathPrefix,
      SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. For use from other targets, prepend their names
      // with the header path prefix.
      return convertToFlatCxxHeaders(
          headerPathPrefix,
          pathResolver,
          headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. Just use the specified map.
      return headers.getNamedSources().get();
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, SourcePath> parseAppleHeadersForUseFromTheSameTarget(
      Function<SourcePath, Path> pathResolver,
      SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      // The user specified a set of header files. Headers can be included from the same target
      // using only their file name without a prefix.
      return convertToFlatCxxHeaders(
          Paths.get(""),
          pathResolver,
          headers.getUnnamedSources().get());
    } else {
      // The user specified a map from include paths to header files. There is nothing we need to
      // add on top of the exported headers.
      return ImmutableMap.of();
    }
  }

  @VisibleForTesting
  static ImmutableSortedMap<String, SourcePath> convertToFlatCxxHeaders(
      Path headerPathPrefix,
      Function<SourcePath, Path> sourcePathResolver,
      Set<SourcePath> headerPaths) {
    ImmutableSortedMap.Builder<String, SourcePath> cxxHeaders = ImmutableSortedMap.naturalOrder();
    for (SourcePath headerPath : headerPaths) {
      Path fileName = sourcePathResolver.apply(headerPath).getFileName();
      String key = headerPathPrefix.resolve(fileName).toString();
      cxxHeaders.put(key, headerPath);
    }
    return cxxHeaders.build();
  }

  public static void populateCxxConstructorArg(
      SourcePathResolver resolver,
      CxxConstructorArg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    // The resulting cxx constructor arg will have no exported headers and both headers and exported
    // headers specified in the apple arg will be available with both public and private include
    // styles.
    ImmutableSortedMap<String, SourcePath> headerMap =
        ImmutableSortedMap.<String, SourcePath>naturalOrder()
            .putAll(
                convertAppleHeadersToPublicCxxHeaders(
                    resolver.getPathFunction(),
                    headerPathPrefix,
                    arg))
            .putAll(
                convertAppleHeadersToPrivateCxxHeaders(
                    resolver.getPathFunction(),
                    headerPathPrefix,
                    arg))
            .build();

    output.srcs = arg.srcs;
    output.platformSrcs = Optional.of(
        PatternMatchedCollection.<ImmutableSortedSet<SourceWithFlags>>of());
    output.headers = Optional.of(SourceList.ofNamedSources(headerMap));
    output.platformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.prefixHeader = arg.prefixHeader;
    output.compilerFlags = arg.compilerFlags;
    output.platformCompilerFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.preprocessorFlags = arg.preprocessorFlags;
    output.platformPreprocessorFlags = arg.platformPreprocessorFlags;
    output.langPreprocessorFlags = arg.langPreprocessorFlags;
    output.linkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.libraries.transform(librariesToLinkerFlagsFunction(resolver)).get())
            .append(arg.linkerFlags.get())
            .toList());
    output.platformLinkerFlags = Optional.of(PatternMatchedCollection.<ImmutableList<String>>of());
    output.frameworks = arg.frameworks;
    output.libraries = arg.libraries;
    output.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.deps = arg.deps;
    // This is intentionally an empty string; we put all prefixes into
    // the header map itself.
    output.headerNamespace = Optional.of("");
    output.cxxRuntimeType = Optional.absent();
    output.tests = arg.tests;
  }

  public static void populateCxxBinaryDescriptionArg(
      SourcePathResolver resolver,
      CxxBinaryDescription.Arg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget);
    output.linkStyle = Optional.absent();
  }

  public static void populateCxxLibraryDescriptionArg(
      SourcePathResolver resolver,
      CxxLibraryDescription.Arg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      boolean linkWhole) {
    populateCxxConstructorArg(
        resolver,
        output,
        arg,
        buildTarget);
    Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(arg, buildTarget);
    output.headers = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPrivateCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPreprocessorFlags = arg.exportedPreprocessorFlags;
    output.exportedHeaders = Optional.of(
        SourceList.ofNamedSources(
            convertAppleHeadersToPublicCxxHeaders(
                resolver.getPathFunction(),
                headerPathPrefix,
                arg)));
    output.exportedPlatformHeaders = Optional.of(PatternMatchedCollection.<SourceList>of());
    output.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    output.exportedPlatformPreprocessorFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    output.exportedLinkerFlags = Optional.of(
        FluentIterable
            .from(arg.frameworks.transform(frameworksToLinkerFlagsFunction(resolver)).get())
            .append(arg.libraries.transform(librariesToLinkerFlagsFunction(resolver)).get())
            .append(arg.exportedLinkerFlags.get())
            .toList());
    output.exportedPlatformLinkerFlags = Optional.of(
        PatternMatchedCollection.<ImmutableList<String>>of());
    output.soname = Optional.absent();
    output.forceStatic = Optional.of(false);
    output.linkWhole = Optional.of(linkWhole);
    output.supportedPlatformsRegex = Optional.absent();
    output.canBeAsset = arg.canBeAsset;
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<String>> frameworksToLinkerFlagsFunction(final SourcePathResolver resolver) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableSortedSet<FrameworkPath> input) {
        return FluentIterable
            .from(input)
            .transformAndConcat(linkerFlagsForFrameworkPathFunction(resolver.getPathFunction()))
            .toList();
      }
    };
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<String>> librariesToLinkerFlagsFunction(final SourcePathResolver resolver) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableSortedSet<FrameworkPath> input) {
        return FluentIterable
            .from(input)
            .transformAndConcat(linkerFlagsForLibraryFunction(resolver.getPathFunction()))
            .toList();
      }
    };
  }

  @VisibleForTesting
  static Function<
      ImmutableSortedSet<FrameworkPath>,
      ImmutableList<Path>> frameworksToSearchPathsFunction(
      final SourcePathResolver resolver,
      final AppleSdkPaths appleSdkPaths) {
    return new Function<ImmutableSortedSet<FrameworkPath>, ImmutableList<Path>>() {
      @Override
      public ImmutableList<Path> apply(ImmutableSortedSet<FrameworkPath> frameworkPaths) {
        return FluentIterable
            .from(frameworkPaths)
            .transform(
                FrameworkPath.getExpandedSearchPathFunction(
                    resolver.getPathFunction(),
                    appleSdkPaths.resolveFunction()))
            .toList();
      }
    };
  }

  @VisibleForTesting
  static Function<
      ImmutableList<String>,
      ImmutableList<String>> expandSdkVariableReferencesFunction(
      final AppleSdkPaths appleSdkPaths) {
    return new Function<ImmutableList<String>, ImmutableList<String>>() {
      @Override
      public ImmutableList<String> apply(ImmutableList<String> flags) {
        return FluentIterable
            .from(flags)
            .transform(appleSdkPaths.replaceSourceTreeReferencesFunction())
            .toList();
      }
    };
  }

  private static Function<FrameworkPath, Iterable<String>> linkerFlagsForFrameworkPathFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, Iterable<String>>() {
      @Override
      public Iterable<String> apply(FrameworkPath input) {
        return ImmutableList.of("-framework", input.getName(resolver));
      }
    };
  }

  private static Function<FrameworkPath, Iterable<String>> linkerFlagsForLibraryFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, Iterable<String>>() {
      @Override
      public Iterable<String> apply(FrameworkPath input) {
        return ImmutableList.of(
            "-l",
            MorePaths.stripPathPrefixAndExtension(input.getFileName(resolver), "lib"));
      }
    };
  }

  public static Optional<AppleAssetCatalog> createBuildRuleForTransitiveAssetCatalogDependencies(
      TargetGraph targetGraph,
      BuildRuleParams params,
      SourcePathResolver sourcePathResolver,
      ApplePlatform applePlatform,
      Tool actool) {
    TargetNode<?> targetNode = Preconditions.checkNotNull(targetGraph.get(params.getBuildTarget()));

    ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogArgs =
        AppleBuildRules.collectRecursiveAssetCatalogs(targetGraph, ImmutableList.of(targetNode));

    ImmutableSortedSet.Builder<Path> assetCatalogDirsBuilder =
        ImmutableSortedSet.naturalOrder();

    for (AppleAssetCatalogDescription.Arg arg : assetCatalogArgs) {
      assetCatalogDirsBuilder.addAll(arg.dirs);
    }

    ImmutableSortedSet<Path> assetCatalogDirs =
        assetCatalogDirsBuilder.build();

    if (assetCatalogDirs.isEmpty()) {
      return Optional.absent();
    }

    BuildRuleParams assetCatalogParams = params.copyWithChanges(
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(AppleAssetCatalog.FLAVOR)
            .build(),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    return Optional.of(
        new AppleAssetCatalog(
            assetCatalogParams,
            sourcePathResolver,
            applePlatform.getName(),
            actool,
            assetCatalogDirs,
            MERGED_ASSET_CATALOG_NAME));
  }

}
