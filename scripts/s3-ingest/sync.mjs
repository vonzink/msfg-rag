#!/usr/bin/env node
// Sync the S3 rag-brain/ corpus into the Mortgage Brain via its admin API.
// Idempotent by fileName. Safe to re-run. See README.md.
import { S3Client, ListObjectsV2Command, GetObjectCommand } from '@aws-sdk/client-s3';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { planSync, summarize } from './plan.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);

function flag(name) { return args.includes(name); }
function val(name) {
  const i = args.indexOf(name);
  return i !== -1 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : null;
}
function die(msg) { console.error('ERROR: ' + msg); process.exit(1); }

const DRY_RUN = flag('--dry-run');
const BASE_URL = (val('--base-url') || process.env.BRAIN_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const BUCKET = val('--bucket') || process.env.S3_BUCKET || 'msfg.us';
const PREFIX = val('--prefix') || process.env.S3_PREFIX || 'rag-brain/';
const REGION = process.env.AWS_REGION || 'us-west-1';
const ADMIN_KEY = process.env.ADMIN_API_KEY || '';
const MANIFEST_KEY = PREFIX + '_manifest.json';

const s3 = new S3Client({ region: REGION });

async function s3Bytes(key) {
  const out = await s3.send(new GetObjectCommand({ Bucket: BUCKET, Key: key }));
  return Buffer.from(await out.Body.transformToByteArray());
}

async function loadManifest() {
  // Prefer the manifest in S3 (corpus is self-describing); fall back to the local copy.
  try {
    return JSON.parse((await s3Bytes(MANIFEST_KEY)).toString('utf8'));
  } catch {
    try { return JSON.parse(readFileSync(join(HERE, 'manifest.json'), 'utf8')); }
    catch { return { defaults: {}, files: {} }; }
  }
}

async function listCorpus() {
  const files = [];
  let token;
  do {
    const out = await s3.send(new ListObjectsV2Command({
      Bucket: BUCKET, Prefix: PREFIX, ContinuationToken: token,
    }));
    for (const o of out.Contents || []) {
      if (o.Key === PREFIX || o.Key === MANIFEST_KEY || o.Key.endsWith('/')) continue;
      files.push(o.Key.slice(PREFIX.length));
    }
    token = out.IsTruncated ? out.NextContinuationToken : undefined;
  } while (token);
  return files;
}

async function brainList() {
  const res = await fetch(`${BASE_URL}/api/ai/documents`, { headers: { 'X-Admin-Api-Key': ADMIN_KEY } });
  if (!res.ok) die(`brain list failed: HTTP ${res.status} ${await res.text()}`);
  return res.json();
}

async function brainUpload(fileName, bytes, meta) {
  const fd = new FormData();
  fd.append('file', new Blob([bytes]), fileName);
  fd.append('title', meta.title);
  fd.append('sourceName', meta.sourceName);
  fd.append('sourceType', meta.sourceType);
  if (meta.documentVersion) fd.append('documentVersion', meta.documentVersion);
  if (meta.effectiveDate) fd.append('effectiveDate', meta.effectiveDate);
  if (meta.expirationDate) fd.append('expirationDate', meta.expirationDate);
  const res = await fetch(`${BASE_URL}/api/ai/documents/upload`, {
    method: 'POST', headers: { 'X-Admin-Api-Key': ADMIN_KEY }, body: fd,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

async function brainSetActive(id, active) {
  const res = await fetch(`${BASE_URL}/api/ai/documents/${id}/${active ? 'activate' : 'deactivate'}`, {
    method: 'POST', headers: { 'X-Admin-Api-Key': ADMIN_KEY },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
}

async function main() {
  if (!ADMIN_KEY) die('ADMIN_API_KEY not set — source the brain .env first.');
  console.log(`brain=${BASE_URL} bucket=${BUCKET} prefix=${PREFIX}${DRY_RUN ? '  (DRY RUN)' : ''}`);

  const [manifest, s3Files, brainDocs] = await Promise.all([loadManifest(), listCorpus(), brainList()]);
  console.log(`s3 objects: ${s3Files.length} | brain docs: ${brainDocs.length}`);

  const actions = planSync({ s3Files, manifest, brainDocs });
  for (const a of actions) {
    console.log(`  ${a.action.toUpperCase().padEnd(11)} ${a.fileName}${a.reason ? '  — ' + a.reason : ''}`);
  }
  console.log('plan:', JSON.stringify(summarize(actions)));
  if (DRY_RUN) { console.log('dry run — no changes made.'); return; }

  let changed = 0, failed = 0;
  for (const a of actions) {
    try {
      if (a.action === 'upload') {
        const dto = await brainUpload(a.fileName, await s3Bytes(PREFIX + a.fileName), a.meta);
        console.log(`  uploaded   ${a.fileName} -> ${dto.id}`);
        changed++;
      } else if (a.action === 'reactivate') {
        await brainSetActive(a.documentId, true);
        console.log(`  reactivated ${a.fileName}`);
        changed++;
      } else if (a.action === 'deactivate') {
        await brainSetActive(a.documentId, false);
        console.log(`  deactivated ${a.fileName}`);
        changed++;
      }
    } catch (e) {
      console.error(`  FAILED ${a.action} ${a.fileName}: ${e.message}`);
      failed++;
    }
  }
  console.log(`done. changed=${changed} failed=${failed}`);
  if (failed) process.exit(1);
}

main().catch((e) => die((e && e.stack) || String(e)));
