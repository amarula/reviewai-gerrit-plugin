const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const fixture = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../resources/__files/reviewagent/sidebar-request.json'), 'utf8'
));
const scriptsRoot = path.join(__dirname, '../../main/resources/static/reviewai');

function harness() {
  const sandbox = {setTimeout, console: {error() {}}};
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  for (const script of ['shared.js', 'api.js', 'entries.js',
    'reviewAgent/reviewAgentUtils.js', 'reviewAgent/reviewAgentModels.js',
    'reviewAgent/reviewAgentConversationStore.js', 'reviewAgent/reviewAgentConversationTurns.js',
    'reviewAgent/reviewAgentConversations.js', 'reviewAgent/reviewAgent.js']) {
    vm.runInContext(fs.readFileSync(path.join(scriptsRoot, script), 'utf8'), sandbox);
  }
  sandbox.ReviewAi.codeContext = {
    selected: () => [], consume() {}, describe: (context, text) => text,
  };
  sandbox.ReviewAi.agentUtils.sleep = async () => {};
  let provider;
  const api = {
    get: async url => url.endsWith('ai-review-history')
      ? {entries: []} : {can_ai_review: true},
    post: async url => {
      if (url.endsWith('ai-review-message')) return {request_id: fixture.messageId};
      if (url.endsWith('ai-review-message-status')) {
        return {status: 'completed', response_text: fixture.response};
      }
      if (url.endsWith('ai-review-agent-conversations')) return {};
      throw new Error(`Unexpected endpoint: ${url}`);
    },
  };
  sandbox.ReviewAi.agent.register({restApi: () => api,
    aiCodeReview: () => ({register: value => { provider = value; }})}, 'reviewai');
  const responses = [];
  let completions = 0;
  return {
    api, provider, responses,
    get completions() { return completions; },
    send: () => provider._chatAsync({change: {_number: 42}, prompt: fixture.prompt,
      conversation_id: 'conversation-1', turn_index: 0, client_data: '{}'}, {
      emitResponse: value => responses.push(value.response_parts[0].text),
      done: () => { completions++; },
    }),
  };
}

test('delivers a completed response when comment history is unavailable', async () => {
  const h = harness();
  let historyReads = 0;
  const get = h.api.get;
  h.api.get = url => {
    if (url.endsWith('ai-review-history') && historyReads++ > 0) {
      throw new Error(fixture.failure);
    }
    return get(url);
  };
  await h.send();
  assert.deepEqual(h.responses, [fixture.response]);
  assert.equal(h.completions, 1);
});

test('finishes the chat before saving conversation history completes', async () => {
  const h = harness();
  const post = h.api.post;
  let finishSave;
  h.api.post = (url, body) => url.endsWith('ai-review-agent-conversations')
    ? new Promise(resolve => { finishSave = resolve; }) : post(url, body);
  const sending = h.send();
  try {
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.responses, [fixture.response]);
    assert.equal(h.completions, 1);
  } finally {
    finishSave({});
    await sending;
  }
});

test('a failed history save does not replace the answer or finish the listener twice', async () => {
  const h = harness();
  const post = h.api.post;
  h.api.post = (url, body) => {
    if (url.endsWith('ai-review-agent-conversations')) throw new Error(fixture.failure);
    return post(url, body);
  };
  await h.send();
  assert.deepEqual(h.responses, [fixture.response]);
  assert.equal(h.completions, 1);
});

test('polls the returned request id through pending to completed', async () => {
  const h = harness();
  const post = h.api.post;
  let polls = 0;
  h.api.post = (url, body) => {
    if (url.endsWith('ai-review-message-status')) {
      assert.equal(body.request_id, fixture.messageId);
      return {status: ++polls === 1 ? 'pending' : 'completed', responseText: fixture.response};
    }
    return post(url, body);
  };
  await h.send();
  assert.equal(polls, 2);
  assert.deepEqual(h.responses, [fixture.response]);
  assert.equal(h.completions, 1);
});

test('surfaces request failures immediately', async () => {
  const h = harness();
  const post = h.api.post;
  h.api.post = (url, body) => url.endsWith('ai-review-message-status')
    ? {status: 'failed', response_text: fixture.failure} : post(url, body);
  await h.send();
  assert.deepEqual(h.responses, [fixture.failure]);
  assert.equal(h.completions, 1);
});

test('can still load completed replies from history when status has no response text', async () => {
  const h = harness();
  const get = h.api.get;
  const post = h.api.post;
  let historyReads = 0;
  h.api.get = url => url.endsWith('ai-review-history')
    ? {entries: historyReads++ === 0 ? [] : [{id: fixture.messageId,
      role: 'assistant', message: fixture.response}]} : get(url);
  h.api.post = (url, body) => url.endsWith('ai-review-message-status')
    ? {status: 'completed'} : post(url, body);
  await h.send();
  assert.deepEqual(h.responses, [fixture.response]);
  assert.equal(h.completions, 1);
});
