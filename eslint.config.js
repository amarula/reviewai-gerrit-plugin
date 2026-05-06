const js = require('@eslint/js');

module.exports = [
  {
    ignores: ['node/**', 'node_modules/**', 'target/**'],
  },
  {
    files: ['src/main/resources/static/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: {
        console: 'readonly',
        customElements: 'readonly',
        document: 'readonly',
        Gerrit: 'readonly',
        HTMLElement: 'readonly',
        URL: 'readonly',
        window: 'readonly',
      },
    },
    rules: {
      ...js.configs.recommended.rules,
    },
  },
];
