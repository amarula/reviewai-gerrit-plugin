(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.api = {
    createFetchHistory(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-history`);
    },

    createFetchModelInfo(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-agent-model`);
    },

    createSendMessage(plugin, pluginName) {
      return (change, message, modelId, reviewAgent) =>
        plugin.restApi().post(`/changes/${change._number}/${pluginName}~ai-review-message`, {
          message,
          model_id: modelId,
          model_name: modelId,
          review_agent: Boolean(reviewAgent),
        });
    },
  };
})(window);
