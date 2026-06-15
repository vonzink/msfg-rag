// Pure planning logic for the S3 -> Mortgage Brain ingestion sync.
// No IO here, so the decision logic can be unit-tested in isolation.

// Mirror of the brain's DocumentAdminController.ALLOWED_EXTENSIONS.
export const ALLOWED_EXT = new Set(['pdf', 'docx', 'txt', 'md', 'markdown', 'html', 'htm']);

export function extOf(name) {
  const i = name.lastIndexOf('.');
  return i === -1 ? '' : name.slice(i + 1).toLowerCase();
}

// Human-ish title fallback when the manifest doesn't name a file.
function deriveTitle(fileName) {
  return fileName.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ').trim();
}

// Merge a manifest entry with defaults for one file. Always returns safe fields.
export function resolveEntry(manifest, fileName) {
  const defaults = (manifest && manifest.defaults) || {};
  const entry = (manifest && manifest.files && manifest.files[fileName]) || {};
  return {
    fileName,
    ingest: entry.ingest !== false, // default true
    reason: entry.reason || null,
    title: entry.title || deriveTitle(fileName),
    sourceName: entry.sourceName || defaults.sourceName || 'MSFG Knowledge Base',
    sourceType: entry.sourceType || defaults.sourceType || 'AGENCY_GUIDELINE',
    documentVersion: entry.documentVersion || null,
    effectiveDate: entry.effectiveDate || null,
    expirationDate: entry.expirationDate || null,
  };
}

// Build an idempotent action plan, keyed by fileName against the brain's docs.
//   s3Files:   string[] of object basenames in the corpus (manifest excluded by caller)
//   manifest:  parsed manifest object
//   brainDocs: DocumentDto[] from GET /api/ai/documents
// Returns: Array<{ fileName, action, reason?, meta?, documentId? }>
//   action ∈ upload | reactivate | deactivate | skip
export function planSync({ s3Files, manifest, brainDocs }) {
  const byName = new Map();
  for (const d of brainDocs || []) byName.set(d.fileName, d);

  const actions = [];
  const wanted = new Set(); // fileNames we intend to keep active in the brain

  for (const fileName of s3Files) {
    const meta = resolveEntry(manifest, fileName);
    if (!meta.ingest) {
      actions.push({ fileName, action: 'skip', reason: meta.reason || 'ingest:false' });
      continue;
    }
    if (!ALLOWED_EXT.has(extOf(fileName))) {
      actions.push({ fileName, action: 'skip', reason: `unsupported extension .${extOf(fileName)}` });
      continue;
    }
    wanted.add(fileName);
    const existing = byName.get(fileName);
    if (existing && existing.active) {
      actions.push({ fileName, action: 'skip', reason: 'already ingested', documentId: existing.id });
    } else if (existing) {
      actions.push({ fileName, action: 'reactivate', documentId: existing.id, meta });
    } else {
      actions.push({ fileName, action: 'upload', meta });
    }
  }

  // Deactivate brain docs no longer in the corpus (removed from S3, or now ingest:false).
  for (const d of brainDocs || []) {
    if (d.active && !wanted.has(d.fileName)) {
      actions.push({ fileName: d.fileName, action: 'deactivate', documentId: d.id, reason: 'not in current corpus' });
    }
  }
  return actions;
}

export function summarize(actions) {
  const s = { upload: 0, reactivate: 0, deactivate: 0, skip: 0 };
  for (const a of actions) s[a.action] = (s[a.action] || 0) + 1;
  return s;
}
