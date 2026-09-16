package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

/**
 * Token-count estimator that reuses the OpenAI cl100k tokenizer. Useful for vendors that do not
 * expose tokenization APIs but are compatible with OpenAI's encoding.
 */
public class FallbackTokenCountEstimator implements TokenCountEstimator {

  private static final String CL100K_MODEL = "gpt-3.5-turbo-0125";

  private final TokenCountEstimator delegate = new OpenAiTokenCountEstimator(CL100K_MODEL);

  @Override
  public int estimateTokenCountInText(String text) {
    return delegate.estimateTokenCountInText(text);
  }

  @Override
  public int estimateTokenCountInMessage(ChatMessage message) {
    return delegate.estimateTokenCountInMessage(message);
  }

  @Override
  public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
    return delegate.estimateTokenCountInMessages(messages);
  }
}
