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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.Map;
import org.junit.Test;

public class LangChainToolSpecificationFactoryTest {

  @Test
  public void shouldLoadOnDemandToolSpecifications() {
    Map<String, String> toolResources =
        Map.of(
            "config/treeTool.json", "tree",
            "config/getContentTool.json", "get_content",
            "config/grepTool.json", "grep");

    for (Map.Entry<String, String> toolResource : toolResources.entrySet()) {
      LangChainToolSpecificationFactory factory =
          new LangChainToolSpecificationFactory(toolResource.getKey());

      ToolSpecification specification = factory.loadToolSpecification();

      assertNotNull("Tool specification should be loaded", specification);
      assertEquals(toolResource.getValue(), specification.name());
      assertNotNull(specification.parameters());
      assertEquals(JsonObjectSchema.class, specification.parameters().getClass());
    }
  }
}
