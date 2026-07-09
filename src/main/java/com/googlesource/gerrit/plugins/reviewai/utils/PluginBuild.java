/*
 * Copyright (c) 2026. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.utils;

public final class PluginBuild {
  private static final String DEV_MODULE_CLASS =
      "com.googlesource.gerrit.plugins.reviewai.DevModule";

  private PluginBuild() {}

  public static boolean isProductionBuild() {
    return !isDevBuild();
  }

  public static boolean isDevBuild() {
    try {
      Class.forName(DEV_MODULE_CLASS, false, PluginBuild.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
