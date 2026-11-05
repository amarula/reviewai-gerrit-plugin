const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const fixture = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../resources/__files/reviewagent/code-context.json'), 'utf8'
));
const versions = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../resources/__files/reviewagent/context-picker-versions.json'), 'utf8'
));
const ranges = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../resources/__files/reviewagent/code-context-ranges.json'), 'utf8'
));
const scriptsRoot = path.join(__dirname, '../../main/resources/static/reviewai');
const plain = value => JSON.parse(JSON.stringify(value));

function harness(conversations = new Map(), serverVersion = versions.supported[0]) {
  const sandbox = {setTimeout, clearTimeout, HTMLElement: class {}, console};
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  for (const script of [
    'shared.js', 'api.js', 'entries.js',
    'reviewAgent/reviewAgentUtils.js', 'reviewAgent/reviewAgentCodeContext.js',
    'reviewAgent/reviewAgentModels.js', 'reviewAgent/reviewAgentConversationStore.js',
    'reviewAgent/reviewAgentConversationTurns.js', 'reviewAgent/reviewAgentConversations.js',
    'reviewAgent/reviewAgent.js',
  ]) {
    vm.runInContext(fs.readFileSync(path.join(scriptsRoot, script), 'utf8'), sandbox);
  }
  let provider;
  const nativeContext = {...sandbox.ReviewAi.codeContext};
  let selection = {change: 42, revision: fixture.revision, path: fixture.path};
  const sent = [];
  const api = {
    get: async url => url.endsWith('ai-review-history') ? {entries: []} : {can_ai_review: true},
    post: async (url, body) => {
      if (url.endsWith('ai-review-agent-conversations')) {
        const key = body.conversationId || body.conversation_id;
        if (body.action === 'get') return {conversation: conversations.get(key)};
        if (body.action === 'append') {
          const conversation = conversations.get(key) || {turns: []};
          conversation.turns[body.turn_index ?? conversation.turns.length] = plain(body.turn);
          conversations.set(key, conversation);
          return {conversation};
        }
        throw new Error(`Unexpected conversation action: ${body.action}`);
      }
      assert.ok(url.endsWith('ai-review-message'));
      sent.push(plain(body));
      return {response_text: fixture.content, wait_for_assistant_reply: false};
    },
  };
  const plugin = {
    restApi: () => api,
    aiCodeReview: () => ({register: value => { provider = value; }}),
  };
  sandbox.ReviewAi.codeContext.selected = change =>
    selection && selection.change === change._number ? selection : null;
  sandbox.ReviewAi.codeContext.consume = (change, file) => {
    if (file && selection === file && selection.change === change._number) selection = null;
  };
  sandbox.ReviewAi.agent.register(plugin, 'reviewai', serverVersion);
  const request = {
    change: {_number: 42, current_revision: 'different-current-revision'},
    prompt: fixture.message,
    conversation_id: 'conversation-1',
    turn_index: 0,
    regeneration_index: 0,
    client_data: '{}',
  };
  return {
    api, provider, sent, request, conversations, nativeContext,
    context: sandbox.ReviewAi.codeContext,
    get selection() { return selection; },
    set selection(value) { selection = value; },
    async send(overrides = {}) {
      const responses = [];
      let completions = 0;
      await provider._chatAsync({...request, ...overrides}, {
        emitResponse: response => responses.push(response.response_parts[0].text),
        done: () => { completions++; },
      });
      assert.equal(completions, 1);
      return responses.join('\n');
    },
  };
}

test('sends and persists the pinned attachment, then consumes the pending selection', async () => {
  const h = harness();
  const response = await h.send();
  const expected = {revision: fixture.revision, path: fixture.path};
  assert.deepEqual(h.sent[0].code_context, expected);
  assert.deepEqual(h.conversations.get('conversation-1').turns[0].code_context, expected);
  assert.ok(response.includes(fixture.path));
  assert.ok(response.includes(fixture.content));
  assert.equal(h.selection, null);
  await h.send({turn_index: 1});
  assert.equal(h.sent[1].code_context, undefined);
});

test('snapshots selection before asynchronous model lookup', async () => {
  const h = harness();
  let resolveModels;
  h.api.get = url => url.endsWith('ai-review-history')
    ? Promise.resolve({entries: []})
    : new Promise(resolve => { resolveModels = resolve; });
  const sending = h.send();
  h.selection = null;
  resolveModels({can_ai_review: true});
  await sending;
  assert.equal(h.sent[0].code_context.revision, fixture.revision);
});

