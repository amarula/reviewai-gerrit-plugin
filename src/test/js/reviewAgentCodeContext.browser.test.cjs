const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {before, after, test} = require('node:test');
const {chromium} = require('playwright');

const resources = path.join(__dirname, '../resources/__files/reviewagent');
const fixture = JSON.parse(fs.readFileSync(path.join(resources, 'code-context.json'), 'utf8'));
const ranges = JSON.parse(fs.readFileSync(path.join(resources, 'code-context-ranges.json'), 'utf8'));
const multiple = JSON.parse(fs.readFileSync(path.join(resources, 'code-context-multiple.json'), 'utf8'));
const files = JSON.parse(fs.readFileSync(path.join(resources, 'context-picker-files.json'), 'utf8'));
const source = path.join(__dirname, '../../main/resources/static/reviewai/reviewAgent/reviewAgentCodeContext.js');
let browser;
before(async () => { browser = await chromium.launch({headless: true}); });
after(async () => { await browser?.close(); });

async function harness(t) {
  const page = await browser.newPage({viewport: {width: 440, height: 740}});
  t.after(() => page.close());
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  t.after(() => assert.deepEqual(errors, []));
  await page.setContent(fs.readFileSync(path.join(resources, 'context-picker.html'), 'utf8'));
  await page.evaluate(({fixture, files}) => {
    window.fixture = fixture;
    window.files = files;
    window.fetches = [];
    window.models = {can_ai_review: true};
    window.ReviewAi = {agentUtils: {canAiReview: value => value.can_ai_review !== false}};
    window.plugin = {
      getPluginName: () => 'reviewai',
      registerCustomComponent: () => {},
      restApi: () => ({
        get: async url => {
          window.fetches.push(url);
          if (url.endsWith('ai-review-agent-model')) return window.models;
          if (window.failFiles) throw new Error('Could not read changed files');
          return window.files;
        },
        fetch: async (method, url) => {
          window.fetches.push(url);
          if (window.failPreview) throw new Error('Could not read file preview');
          if (window.delayPreview) await new Promise(resolve => { window.resolvePreview = resolve; });
          const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(window.previewContent ?? fixture.content)));
          return window.previewJson
            ? new Response(")]}'\n" + JSON.stringify(encoded), {headers: {'Content-Type': 'application/json'}})
            : new Response(encoded, {headers: {'Content-Type': 'text/plain'}});
        },
      }),
    };
    window.change = {_number: 42, current_revision: fixture.revision,
      revisions: {[fixture.revision]: {_number: 1}}};
    window.originalPanel = document.querySelector('chat-panel');
  }, {fixture, files});
  await page.addScriptTag({path: source});
  await page.evaluate(() => {
    ReviewAi.codeContext.register(plugin);
    window.types = [...ReviewAi.codeContext.types(), {id: 'url', name: 'URL'}];
    originalPanel.shadowRoot.querySelector('prompt-box').types = types;
    const picker = document.createElement('reviewai-code-context');
    picker.plugin = plugin;
    picker.change = change;
    picker.revision = {_number: 1};
    document.querySelector('gr-endpoint-decorator').append(picker);
  });
  const picker = page.locator('reviewai-code-context');
  const dialog = page.getByRole('dialog', {name: 'Attach code'});
  const select = dialog.getByRole('combobox');
  const items = () => page.evaluate(() =>
    originalPanel.shadowRoot.querySelector('prompt-box').items);
  const open = async (keyboard = false) => {
    await page.getByRole('button', {name: 'Add Context', exact: true}).click();
    const item = page.getByRole('menuitem', {name: 'Changed file', exact: true});
    if (keyboard) await item.press('Enter');
    else await item.click();
    await dialog.waitFor({state: 'visible'});
  };
  const attach = async file => {
    await open();
    await select.selectOption(file);
    await dialog.getByRole('button', {name: 'Attach file', exact: true}).click();
    await dialog.waitFor({state: 'hidden'});
  };
  const rangeToggle = dialog.getByRole('checkbox', {name: 'Select lines', exact: true});
  const preview = dialog.getByRole('textbox', {name: 'File preview', exact: true});
  const selectRange = async () => {
    await open();
    await select.selectOption(fixture.path);
    await rangeToggle.check();
    await preview.waitFor({state: 'visible'});
    await dialog.getByRole('spinbutton', {name: 'Start line'}).waitFor();
    await page.waitForFunction(() => !document.querySelector('reviewai-code-context').shadowRoot.querySelector('#range-fields').disabled);
  };
  return {page, picker, dialog, select, items, open, attach, rangeToggle, preview, selectRange};
}

