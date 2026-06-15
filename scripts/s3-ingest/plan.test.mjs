import { test } from 'node:test';
import assert from 'node:assert/strict';
import { planSync, resolveEntry, extOf } from './plan.mjs';

const manifest = {
  defaults: { sourceName: 'MSFG Knowledge Base', sourceType: 'AGENCY_GUIDELINE' },
  files: {
    'loandoc.md': { title: 'Mortgage Document Matrix', sourceType: 'EDUCATIONAL' },
    'LLPA matrix.pdf': { ingest: false, reason: 'duplicate of rag markdown' },
    'Loan Limit.xlsx': {},
  },
};

test('extOf lowercases and handles no extension', () => {
  assert.equal(extOf('A.PDF'), 'pdf');
  assert.equal(extOf('noext'), '');
});

test('resolveEntry merges defaults and derives a title', () => {
  const e = resolveEntry(manifest, 'FHA handbook.pdf');
  assert.equal(e.ingest, true);
  assert.equal(e.title, 'FHA handbook');
  assert.equal(e.sourceType, 'AGENCY_GUIDELINE');
  assert.equal(e.sourceName, 'MSFG Knowledge Base');
});

test('new file -> upload with resolved meta', () => {
  const actions = planSync({ s3Files: ['loandoc.md'], manifest, brainDocs: [] });
  assert.equal(actions.length, 1);
  assert.equal(actions[0].action, 'upload');
  assert.equal(actions[0].meta.sourceType, 'EDUCATIONAL');
});

test('ingest:false -> skip with reason', () => {
  const actions = planSync({ s3Files: ['LLPA matrix.pdf'], manifest, brainDocs: [] });
  assert.equal(actions[0].action, 'skip');
  assert.match(actions[0].reason, /duplicate/);
});

test('unsupported extension -> skip', () => {
  const actions = planSync({ s3Files: ['Loan Limit.xlsx'], manifest, brainDocs: [] });
  assert.equal(actions[0].action, 'skip');
  assert.match(actions[0].reason, /unsupported/);
});

test('already-ingested active -> skip (idempotent)', () => {
  const brainDocs = [{ id: 'x', fileName: 'loandoc.md', active: true }];
  const actions = planSync({ s3Files: ['loandoc.md'], manifest, brainDocs });
  assert.equal(actions[0].action, 'skip');
  assert.equal(actions[0].reason, 'already ingested');
});

test('inactive existing -> reactivate', () => {
  const brainDocs = [{ id: 'x', fileName: 'loandoc.md', active: false }];
  const actions = planSync({ s3Files: ['loandoc.md'], manifest, brainDocs });
  assert.equal(actions[0].action, 'reactivate');
});

test('brain doc no longer in corpus -> deactivate', () => {
  const brainDocs = [{ id: 'y', fileName: 'old.md', active: true }];
  const actions = planSync({ s3Files: ['loandoc.md'], manifest, brainDocs });
  const deact = actions.find((a) => a.action === 'deactivate');
  assert.ok(deact, 'expected a deactivate action');
  assert.equal(deact.documentId, 'y');
});

test('skipped-but-previously-ingested -> deactivate', () => {
  const brainDocs = [{ id: 'z', fileName: 'LLPA matrix.pdf', active: true }];
  const actions = planSync({ s3Files: ['LLPA matrix.pdf'], manifest, brainDocs });
  assert.ok(actions.some((a) => a.action === 'skip' && a.fileName === 'LLPA matrix.pdf'));
  assert.ok(actions.some((a) => a.action === 'deactivate' && a.documentId === 'z'));
});
