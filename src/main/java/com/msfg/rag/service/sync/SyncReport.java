package com.msfg.rag.service.sync;

import java.util.List;
import java.util.Map;

/** What POST /api/ai/documents/sync returns: the plan and what happened. */
public record SyncReport(boolean dryRun,
                         Map<String, Integer> summary,
                         List<Result> results) {

    public record Result(String fileName, String action, String reason,
                         boolean executed, boolean succeeded, String error) {}
}
