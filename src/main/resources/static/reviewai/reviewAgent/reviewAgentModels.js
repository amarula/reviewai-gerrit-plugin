(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  reviewAi.agentModelMethods = {
    async getModels(change) {
      const modelInfo = await this._fetchModelInfo(change);
      if (!agentUtils.canAiReview(modelInfo)) {
        return agentUtils.emptyModelsResponse();
      }

      const toModel = model => {
        const provider = agentUtils.toProviderDisplayName(model && model.provider);
        const aiModel = model && (model.model || model.ai_model);
        const modelId =
          (model && (model.model_id || model.modelId)) || `${model.provider}/${aiModel}`;
        const fullDisplayText = aiModel ? `${provider} (${aiModel})` : provider;
        return {
          model_id: modelId,
          short_text: fullDisplayText,
          full_display_text: fullDisplayText,
        };
      };
      let models = agentUtils.configuredModels(modelInfo).map(toModel);
      let defaultModelId =
        (modelInfo && (modelInfo.default_model_id || modelInfo.defaultModelId)) ||
        (models[0] && models[0].model_id) ||
        null;
      const customizedModels =
        modelInfo && (modelInfo.customized_models || modelInfo.customizedModels);
      const agentModelsError =
        modelInfo && (modelInfo.agent_models_error || modelInfo.agentModelsError);
      if (Array.isArray(customizedModels) && models.length) {
        const currentModel = models.find(model => model.model_id === defaultModelId) || models[0];
        const customized = new Map(customizedModels.map(toModel)
          .filter(model => model.model_id !== currentModel.model_id)
          .map(model => [model.model_id, model.full_display_text]));
        defaultModelId = currentModel.model_id;
        models = [{
          ...currentModel,
          full_display_text: currentModel.full_display_text +
            (customized.size ? `; customized models: ${[...customized.values()].join(', ')}` : '') +
            (agentModelsError ? `; configuration error: ${agentModelsError}` : ''),
        }];
      }

      return {
        models,
        default_model_id: defaultModelId,
        custom_actions: this._actions(),
      };
    },

    async getActions(change) {
      if (change && agentUtils.getChangeNumber(change)) {
        const modelInfo = await this._fetchModelInfo(change);
        if (!agentUtils.canAiReview(modelInfo)) {
          return agentUtils.emptyActionsResponse();
        }
      }

      return {
        actions: this._actions(),
        default_action_id: agentUtils.defaultActionId,
      };
    },

    _actions() {
      return [
        {
          id: 'review-change',
          display_text: 'Full Review',
          hover_text: 'Run /review for the full change',
          enable_send_without_input: true,
          initial_user_prompt: '/review',
        },
        {
          id: 'review-patchset',
          display_text: 'Review Patch Set Only',
          hover_text: 'Run /review --scope=patchset to review the patchset only',
          enable_send_without_input: true,
          initial_user_prompt: '/review --scope=patchset',
        },
        {
          id: 'review-commit-message',
          display_text: 'Review Commit Message Only',
          hover_text: 'Run /review --scope=commit_message to review the commit message only',
          enable_send_without_input: true,
          initial_user_prompt: '/review --scope=commit_message',
        },
        {
          id: 'suggest',
          display_text: 'Generate Suggested Edits',
          hover_text: 'Run /suggest',
          enable_send_without_input: true,
          initial_user_prompt: '/suggest',
        },
        {
          id: 'reviewai-help',
          display_text: 'Show ReviewAI help',
          hover_text: 'Run /help to list ReviewAI commands',
          enable_send_without_input: true,
          initial_user_prompt: '/help',
        },
      ];
    },

    async _canAiReviewChange(change) {
      const modelInfo = await this._fetchModelInfo(change);
      return agentUtils.canAiReview(modelInfo);
    },

    async _fetchModelInfo(change) {
      if (!change || !change._number) {
        return null;
      }
      return this.plugin
        .restApi()
        .get(`/changes/${change._number}/${this.pluginName}~ai-review-agent-model`);
    },
  };
})(window);
