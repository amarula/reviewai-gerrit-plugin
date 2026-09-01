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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Cooperative cancellation shared by a durable request and all of its asynchronous AI stages. */
public final class AiRequestCancellation {
  private static final String DEFAULT_REASON = "AI review superseded by a newer patch set";
  private static final ThreadLocal<AiRequestCancellation> CURRENT = new ThreadLocal<>();

  private final BooleanSupplier durableSupersessionRequested;
  private boolean supersessionRequested;
  private String reason = DEFAULT_REASON;
  private int workCount;

  public AiRequestCancellation() {
    this(() -> false);
  }

  public AiRequestCancellation(BooleanSupplier durableSupersessionRequested) {
    this.durableSupersessionRequested = durableSupersessionRequested;
  }

  public static AiRequestCancellation current() {
    AiRequestCancellation cancellation = CURRENT.get();
    return cancellation == null ? new AiRequestCancellation() : cancellation;
  }

  public static Scope activate(AiRequestCancellation cancellation) {
    AiRequestCancellation previous = CURRENT.get();
    CURRENT.set(cancellation);
    return () -> {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    };
  }

  public synchronized boolean requestSupersession(String requestedReason) {
    if (supersessionRequested) {
      return false;
    }
    supersessionRequested = true;
    if (requestedReason != null && !requestedReason.isBlank()) {
      reason = requestedReason;
    }
    return true;
  }

  public boolean isSupersessionRequested() {
    synchronized (this) {
      if (supersessionRequested) {
        return true;
      }
    }
    if (durableSupersessionRequested.getAsBoolean()) {
      requestSupersession(DEFAULT_REASON);
      return true;
    }
    return false;
  }

  public void throwIfSupersessionRequested() {
    if (isSupersessionRequested()) {
      throw new AiRequestSupersededException(reason());
    }
  }

  public Work beginWork() {
    synchronized (this) {
      workCount++;
    }
    return new Work();
  }

  public <T> CompletableFuture<T> supplyAsync(WorkSupplier<T> supplier, Executor executor) {
    reserveWork();
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            try {
              throwIfSupersessionRequested();
              return supplier.get();
            } catch (Exception e) {
              throw new CompletionException(e);
            } finally {
              finishWork();
            }
          },
          executor);
    } catch (RuntimeException e) {
      cancelReservedWork();
      throw e;
    }
  }

  public void awaitWorkCompletion() {
    boolean interrupted = false;
    synchronized (this) {
      while (workCount > 0) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private synchronized String reason() {
    return reason;
  }

  private synchronized void reserveWork() {
    workCount++;
  }

  private synchronized void finishWork() {
    workCount--;
    notifyAll();
  }

  private synchronized void cancelReservedWork() {
    workCount--;
    notifyAll();
  }

  public final class Work implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      synchronized (AiRequestCancellation.this) {
        if (closed) {
          return;
        }
        closed = true;
      }
      finishWork();
    }
  }

  @FunctionalInterface
  public interface WorkSupplier<T> {
    T get() throws Exception;
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