test('regeneration after reload restores saved context, not a newly selected file', async () => {
  const original = harness();
  await original.send();
  const restored = harness(original.conversations);
  const newSelection = {...restored.selection, revision: fixture.revision.split('').reverse().join('')};
  restored.selection = newSelection;
  await restored.send({regeneration_index: 1});
  assert.equal(restored.sent[0].code_context.revision, fixture.revision);
  assert.equal(restored.selection, newSelection);
});

test('regeneration of an unattached turn does not pick up pending context', async () => {
  const original = harness();
  original.selection = null;
  await original.send();
  const restored = harness(original.conversations);
  await restored.send({regeneration_index: 1});
  assert.equal(restored.sent[0].code_context, undefined);
  assert.ok(restored.selection);
});

test('regeneration normalizes plain-text prompts from older conversation history', async () => {
  const h = harness(new Map([['conversation-1', {turns: [{
    user_input: {user_question: fixture.message.substring('/message '.length)},
  }]}]]));
  await h.send({regeneration_index: 1});
  assert.equal(h.sent[0].message, fixture.message);
  assert.equal(h.sent[0].code_context, undefined);
});

test('failed sends retain the attachment for retry', async () => {
  const h = harness();
  const originalSelection = h.selection;
  const post = h.api.post;
  h.api.post = async () => { throw new Error('File is no longer accessible'); };
  assert.match(await h.send(), /File is no longer accessible/);
  assert.equal(h.selection, originalSelection);
  assert.equal(h.conversations.size, 0);
  h.api.post = post;
  await h.send({regeneration_index: 1});
  assert.equal(h.sent[0].code_context.revision, fixture.revision);
  assert.equal(h.selection, null);
});

test('review commands reject attachments without consuming or posting them', async () => {
  const h = harness();
  assert.match(await h.send({prompt: '/review'}), /Remove the file to run a command/);
  assert.equal(h.sent.length, 0);
  assert.ok(h.selection);
});

test('context selected for another change is not sent', async () => {
  const h = harness();
  await h.send({change: {_number: 43}});
  assert.equal(h.sent[0].code_context, undefined);
  assert.ok(h.selection);
});

test('provider advertises Changed file in the native Add Context menu', async () => {
  const h = harness();
  assert.equal(h.provider.supports_add_context, true);
  const types = await h.provider.getContextItemTypes();
  assert.equal(types.length, 1);
  assert.equal(types[0].name, 'Changed file');
  const reference = `reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}`;
  const item = types[0].parse(reference);
  assert.equal(item.identifier, reference);
  assert.ok(item.title.includes(fixture.path));
});

for (const version of versions.supported) {
  test(`enables Add Context on Gerrit ${version}`, () => {
    assert.equal(harness(new Map(), version).provider.supports_add_context, true);
  });
}

for (const version of versions.unsupported) {
  test(`hides Add Context for unsupported or unknown Gerrit version ${JSON.stringify(version)}`, async () => {
    const h = harness(new Map(), version);
    assert.equal(h.provider.supports_add_context, false);
    h.selection = null;
    assert.equal(await h.send(), fixture.content);
  });
}

function initializationHarness(getVersion) {
  const registrations = [];
  const plugin = {restApi: () => ({getVersion})};
  const sandbox = {
    window: {ReviewAi: {
      codeContext: {register() {}},
      agent: {register: (...args) => registrations.push(args)},
    }},
    Gerrit: {install() {}},
    console: {error() {}},
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(scriptsRoot, '../init.js'), 'utf8'), sandbox);
  vm.runInContext('reviewAiLoader.load = () => Promise.resolve();', sandbox);
  return {registrations, initialize: () => sandbox.initializeReviewAi(plugin, 'reviewai')};
}

test('initialization determines the server version before registering the provider', async () => {
  let finishVersion;
  const version = new Promise(resolve => { finishVersion = resolve; });
  const h = initializationHarness(() => version);
  const initializing = h.initialize();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.registrations.length, 0);
  finishVersion(versions.supported[0]);
  await initializing;
  assert.equal(h.registrations.length, 1);
  assert.equal(h.registrations[0][2], versions.supported[0]);
});

