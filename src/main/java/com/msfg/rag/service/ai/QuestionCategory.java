package com.msfg.rag.service.ai;

/**
 * Pre-retrieval classification of an incoming question (rag.md guardrails).
 * Only EDUCATIONAL questions proceed to RAG retrieval and the LLM.
 */
public enum QuestionCategory {

    /** General mortgage education — proceeds through the full RAG pipeline. */
    EDUCATIONAL,

    /** "Do I qualify?" / "Will I be approved?" — needs a licensed loan officer. */
    ELIGIBILITY,

    /** Legal questions (suing, contracts, disputes) — not legal advice. */
    LEGAL,

    /** Tax strategy questions — not tax advice. */
    TAX,

    /** Rate shopping — we have no live rate data connected. */
    LIVE_RATES,

    /** Requests to falsify, hide, or misrepresent — hard refusal. */
    FRAUD
}
