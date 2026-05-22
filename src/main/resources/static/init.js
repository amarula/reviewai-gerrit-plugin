const reviewAiVersion = '@project.version@';

function loadReviewAiScript(url) {
  const existingScript = document.querySelector(`script[data-reviewai-src="${url}"]`);
  if (existingScript) {
    if (existingScript.dataset.reviewaiLoaded === 'true') {
      return Promise.resolve();
    }

    return new Promise((resolve, reject) => {
      existingScript.addEventListener('load', () => resolve(), {once: true});
      existingScript.addEventListener('error', () => reject(new Error(`Failed to load ${url}`)), {
        once: true,
      });
    });
  }

  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = url;
    script.async = false;
    script.dataset.reviewaiSrc = url;
    script.addEventListener(
      'load',
      () => {
        script.dataset.reviewaiLoaded = 'true';
        resolve();
      },
      {once: true}
    );
    script.addEventListener('error', () => reject(new Error(`Failed to load ${url}`)), {
      once: true,
    });
    document.head.appendChild(script);
  });
}

const reviewAiLoader = (() => {
  let loadPromise;

  return {
    load(plugin) {
      if (!loadPromise) {
        const scriptPaths = [
          'shared.js',
          'api.js',
          'entries.js',
          'reviewAgent/reviewAgentUtils.js',
          'reviewAgent/reviewAgentModels.js',
          'reviewAgent/reviewAgentConversationStore.js',
          'reviewAgent/reviewAgentConversationTurns.js',
          'reviewAgent/reviewAgentConversations.js',
          'reviewAgent/reviewAgent.js',
        ];

        loadPromise = scriptPaths.reduce(
          (promise, path) =>
            promise.then(() => {
              const scriptUrl = new URL(plugin.url(`/static/reviewai/${path}`));
              scriptUrl.searchParams.set('v', reviewAiVersion);
              return loadReviewAiScript(scriptUrl.toString());
            }),
          Promise.resolve()
        );
      }

      return loadPromise;
    },
  };
})();

function initializeReviewAi(plugin, pluginName) {
  reviewAiLoader
    .load(plugin)
    .then(() => {
      window.ReviewAi.agent.register(plugin, pluginName);
    })
    .catch(error => {
      console.error('ReviewAI frontend failed to initialize.', error);
    });
}

Gerrit.install(plugin => {
  const pluginName = plugin.getPluginName();

  initializeReviewAi(plugin, pluginName);
});
