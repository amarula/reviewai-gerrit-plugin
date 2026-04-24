package dev.langchain4j.model.googleai;

import dev.langchain4j.model.TokenCountEstimator;

/**
 * Test stub mimicking the LangChain Google Gemini token estimator API. It deliberately throws when
 * {@link Builder#build()} is invoked with a Gemini model to exercise fallback paths without
 * contacting external services.
 */
public class GoogleAiTokenCountEstimator implements TokenCountEstimator {

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public int estimateTokenCountInText(String text) {
    return 0;
  }

  @Override
  public int estimateTokenCountInMessage(dev.langchain4j.data.message.ChatMessage message) {
    return 0;
  }

  @Override
  public int estimateTokenCountInMessages(
      Iterable<dev.langchain4j.data.message.ChatMessage> messages) {
    return 0;
  }

  public static final class Builder {
    private static String lastModelName;

    private String apiKey;
    private String modelName;

    public static void reset() {
      lastModelName = null;
    }

    public static String getLastModelName() {
      return lastModelName;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("apiKey must be provided");
      }
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public GoogleAiTokenCountEstimator build() {
      lastModelName = modelName;
      if (modelName != null && modelName.startsWith("gemini-")) {
        throw new IllegalArgumentException("Unknown Gemini model: " + modelName);
      }
      if (apiKey == null) {
        throw new IllegalStateException("apiKey must be configured");
      }
      return new GoogleAiTokenCountEstimator();
    }
  }
}