test('a failed version lookup still registers the provider without context support', async () => {
  const h = initializationHarness(async () => { throw new Error(versions.lookupFailure); });
  await h.initialize();
  assert.equal(h.registrations.length, 1);
  assert.equal(h.registrations[0][2], undefined);
});

test('native context chips are forwarded and persisted as pinned code attachments', async () => {
  const h = harness();
  h.context.selected = h.nativeContext.selected;
  const type = (await h.provider.getContextItemTypes())[0];
  const item = type.parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}`);
  await h.send({external_contexts: [item]});
  assert.deepEqual(h.sent[0].code_context, {revision: fixture.revision, path: fixture.path});
  assert.deepEqual(h.conversations.get('conversation-1').turns[0].code_context, h.sent[0].code_context);
  const first = JSON.parse(h.conversations.get('conversation-1').turns[0].user_input.client_data);
  assert.equal(first.contextItems[0].identifier, item.identifier);
  assert.equal(first.contextItems[0].reviewai_consumed, true);
  assert.equal(h.nativeContext.selected(h.request.change, {external_contexts: first.contextItems}), null);
  await h.send({turn_index: 1, external_contexts: []});
  const following = JSON.parse(h.conversations.get('conversation-1').turns[1].user_input.client_data);
  assert.equal(following.overridesPreviousTurn, true);
  assert.deepEqual(following.contextItems, []);
});

test('invalid or cross-change context references cannot become attachments', async () => {
  const h = harness();
  const type = (await h.provider.getContextItemTypes())[0];
  assert.equal(type.parse('reviewai-file:42:current:src/file'), undefined);
  assert.equal(type.parse(`reviewai-file:42:${fixture.revision}:%ZZ`), undefined);
  assert.equal(type.parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent('../secret')}`), undefined);
  const item = type.parse(`reviewai-file:43:${fixture.revision}:${encodeURIComponent(fixture.path)}`);
  assert.throws(() => h.nativeContext.selected({_number: 42}, {external_contexts: [item]}),
    /does not belong to this change/);
});

test('more than one native file chip is rejected before sending', async () => {
  const h = harness();
  h.context.selected = h.nativeContext.selected;
  const type = (await h.provider.getContextItemTypes())[0];
  const item = type.parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}`);
  assert.match(await h.send({external_contexts: [item, {...item}]}), /Attach one changed file/);
  assert.equal(h.sent.length, 0);
});

test('a selected line range survives sending, saving and regeneration after reload', async () => {
  const h = harness();
  h.context.selected = h.nativeContext.selected;
  const type = (await h.provider.getContextItemTypes())[0];
  const item = type.parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}#L${ranges.start_line}-L${ranges.end_line}`);
  assert.ok(item.title.includes(`:${ranges.start_line}–${ranges.end_line}`));
  const response = await h.send({external_contexts: [item]});
  const expected = {revision: fixture.revision, path: fixture.path,
    start_line: ranges.start_line, end_line: ranges.end_line};
  assert.deepEqual(h.sent[0].code_context, expected);
  const saved = h.conversations.get('conversation-1').turns[0];
  assert.deepEqual(saved.code_context, expected);
  assert.equal(JSON.parse(saved.user_input.client_data).contextItems[0].identifier, item.identifier);
  assert.ok(response.includes(`lines ${ranges.start_line}–${ranges.end_line}`));
  const restored = harness(h.conversations);
  await restored.send({regeneration_index: 1});
  assert.deepEqual(restored.sent[0].code_context, expected);
  assert.deepEqual(restored.conversations.get('conversation-1').turns[0].code_context, expected);
});

test('invalid ranges cannot become native attachments', () => {
  const h = harness();
  const type = h.nativeContext.types()[0];
  const reference = `reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}`;
  for (const invalid of ranges.invalid_ranges.filter(value =>
    !value.start_line || !value.end_line || value.start_line < 1 || value.end_line < value.start_line)) {
    assert.equal(type.parse(`${reference}#L${invalid.start_line}-L${invalid.end_line}`), undefined);
  }
  const pathWithHash = `${fixture.path}#L1-L2`;
  const item = type.parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent(pathWithHash)}`);
  assert.equal(h.nativeContext.selected(h.request.change, {external_contexts: [item]}).path, pathWithHash);
});
