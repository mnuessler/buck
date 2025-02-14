/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PythonPackagedBinary extends PythonBinary implements HasRuntimeDeps {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  @AddToRuleKey
  private final Path pathToPex;
  @AddToRuleKey
  private final ImmutableList<String> buildArgs;
  private final Path pathToPexExecuter;
  @AddToRuleKey
  private final String pexExtension;
  @AddToRuleKey
  private final String mainModule;
  @AddToRuleKey
  private final PythonPackageComponents components;
  @AddToRuleKey
  private final PythonEnvironment pythonEnvironment;
  private final ImmutableSortedSet<BuildRule> runtimeDeps;

  protected PythonPackagedBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path pathToPex,
      ImmutableList<String> buildArgs,
      Path pathToPexExecuter,
      String pexExtension,
      PythonEnvironment pythonEnvironment,
      String mainModule,
      PythonPackageComponents components,
      ImmutableSortedSet<BuildRule> runtimeDeps) {
    super(params, resolver, mainModule, components);
    this.pathToPex = pathToPex;
    this.buildArgs = buildArgs;
    this.pathToPexExecuter = pathToPexExecuter;
    this.pexExtension = pexExtension;
    this.pythonEnvironment = pythonEnvironment;
    this.mainModule = mainModule;
    this.components = components;
    this.runtimeDeps = runtimeDeps;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public Path getBinPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s" + pexExtension);
  }

  @Override
  public Path getPathToOutput() {
    return getBinPath();
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(new PathSourcePath(getProjectFilesystem(), pathToPexExecuter))
        .addArg(new BuildTargetSourcePath(getBuildTarget(), getBinPath()))
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path binPath = getBinPath();

    // Make sure the parent directory exists.
    steps.add(new MkdirStep(binPath.getParent()));

    Path workingDirectory = BuildTargets.getGenPath(
        getBuildTarget(), "__%s__working_directory");
    steps.add(new MakeCleanDirectoryStep(workingDirectory));

    // Generate and return the PEX build step.
    steps.add(
        new PexStep(
            pathToPex,
            buildArgs,
            pythonEnvironment.getPythonPath(),
            workingDirectory,
            binPath,
            mainModule,
            getResolver().getMappedPaths(components.getModules()),
            getResolver().getMappedPaths(components.getResources()),
            getResolver().getMappedPaths(components.getNativeLibraries()),
            ImmutableSet.copyOf(getResolver().getAllPaths(components.getPrebuiltLibraries())),
            components.isZipSafe().or(true)));

    // Record the executable package for caching.
    buildableContext.recordArtifact(getBinPath());

    return steps.build();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return runtimeDeps;
  }

}
