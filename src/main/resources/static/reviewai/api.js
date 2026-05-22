(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.api = {
    createFetchHistory(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-history`);
    },

    createSendMessage(plugin, pluginName) {
      return (change, message, modelId, reviewAgent, requestId) =>
        plugin.restApi().post(`/changes/${change._number}/${pluginName}~ai-review-message`, {
          message,
          model_id: modelId,
          model_name: modelId,
          review_agent: Boolean(reviewAgent),
          request_id: requestId,
        });
    },

    createFetchMessageStatus(plugin, pluginName) {
      return (change, requestId) =>
        plugin.restApi().post(
          `/changes/${change._number}/${pluginName}~ai-review-message-status`,
          {
            request_id: requestId,
          }
        );
    },
  };
})(window);
