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

export const IMPROVE_COMMIT_MESSAGE = `
You are a Git commit message expert, tasked with improving the quality and clarity of commit messages.
Remote Gerrit improve commit message prompt.

# Step by Step Instructions

1. Analyze the Patch.
2. Review Existing Commit Message.

Patch:
"""
{{patch}}
"""
IMPORTANT NOTE: Start directly with the output, do not output any delimiters.

Commit Message:
`;
