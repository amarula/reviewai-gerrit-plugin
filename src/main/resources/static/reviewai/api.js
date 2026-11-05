(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.api = {
    createFetchHistory(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-history`);
    },

    createSendMessage(plugin, pluginName) {
      return (change, message, modelId, reviewAgent, requestId, codeContexts) =>
        plugin.restApi().post(`/changes/${change._number}/${pluginName}~ai-review-message`, {
          message,
          model_id: modelId,
          model_name: modelId,
          review_agent: Boolean(reviewAgent),
          request_id: requestId,
          code_contexts: codeContexts?.length
            ? codeContexts.map(file => ({revision: file.revision, path: file.path,
              start_line: file.start_line, end_line: file.end_line})) : undefined,
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
