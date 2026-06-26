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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AiModelRequestLimiterTest {

  @Test
  public void limitsConcurrentRequests() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiMaxConcurrentRequests()).thenReturn(1);
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    AtomicInteger activeRequests = new AtomicInteger();
    AtomicInteger maxActiveRequests = new AtomicInteger();

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<String> firstRequest =
          executor.submit(
              () ->
                  AiModelRequestLimiter.execute(
                      config,
                      () -> {
                        enterRequest(activeRequests, maxActiveRequests);
                        firstEntered.countDown();
                        await(releaseFirst);
                        activeRequests.decrementAndGet();
                        return "first";
                      }));
      assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

      Future<String> secondRequest =
          executor.submit(
              () ->
                  AiModelRequestLimiter.execute(
                      config,
                      () -> {
                        enterRequest(activeRequests, maxActiveRequests);
                        secondEntered.countDown();
                        activeRequests.decrementAndGet();
                        return "second";
                      }));

      assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
      releaseFirst.countDown();

      assertEquals("first", firstRequest.get(1, TimeUnit.SECONDS));
      assertEquals("second", secondRequest.get(1, TimeUnit.SECONDS));
      assertEquals(1, maxActiveRequests.get());
    }
  }

  private static void enterRequest(AtomicInteger activeRequests, AtomicInteger maxActiveRequests) {
    int active = activeRequests.incrementAndGet();
    maxActiveRequests.updateAndGet(currentMax -> Math.max(currentMax, active));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
