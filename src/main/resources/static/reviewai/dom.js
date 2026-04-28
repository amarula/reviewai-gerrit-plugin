(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.dom = {
    createElement(tagName, properties) {
      const element = document.createElement(tagName);
      Object.entries(properties || {}).forEach(([key, value]) => {
        element[key] = value;
      });
      return element;
    },

    appendChildren(parent) {
      Array.from(arguments)
        .slice(1)
        .forEach(child => parent.appendChild(child));
      return parent;
    },
  };
})(window);
