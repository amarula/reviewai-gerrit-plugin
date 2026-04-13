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

package com.googlesource.gerrit.plugins.reviewai.errors.exceptions;

public class AiConnectionFailException extends Exception {
  public AiConnectionFailException() {
    super("Failed to connect to AI services");
  }

  public AiConnectionFailException(String message) {
    super(message);
  }

  public AiConnectionFailException(String message, Throwable cause) {
    super(message, cause);
  }

  public AiConnectionFailException(Throwable cause) {
    super(cause);
  }
}
