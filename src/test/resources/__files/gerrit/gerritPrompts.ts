export const HELP_ME_REVIEW_PROMPT = `
Remote Gerrit review prompt.

# Step by Step Instructions

1. Inspect the patch carefully.
2. Focus on correctness and tests.

Patch:
"""
{{patch}}
"""
IMPORTANT NOTE: Start directly with the output, do not output any delimiters.

Review:
`;
