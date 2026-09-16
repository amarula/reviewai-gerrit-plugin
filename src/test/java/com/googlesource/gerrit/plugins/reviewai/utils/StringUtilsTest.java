/*
 * Copyright (c) 2026. Amarula Solutions
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

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import java.nio.file.Files;
import java.util.Locale;
import org.junit.Test;

public class StringUtilsTest {
  @Test
  public void caseConversionsUseLocaleIndependentIdentifiers() throws Exception {
    var cases =
        Files.readAllLines(
            TestResourceLoader.getTestResourcePath().resolve("utils/caseConversions.tsv"));
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      for (String row : cases) {
        String[] values = row.split("\t");
        assertEquals(values[1], StringUtils.convertCamelToSnakeCase(values[0]));
        assertEquals(values[2], StringUtils.convertSnakeToPascalCase(values[1]));
        assertEquals(
            values[2], StringUtils.convertSnakeToPascalCase(values[1].toUpperCase(Locale.ROOT)));
        assertEquals(values[2], StringUtils.capitalizeFirstLetter(values[0]));
      }
    } finally {
      Locale.setDefault(originalLocale);
    }
  }
}
