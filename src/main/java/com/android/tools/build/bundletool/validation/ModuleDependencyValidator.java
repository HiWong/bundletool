/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.validation;

import static com.android.tools.build.bundletool.model.BundleModuleName.BASE_MODULE_NAME;
import static com.google.common.base.Preconditions.checkArgument;

import com.android.tools.build.bundletool.exceptions.ValidationException;
import com.android.tools.build.bundletool.model.AndroidManifest;
import com.android.tools.build.bundletool.model.BundleModule;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates dependencies between bundle modules.
 *
 * <p>The dependency graph is inferred based on:
 *
 * <ul>
 *   <li>Module names (aka names of top-level directories in the bundle).
 *   <li>Dependency declarations in form of {@code <uses-split name="parent_module"/>} manifest
 *       entries.
 * </ul>
 */
public class ModuleDependencyValidator extends SubValidator {

  @Override
  public void validateAllModules(ImmutableList<BundleModule> modules) {
    checkHasBaseModule(modules);
    checkSplitIds(modules);

    Multimap<String, String> moduleDependenciesMap = buildAdjacencyMap(modules);

    checkNoReflexiveDependencies(moduleDependenciesMap);
    checkModulesHaveUniqueDependencies(moduleDependenciesMap);
    checkReferencedModulesExist(moduleDependenciesMap);
    checkNoCycles(moduleDependenciesMap);
    checkNoInstallTimeToOnDemandModulesDependencies(modules, moduleDependenciesMap);
  }

  /**
   * Builds a map of module dependencies.
   *
   * <p>If module "a" contains {@code <uses-split name="b"/>} manifest entry, then the map contains
   * entry ("a", "b").
   *
   * <p>All modules implicitly depend on the "base" module. Hence the map contains also dependency
   * ("base", "base").
   */
  private static Multimap<String, String> buildAdjacencyMap(ImmutableList<BundleModule> modules) {
    Multimap<String, String> moduleDependenciesMap = ArrayListMultimap.create();

    for (BundleModule module : modules) {
      String moduleName = module.getName().getName();
      AndroidManifest manifest = module.getAndroidManifest();

      checkArgument(
          !moduleDependenciesMap.containsKey(moduleName),
          "Module named '%s' was passed in multiple times.",
          moduleName);

      moduleDependenciesMap.putAll(moduleName, manifest.getUsesSplits());

      // Check that module does not declare explicit dependency on the "base" module
      // (whose split ID actually is empty instead of "base" anyway).
      if (moduleDependenciesMap.containsEntry(moduleName, BASE_MODULE_NAME)) {
        throw ValidationException.builder()
            .withMessage(
                "Module '%s' declares dependency on the '%s' module, which is implicit.",
                moduleName, BASE_MODULE_NAME)
            .build();
      }

      // Add implicit dependency on the base. Also ensures that every module has a key in the map.
      moduleDependenciesMap.put(moduleName, BASE_MODULE_NAME);
    }

    return Multimaps.unmodifiableMultimap(moduleDependenciesMap);
  }

  private static void checkHasBaseModule(ImmutableList<BundleModule> modules) {
    if (!modules.stream().anyMatch(BundleModule::isBaseModule)) {
      throw ValidationException.builder()
          .withMessage("Mandatory '%s' module is missing.", BASE_MODULE_NAME)
          .build();
    }
  }

  private static void checkSplitIds(ImmutableList<BundleModule> modules) {
    // This is rather a sanity check. Currently the bundletool ignores and/or rewrites the
    // <manifest split="..."> attribute, but mismatch should not happen.

    for (BundleModule module : modules) {
      String moduleName = module.getName().getName();
      Optional<String> splitIdFromManifest = module.getAndroidManifest().getSplitId();

      if (module.isBaseModule()) {
        // Follows the Split APK conventions - the base split has empty split ID.
        if (splitIdFromManifest.isPresent()) {
          throw ValidationException.builder()
              .withMessage(
                  "The base module should not declare split ID in the manifest, but it is set to "
                      + "'%s'.",
                  splitIdFromManifest.get())
              .build();
        }
      } else {
        if (splitIdFromManifest.isPresent() && !moduleName.equals(splitIdFromManifest.get())) {
          throw ValidationException.builder()
              .withMessage(
                  "Module '%s' declares in its manifest that the split ID is '%s'. It needs to be"
                      + " either absent or equal to the module name.",
                  moduleName, splitIdFromManifest.get())
              .build();
        }
      }
    }
  }