test('Add Context opens a floating picker without taking sidebar space or opening link input', async t => {
  const h = await harness(t);
  assert.equal(await h.dialog.isVisible(), false);
  assert.equal(await h.picker.boundingBox(), null);
  assert.equal(await h.page.evaluate(() => fetches.length), 0);
  const originalBox = await h.page.locator('chat-panel').boundingBox();
  await h.open();
  await h.page.waitForFunction(() => !document.querySelector('reviewai-code-context').shadowRoot.querySelector('select').disabled);
  assert.equal(await h.select.locator('option').count(), 3);
  assert.equal(await h.page.evaluate(() => nativeTextDialogOpenCount), 0);
  assert.deepEqual(await h.page.locator('chat-panel').boundingBox(), originalBox);
  const box = await h.dialog.boundingBox();
  assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 440 && box.y + box.height <= 740);
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).isDisabled(), true);
});

test('attaching uses native removable chips and consuming a request clears its draft attachment', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  assert.equal((await h.items()).length, 1);
  assert.match((await h.items())[0].title, /12345678/);
  assert.deepEqual(await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    const file = ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
    ReviewAi.codeContext.consume(change, file);
    return file;
  }), [{revision: fixture.revision, path: fixture.path}]);
  assert.deepEqual(await h.items(), []);
  await h.attach(fixture.path);
  await h.page.getByRole('button', {name: 'Remove context', exact: true}).click();
  assert.deepEqual(await h.items(), []);
});

test('Cancel, Escape and outside clicks dismiss the panel without changing attachments', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  const original = await h.items();
  await h.open();
  await h.select.selectOption('src/Other.java');
  await h.dialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  assert.deepEqual(await h.items(), original);
  await h.open(true);
  await h.select.press('Escape');
  await h.dialog.waitFor({state: 'hidden'});
  assert.deepEqual(await h.items(), original);
  await h.open();
  await h.page.mouse.click(3, 3);
  await h.dialog.waitFor({state: 'hidden'});
  assert.deepEqual(await h.items(), original);
});

test('a new file preserves existing chips and an older request cannot consume a newer selection', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    window.previousSnapshot = ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
  });
  await h.attach('src/Other.java');
  assert.equal((await h.items()).length, 2);
  await h.page.evaluate(() => ReviewAi.codeContext.consume(change, previousSnapshot));
  assert.equal((await h.items()).length, 1);
  assert.ok((await h.items())[0].title.startsWith('src/Other.java'));
});

test('patch-set changes close the picker and remove stale context chips', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  await h.open();
  await h.page.evaluate(() => {
    const sha = fixture.revision.split('').reverse().join('');
    const picker = document.querySelector('reviewai-code-context');
    picker.change = {...change, current_revision: sha, revisions: {[sha]: {_number: 2}}};
    picker.revision = {_number: 2};
  });
  await h.dialog.waitFor({state: 'hidden'});
  assert.deepEqual(await h.items(), []);
});

test('native prompt replacement reconnects the menu adapter and other context types retain native behavior', async t => {
  const h = await harness(t);
  await h.page.evaluate(() => {
    const replacement = document.createElement('prompt-box');
    replacement.types = types;
    originalPanel.shadowRoot.querySelector('prompt-box').replaceWith(replacement);
  });
  await h.open();
  await h.dialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  await h.page.getByRole('button', {name: 'Add Context', exact: true}).click();
  await h.page.getByRole('menuitem', {name: 'URL', exact: true}).click();
  assert.equal(await h.page.evaluate(() => nativeTextDialogOpenCount), 1);
  assert.equal(await h.dialog.isVisible(), false);
});

