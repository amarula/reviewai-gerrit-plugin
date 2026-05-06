import globals from "globals";

export default [
    {
        languageOptions: {
            // This tells ESLint that window, document, etc. are global variables
            globals: {
                ...globals.browser,
            }
        },
        rules: {
            "no-unused-vars": "warn",
            "no-undef": "error",
            "no-console": "off"
        }
    }
];
