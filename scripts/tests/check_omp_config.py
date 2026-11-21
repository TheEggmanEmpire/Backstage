#!/usr/bin/env python3
"""Validate OmpConfigTest's generated fixtures with the pinned omp package itself.

First run:backstage:testDebugUnitTest --tests '*omp.OmpConfigTest'. Pass an extracted
payload tree and host Bun executable. On Linux, the temporary tree also needs
@oh-my-pi/pi-natives-linux-x64 at exactly OMP_VERSION (APK payloads only ship Android).
This check never resolves !commands or provider credentials and never changes the tree.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

REPO = Path(__file__).resolve().parents[2]
JS = r'''
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const [tree, fixtures, version] = process.argv.slice(2);
const modules = path.join(tree, 'install/global/node_modules');
const load = relative => import(pathToFileURL(path.join(modules, relative)).href);
const { ModelsConfigFile } = await load('@oh-my-pi/pi-coding-agent/src/config/models-config.ts');
const { getBundledModels } = await load('@oh-my-pi/pi-catalog/src/models.ts');
const { Settings } = await load('@oh-my-pi/pi-coding-agent/src/config/settings.ts');
const { cfgEnabledModels, cfgCycleOrder, cfgModelRoles } = await load('@oh-my-pi/pi-coding-agent/src/config/model-settings.ts');
const cases = ['anthropic','openai','chatgpt','gemini','openrouter','deepseek','ollama','custom','venice','opencode','mixed','multi'];
const builtins = new Set(['anthropic','openai','google','openrouter','deepseek','openai-codex']);
for (const name of cases) {
  const directory = path.join(fixtures, name);
  const result = ModelsConfigFile.relocate(path.join(directory, 'models.yml')).tryLoad();
  assert.equal(result.status, 'ok', `${name}: ${result.error?.message ?? result.status}`);
  const providers = result.value.providers;
  const config = Bun.YAML.parse(fs.readFileSync(path.join(directory, 'config.yml'), 'utf8'));
  assert.equal(typeof config.modelRoles.default, 'string');
  const selected = config.modelRoles.default;
  const settings = await Settings.loadReadOnly({ agentDir: directory, cwd: directory, configFiles: [path.join(directory, 'config.yml')] });
  assert.deepEqual(cfgEnabledModels.get(settings), config.enabledModels);
  assert.deepEqual(cfgCycleOrder.get(settings), config.cycleOrder);
  assert.equal(cfgModelRoles.get(settings).default, selected);
  assert.ok(config.enabledModels.includes(selected));
  assert.deepEqual(config.cycleOrder, config.enabledModels);
  if (name === 'multi') {
    assert.equal(config.enabledModels.length, 20);
    for (const [id, provider] of Object.entries(providers)) {
      if (!builtins.has(id)) assert.equal(provider.models.length, 2, `${id}: card per enabled model`);
    }
  }
  for (const [id, provider] of Object.entries(providers)) {
    if (builtins.has(id)) {
      assert.equal(provider.models, undefined, `${id}: built-in must be override-only`);
      assert.equal(provider.api, undefined, `${id}: built-in inherits the catalog API`);
      const catalog = getBundledModels(id);
      const model = catalog.find(m => selected === `${id}/${m.id}`) ?? catalog[0];
      assert.ok(model, `${id}: bundled catalog must contain this provider`);
      assert.equal(provider.baseUrl, model.baseUrl, `${id}: default must match bundled catalog`);
      assert.equal(provider.apiKey, `!backstage-omp-key ${id}`);
    } else {
      assert.ok(id.startsWith('backstage-'));
      assert.equal(provider.api, 'openai-completions');
      assert.ok(provider.baseUrl && provider.models?.length);
      assert.ok(!provider.baseUrl.endsWith('/chat/completions'));
    }
    if (id === 'openai-codex') {
      assert.equal(provider.auth, 'oauth');
      assert.equal(provider.headers['chatgpt-account-id'], '!backstage-omp-key openai-codex-account-id');
    }
    if (id === 'backstage-custom' || id === 'backstage-ollama') {
      assert.equal(provider.auth, 'none');
      assert.equal(provider.apiKey, undefined);
    }
  }
  assert.equal(Object.keys(providers).length, ['mixed', 'multi'].includes(name) ? 10 : 1);
  console.log(`PASS ${name}: models.yml schema + provider validation; config.yml YAML`);
}
console.log(`PASS omp ${version}: ${cases.length} Kotlin-generated fixture sets`);
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bun', required=True, type=Path)
    parser.add_argument('--tree', required=True, type=Path)
    parser.add_argument('--fixtures', type=Path, default=REPO / 'backstage/android/build/omp-config-fixtures')
    args = parser.parse_args()
    pins = dict(line.split('=', 1) for line in (REPO / 'upstream/omp-termux/UPSTREAM.env').read_text().splitlines() if line and not line.startswith('#'))
    archive = REPO / f'.cache/omp/bun-linux-x64-{pins["BUN_VERSION"]}.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == pins['BUN_HOST_SHA256'], 'host Bun archive SHA-256 mismatch'
    with zipfile.ZipFile(archive) as zipped:
        expected = hashlib.sha256(zipped.read('bun-linux-x64/bun')).digest()
    assert hashlib.sha256(args.bun.read_bytes()).digest() == expected, 'host Bun executable differs from the pinned archive'
    package = args.tree / 'install/global/node_modules/@oh-my-pi/pi-coding-agent/package.json'
    assert json.loads(package.read_text())['version'] == pins['OMP_VERSION'], 'omp package version mismatch'
    print(f'PASS host Bun {pins["BUN_VERSION"]}: archive SHA-256 {pins["BUN_HOST_SHA256"]}; executable matches', flush=True)
    with tempfile.TemporaryDirectory(prefix='backstage-omp-config-') as scratch:
        script = Path(scratch) / 'validate.ts'
        script.write_text(JS)
        subprocess.run([str(args.bun.resolve()), str(script), str(args.tree.resolve()), str(args.fixtures.resolve()), pins['OMP_VERSION']], check=True)


if __name__ == '__main__':
    main()
