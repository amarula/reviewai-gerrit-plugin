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
        const scriptPaths = ['shared.js', 'reviewAgent.js', 'render.js', 'components.js', 'register.js'];

        loadPromise = scriptPaths
          .reduce(
            (promise, path) =>
              promise.then(() => {
                const scriptUrl = new URL(plugin.url(`/static/reviewai/${path}`));
                scriptUrl.searchParams.set('v', reviewAiVersion);
                return loadReviewAiScript(scriptUrl.toString());
              }),
            Promise.resolve()
          )
          .then(() => {
            window.ReviewAi.defineCustomElements();
          });
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

  plugin.registerDynamicCustomComponent(
    'change-view-tab-header',
    'reviewai-change-view-tab-header'
  );

  plugin
    .registerDynamicCustomComponent(
      'change-view-tab-content',
      'reviewai-change-view-tab-content'
    )
    .onAttached(view => {
      view.fetchHistory = change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-history`);
      view.sendMessage = (change, message) =>
        plugin.restApi().post(`/changes/${change._number}/${pluginName}~ai-review-message`, {
          message,
        });
      initializeReviewAi(plugin, pluginName);
    });

  initializeReviewAi(plugin, pluginName);
});