test('loading failures and denied permissions keep Attach disabled', async t => {
  const h = await harness(t);
  await h.page.evaluate(() => { window.failFiles = true; });
  await h.open();
  await h.page.getByText('Could not read changed files', {exact: true}).waitFor();
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).isDisabled(), true);
  await h.dialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  await h.page.evaluate(() => { window.failFiles = false; window.models.can_ai_review = false; });
  await h.open();
  await h.page.getByText('ReviewAI is not allowed for this change.', {exact: true}).waitFor();
  assert.equal(await h.select.isDisabled(), true);
});

test('disconnecting the picker removes its draft chip and dismisses the popover', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  await h.open();
  await h.picker.evaluate(element => element.remove());
  assert.deepEqual(await h.items(), []);
  assert.equal(await h.page.getByRole('dialog', {name: 'Attach code'}).count(), 0);
});

test('restored history chips do not become attachments on the next draft', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    prompt.items = prompt.items.map(item => ({...item, reviewai_consumed: true}));
    prompt.renderChips();
  });
  await h.page.waitForFunction(() => originalPanel.shadowRoot.querySelector('prompt-box').items.length === 0);
});

test('selecting text in the preview attaches its inclusive lines and restores them when reopened', async t => {
  const h = await harness(t);
  await h.selectRange();
  assert.equal(await h.preview.inputValue(), fixture.content);
  const previewBox = await h.preview.boundingBox();
  await h.page.mouse.move(previewBox.x + 16, previewBox.y + 6 + 18 * (ranges.start_line - 0.5));
  await h.page.mouse.down();
  await h.page.mouse.move(previewBox.x + 70, previewBox.y + 6 + 18 * (ranges.end_line - 0.5));
  await h.page.mouse.up();
  assert.equal(await h.dialog.getByRole('spinbutton', {name: 'Start line'}).inputValue(), String(ranges.start_line));
  assert.equal(await h.dialog.getByRole('spinbutton', {name: 'End line'}).inputValue(), String(ranges.end_line));
  await h.dialog.getByRole('button', {name: 'Attach selection', exact: true}).click();
  const expected = {revision: fixture.revision, path: fixture.path,
    start_line: ranges.start_line, end_line: ranges.end_line};
  assert.deepEqual(await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    return ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
  }), [expected]);
  assert.ok((await h.items())[0].title.includes(`:${ranges.start_line}–${ranges.end_line}`));
  await h.open();
  await h.preview.waitFor({state: 'visible'});
  assert.equal(await h.rangeToggle.isChecked(), true);
  assert.equal(await h.dialog.getByRole('spinbutton', {name: 'Start line'}).inputValue(), String(ranges.start_line));
  assert.equal(await h.dialog.getByRole('spinbutton', {name: 'End line'}).inputValue(), String(ranges.end_line));
  const box = await h.dialog.boundingBox();
  assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 440 && box.y + box.height <= 740);
});

test('line controls validate ranges and keep the preview selection in sync', async t => {
  const h = await harness(t);
  await h.page.evaluate(() => { window.previewJson = true; });
  await h.selectRange();
  const attach = h.dialog.getByRole('button', {name: 'Attach selection', exact: true});
  for (const range of ranges.invalid_ranges) {
    await h.dialog.getByRole('spinbutton', {name: 'Start line'}).fill(String(range.start_line ?? ''));
    await h.dialog.getByRole('spinbutton', {name: 'End line'}).fill(String(range.end_line ?? ''));
    assert.equal(await attach.isDisabled(), true);
  }
  await h.dialog.getByRole('spinbutton', {name: 'Start line'}).fill(String(ranges.start_line));
  await h.dialog.getByRole('spinbutton', {name: 'End line'}).fill(String(ranges.end_line));
  assert.equal(await attach.isDisabled(), false);
  const selectedText = await h.preview.evaluate(element => element.value.slice(element.selectionStart, element.selectionEnd));
  assert.equal(selectedText.trimEnd(), ranges.content.trimEnd());
});

test('switching back to whole-file mode removes the range from the attachment', async t => {
  const h = await harness(t);
  await h.selectRange();
  await h.rangeToggle.uncheck();
  assert.equal(await h.preview.isVisible(), false);
  await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).click();
  assert.deepEqual(await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    return ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
  }), [{revision: fixture.revision, path: fixture.path}]);
});

