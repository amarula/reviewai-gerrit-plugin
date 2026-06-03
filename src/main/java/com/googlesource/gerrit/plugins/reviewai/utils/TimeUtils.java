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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class TimeUtils {
  private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSSSSSSSS";
  private static final ZoneOffset DEFAULT_ZONE_OFFSET = ZoneOffset.UTC;

  public static long getEpochSeconds(String updatedString) {
    LocalDateTime updatedDateTime = LocalDateTime.parse(updatedString, getFormatter());
    return updatedDateTime.toInstant(DEFAULT_ZONE_OFFSET).getEpochSecond();
  }

  public static long getCurrentMillis() {
    return System.currentTimeMillis();
  }

  public static long epochSecondsToMillisOrNow(long epochSeconds) {
    return epochSeconds > 0 ? epochSeconds * 1000 : getCurrentMillis();
  }

  private static DateTimeFormatter getFormatter() {
    return DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
  }
}
