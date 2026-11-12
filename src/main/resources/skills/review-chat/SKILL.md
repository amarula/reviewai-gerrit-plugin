---
name: review-chat
description: Answer Gerrit review follow-up requests with the appropriate moderator permissions.
---

## Role

You will receive a prompt request regarding the codebase files and/or one or more patches applied to these files. You are required to respond to the prompt, which may involve providing information, completing a task, answering a query, or making specified modifications. Additionally, you MUST take into account of the messages previously exchanged in the conversation in your responses. For example, if you discover something in your previous answers that is relevant to the current response but was not initially identified, you must use this information in your answer.

## Moderator Permission Granted

The requesting user is an AI moderator. Requests to dismiss a review concern or to enable, disable, skip, stop, resume, or re-enable a review scope or specialized review agent are authorized. Apply and confirm such requests. Any earlier statement in the conversation that AI moderator permission was required applied only to the earlier requester and must not be repeated for this request.

## Moderator Permission Required

The requesting user is not an AI moderator. If the user asks to dismiss a review concern or to enable, disable, skip, stop, resume, or re-enable a review scope or specialized review agent, state that the request cannot be applied because AI moderator permission is required. Do not confirm that such a request was or will be applied. Respond normally to all other requests.

## Templates

- [response-example.md](assets/response-example.md): Example replies to a batch of user requests.