test('preview failures block range attachments while whole-file mode remains usable', async t => {
  const h = await harness(t);
  await h.page.evaluate(() => { window.failPreview = true; });
  await h.open();
  await h.select.selectOption(fixture.path);
  await h.rangeToggle.check();
  await h.page.getByText('Could not read file preview', {exact: true}).waitFor();
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach selection', exact: true}).isDisabled(), true);
  await h.rangeToggle.uncheck();
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).isDisabled(), false);
});

test('changing files discards an in-flight preview and its range', async t => {
  const h = await harness(t);
  await h.page.evaluate(() => { window.delayPreview = true; });
  await h.open();
  await h.select.selectOption(fixture.path);
  await h.rangeToggle.check();
  await h.page.waitForFunction(() => typeof window.resolvePreview === 'function');
  await h.select.selectOption('src/Other.java');
  await h.page.evaluate(() => window.resolvePreview());
  assert.equal(await h.rangeToggle.isChecked(), false);
  assert.equal(await h.picker.locator('#preview').inputValue(), '');
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).isDisabled(), false);
});

test('empty files have no selectable lines', async t => {
  const h = await harness(t);
  await h.page.evaluate(value => { window.previewContent = value; }, ranges.empty_content);
  await h.open();
  await h.select.selectOption(fixture.path);
  await h.rangeToggle.check();
  await h.page.getByText('This file has no lines to select.', {exact: true}).waitFor();
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach selection', exact: true}).isDisabled(), true);
});

test('multiple ranges from one file and another whole file have independently removable chips', async t => {
  const h = await harness(t);
  const expected = multiple.attachments.map(file => ({revision: fixture.revision, ...file}));
  for (const file of multiple.attachments) {
    if (file.start_line) {
      await h.selectRange();
      await h.dialog.getByRole('spinbutton', {name: 'Start line'}).fill(String(file.start_line));
      await h.dialog.getByRole('spinbutton', {name: 'End line'}).fill(String(file.end_line));
      await h.dialog.getByRole('button', {name: 'Attach selection', exact: true}).click();
    } else await h.attach(file.path);
  }
  assert.equal((await h.items()).length, expected.length);
  assert.deepEqual(await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    return ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
  }), expected);
  await h.page.getByRole('button', {name: 'Remove context', exact: true}).nth(1).click();
  assert.deepEqual(await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    const selected = ReviewAi.codeContext.selected(change, {external_contexts: prompt.items});
    ReviewAi.codeContext.consume(change, selected);
    return selected;
  }), [expected[0], expected[2]]);
  assert.deepEqual(await h.items(), []);
});

test('attaching an identical file twice keeps one chip', async t => {
  const h = await harness(t);
  await h.attach(fixture.path);
  const original = await h.items();
  await h.attach(fixture.path);
  assert.deepEqual(await h.items(), original);
});

test('the attachment limit blocks new chips until one is removed', async t => {
  const h = await harness(t);
  const limit = await h.page.evaluate(() => {
    const prompt = originalPanel.shadowRoot.querySelector('prompt-box');
    const parse = ReviewAi.codeContext.types()[0].parse;
    const count = fixture.content.trimEnd().split('\n').length;
    const items = [];
    for (let start = 1; start <= count; start++) {
      for (let end = start; end <= count && items.length < ReviewAi.codeContext.maxAttachments; end++) {
        items.push(parse(`reviewai-file:42:${fixture.revision}:${encodeURIComponent(fixture.path)}#L${start}-L${end}`));
      }
    }
    prompt.items = items;
    prompt.renderChips();
    return ReviewAi.codeContext.maxAttachments;
  });
  await h.open();
  await h.select.selectOption('src/Other.java');
  assert.equal(await h.dialog.getByRole('button', {name: 'Attach file', exact: true}).isDisabled(), true);
  assert.match(await h.picker.locator('#attachment-count').textContent(), /Remove a chip/);
  await h.dialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  await h.page.getByRole('button', {name: 'Remove context', exact: true}).first().click();
  await h.attach('src/Other.java');
  assert.equal((await h.items()).length, limit);
});
