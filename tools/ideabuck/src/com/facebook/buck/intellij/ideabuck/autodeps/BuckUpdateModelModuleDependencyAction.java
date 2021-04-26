/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;

/** Update Module with a new module dependency */
public class BuckUpdateModelModuleDependencyAction implements BuckUpdateModelAction {

  @Override
  public void updateModel(Module editModule, Module importModule, Logger logger) {
    ModuleRootModificationUtil.updateModel(
        editModule,
        (modifiableRootModel -> {
          if (modifiableRootModel.findModuleOrderEntry(importModule) != null) {
            logger.info(
                "No need to modify module "
                    + editModule.getName()
                    + ", already has dependency on "
                    + importModule.getName());
          } else {
            modifiableRootModel.addModuleOrderEntry(importModule);
            logger.info(
                "Successfully added module dependency from "
                    + editModule.getName()
                    + " on "
                    + importModule.getName());
          }
        }));
  }
}