  private static void checkReferencedModulesExist(Multimap<String, String> moduleDependenciesMap) {
    for (String referencedModule : moduleDependenciesMap.values()) {
      if (!moduleDependenciesMap.containsKey(referencedModule)) {
        throw ValidationException.builder()
            .withMessage(
                "Module '%s' is referenced by <uses-split> but does not exist.", referencedModule)
            .build();
      }
    }
  }

  /** Checks that a module doesn't depend on itself. */
  private static void checkNoReflexiveDependencies(Multimap<String, String> moduleDependenciesMap) {
    for (String moduleName : moduleDependenciesMap.keySet()) {
      // The base module is the only one that will have a self-loop in the dependencies map.
      if (!moduleName.equals(BASE_MODULE_NAME)) {
        if (moduleDependenciesMap.containsEntry(moduleName, moduleName)) {
          throw ValidationException.builder()
              .withMessage("Module '%s' depends on itself via <uses-split>.", moduleName)
              .build();
        }
      }
    }
  }

  /** Checks that a module doesn't declare dependency on another module more than once. */
  private static void checkModulesHaveUniqueDependencies(
      Multimap<String, String> moduleDependenciesMap) {
    for (Entry<String, Collection<String>> entry : moduleDependenciesMap.asMap().entrySet()) {
      String moduleName = entry.getKey();
      Collection<String> moduleDeps = entry.getValue();

      Set<String> alreadyReferencedModules = new HashSet<>();
      for (String moduleDep : moduleDeps) {
        if (!alreadyReferencedModules.add(moduleDep)) {
          throw ValidationException.builder()
              .withMessage(
                  "Module '%s' declares dependency on module '%s' multiple times.",
                  moduleName, moduleDep)
              .build();
        }
      }
    }
  }

  /**
   * Validates that the module dependency graph contains no cycles.
   *
   * <p>Uses two sets of nodes for better time complexity:
   *
   * <ul>
   *   <li>"safe" is a set of modules/nodes that are already known not to be participants in any
   *       dependency cycle. Such nodes don't need to be re-examined again and again.
   *   <li>"visited" is a set of modules/nodes that have been visited during a single recursive
   *       call. When the call does not throw, no cycle has been found and "visited" nodes can be
   *       added to the "safe" set to avoid re-examination later.
   * </ul>
   */
  private static void checkNoCycles(Multimap<String, String> moduleDependenciesMap) {
    Set<String> safe = new HashSet<>();

    for (String moduleName : moduleDependenciesMap.keySet()) {
      Set<String> visited = new HashSet<>();

      // Using LinkedHashSet to preserve dependency order for better error message.
      checkNoCycles(
          moduleName,
          moduleDependenciesMap,
          visited,
          safe,
          /* processing= */ new LinkedHashSet<>());

      safe.addAll(visited);
    }
  }

  private static void checkNoCycles(
      String moduleName,
      Multimap<String, String> moduleDependenciesMap,
      Set<String> visited,
      Set<String> safe,
      Set<String> processing) {
    if (safe.contains(moduleName)) {
      return;
    }

    if (processing.contains(moduleName)) {
      throw ValidationException.builder()
          .withMessage("Found cyclic dependency between modules: %s", processing)
          .build();
    }

    visited.add(moduleName);

    processing.add(moduleName);
    for (String referencedModule : moduleDependenciesMap.get(moduleName)) {
      // Skip  reflexive dependency (base, base).
      if (!moduleName.equals(referencedModule)) {
        checkNoCycles(referencedModule, moduleDependenciesMap, visited, safe, processing);
      }
    }
    processing.remove(moduleName);
  }

  /** Checks that an install-time module does not depend on an on-demand module. */
  private static void checkNoInstallTimeToOnDemandModulesDependencies(
      ImmutableList<BundleModule> modules, Multimap<String, String> moduleDependenciesMap) {
    Map<String, BundleModule> modulesByName =
        modules
            .stream()
            .collect(Collectors.toMap(module -> module.getName().getName(), Function.identity()));

    for (Entry<String, String> dependencyEntry : moduleDependenciesMap.entries()) {
      String moduleName = dependencyEntry.getKey();
      String moduleDep = dependencyEntry.getValue();
      if (!modulesByName.get(moduleName).isDynamicModule()
          && modulesByName.get(moduleDep).isDynamicModule()) {
        throw ValidationException.builder()
            .withMessage(
                "Install-time module '%s' declares dependency on on-demand module '%s'.",
                moduleName, moduleDep)
            .build();
      }
    }
  }
}
