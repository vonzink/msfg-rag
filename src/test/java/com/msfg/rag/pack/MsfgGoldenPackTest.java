package com.msfg.rag.pack;

import com.msfg.rag.service.ai.PromptBuilderService;
import com.msfg.rag.service.ai.QuestionCategory;
import com.msfg.rag.service.ai.RulesService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden regression lock for the MSFG domain pack.
 * Literals are transcribed from the Java service constants.
 * If a test fails: FIX THE YAML, never the literal.
 */
class MsfgGoldenPackTest {

    private static final DomainPack PACK = TestPacks.msfg();

    @Test
    void identity() {
        assertEquals("mortgage", PACK.slug());
        assertEquals("Mountain State Financial Group", PACK.companyName());
        assertEquals(
                "This answer is for general mortgage education only and is not a loan approval, "
                + "underwriting decision, legal advice, or tax advice.",
                PACK.disclaimer());
    }

    @Test
    void skeletonIsByteExact() {
        String expected = """
                You are the AI assistant for Mountain State Financial Group LLC. You help website
                visitors, borrowers, real estate agents, financial professionals, and internal staff
                with clear, accurate, helpful mortgage guidance.

                Personality and tone:
                - Friendly but professional; confident but never pushy or salesy.
                - Clear and easy to understand for people who may not know mortgage terminology.
                - Respectful of compliance, lending rules, and borrower privacy.
                - Not robotic or needlessly technical. When it helps, explain the concept in plain
                English first, then add the guideline-level detail.

                Expertise:
                - Act as a mortgage underwriting and loan-structuring expert across conventional, FHA,
                VA, jumbo, non-QM, and DSCR lending; self-employed and rental income analysis; assets,
                credit, title, insurance, disclosures, and closing conditions; and common underwriting
                conditions and how to resolve them.
                - You may explain general mortgage guidelines, but you must not guarantee approval, quote
                final terms, make credit decisions, or state that a borrower is approved unless an
                authorized human loan officer or underwriting system has explicitly provided that.

                You must answer ONLY using the approved source context provided below. Do not perform
                external research, browse the web, or answer from general knowledge; if the approved
                source context does not cover the question, say you cannot find enough information.

                Hard rules — follow these without exception:
                %s

                Guidance — strong recommendations:
                %s

                Approved Source Context:
                %s

                User Question:
                %s

                Return ONLY valid JSON in exactly this format, with no other text before or after it:

                {
                  "answer": "...",
                  "citations": [
                    {
                      "source_name": "...",
                      "document_name": "...",
                      "section": "...",
                      "page_number": "...",
                      "effective_date": "..."
                    }
                  ],
                  "confidence": 0.0,
                  "human_escalation_required": false,
                  "disclaimer": "%s"
                }
                """;
        assertEquals(expected, PACK.promptTemplate());
    }

    @Test
    void defaultHardRulesAreByteExact() {
        String expected =
                "1. Do not answer from general knowledge.\n" +
                "2. Do not invent mortgage guidelines.\n" +
                "3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.\n" +
                "4. If the source context does not answer the question, say you cannot find enough information.\n" +
                "5. Include citations from the provided source context. The \"citations\"\n" +
                "   array is REQUIRED and must contain at least one entry whenever\n" +
                "   source context is provided above. Cite every [Source N] you relied\n" +
                "   on to write the answer. NEVER return an empty \"citations\" array\n" +
                "   when source context is present — if you used the sources to answer,\n" +
                "   you must list them.\n" +
                "6. In citations, copy source_name, document_name, section, page_number, and\n" +
                "   effective_date EXACTLY as given in the source context metadata. If a field is\n" +
                "   not present for a source, set it to null. NEVER invent page numbers, section\n" +
                "   names, or dates.\n" +
                "7. Pay attention to which loan program each source covers (FHA, VA, conventional).\n" +
                "   If the question is about one program, do not answer using a different\n" +
                "   program's guideline. If no source covers the right program, say you cannot\n" +
                "   find enough information.\n" +
                "8. Never promise or imply loan approval, call a loan \"guaranteed,\" or quote final rates,\n" +
                "   fees, or terms. Approvals and pricing come only from an authorized loan officer,\n" +
                "   pricing engine, or underwriting system.\n" +
                "9. Follow fair-lending and consumer-protection law (ECOA, Fair Lending, RESPA, TILA,\n" +
                "   UDAAP). Never discourage an applicant or treat anyone differently based on a protected\n" +
                "   class.\n" +
                "10. Never help anyone hide debt, misrepresent income or assets, alter documents, or skip\n" +
                "   required disclosures. All mortgage information must be accurate and fully disclosed; if\n" +
                "   asked to do otherwise, refuse and explain why.";
        assertEquals(expected, PACK.hardRules());
    }

    @Test
    void defaultGuidanceIsByteExact() {
        String expected =
                "1. Use careful wording such as \"may,\" \"generally,\" and \"subject to full loan review.\"\n" +
                "2. Keep the answer clear and borrower-friendly.\n" +
                "3. For a simple question, answer in a few sentences. For a complex one, give the direct\n" +
                "   answer first, then the guideline-level reasoning, then important exceptions or risks,\n" +
                "   then a practical next step.\n" +
                "4. Use markdown tables, checklists, or step-by-step breakdowns inside the answer when they\n" +
                "   make it clearer (for example, a document checklist or an income-calculation breakdown).\n" +
                "   Label estimates and assumptions; never present an estimate as a final figure.\n" +
                "5. When the answer depends on the loan program, investor, AUS findings, credit, income,\n" +
                "   assets, property type, occupancy, or state law, say so, and name the concrete next step\n" +
                "   (for example, confirm against current guidelines, or have a licensed loan officer or\n" +
                "   underwriter review before any final decision).\n" +
                "6. Do not give legal, tax, investment, or financial-planning advice; share general\n" +
                "   educational information and point the user to the right licensed professional.";
        assertEquals(expected, PACK.guidance());
    }

    @Test
    void guardrailsMatchLegacyConstants() {
        List<String> expectedPhrases = List.of(
                "you qualify",
                "you are approved",
                "you're approved",
                "you will be approved",
                "guaranteed",
                "the underwriter must accept",
                "the underwriter will accept",
                "this will close",
                "this loan will close",
                "legal advice:",
                "as your lawyer",
                "as your tax advisor"
        );
        assertEquals(expectedPhrases, PACK.guardrails().prohibitedPhrases());
        assertEquals("you are eligible", PACK.guardrails().eligiblePhrase());

        DomainPack.CannedAnswers ca = PACK.guardrails().cannedAnswers();
        assertEquals(
                "I could not find enough information in the approved mortgage guidelines to answer "
                + "that confidently. Please contact a licensed loan officer for review.",
                ca.noSource());
        assertEquals(
                "This question depends on your full loan file and should be reviewed by a licensed "
                + "loan officer. I can explain the general guideline, but I cannot determine approval "
                + "or eligibility here.",
                ca.escalation());
        assertEquals(
                "I can't provide legal advice. For legal questions about your mortgage or lender, "
                + "please consult a licensed attorney. I'm happy to explain general mortgage "
                + "guidelines if that helps.",
                ca.legal());
        assertEquals(
                "I can't provide tax advice. A licensed tax professional can review your specific "
                + "situation. I'm happy to explain general mortgage guidelines if that helps.",
                ca.tax());
        assertEquals(
                "I don't have access to live rate data, and rates depend on your full loan scenario. "
                + "A licensed loan officer at Mountain State Financial Group can provide a current, "
                + "personalized quote.",
                ca.liveRates());
        assertEquals(
                "I can't help with that. Misrepresenting income, debts, or documents on a mortgage "
                + "application is fraud. If you have questions about what must be disclosed, a "
                + "licensed loan officer can walk you through the requirements.",
                ca.fraud());
    }

    @Test
    void classifierRulesMatchLegacy() {
        List<DomainPack.ClassifierRule> rules = PACK.classifierRules();
        assertEquals(5, rules.size());

        // Rule 0: FRAUD (must be first — priority order)
        assertEquals(QuestionCategory.FRAUD, rules.get(0).category());
        assertEquals(List.of(
                "\\b(hide|hiding|conceal)\\b.*\\b(income|debt|loan|liabilit|asset)",
                "\\b(fake|falsif|forge|doctor|alter)\\w*\\b.*\\b(document|paystub|pay stub|w-?2|bank statement|tax return)",
                "\\b(lie|lying)\\b.*\\b(lender|application|underwriter|loan)",
                "\\bnot (tell|report|disclose)\\b.*\\b(lender|debt|income|loan)",
                "\\bwithout (the )?(lender|bank) (knowing|finding out)"
        ), rules.get(0).patterns());

        // Rule 1: ELIGIBILITY
        assertEquals(QuestionCategory.ELIGIBILITY, rules.get(1).category());
        assertEquals(List.of(
                "\\b(do|would|will|can|could) i (pre-?)?(qualify|get (pre-?)?approved)\\b",
                "\\b(am i|are we) (eligible|approved|qualified)\\b",
                "\\bwill (i|we) (be )?(approved|denied|turned down)\\b",
                "\\b(approve|deny) (me|my loan|my application)\\b",
                "\\bhow much (house|home|mortgage|loan) (can|could) (i|we) (afford|get|qualify)\\b"
        ), rules.get(1).patterns());

        // Rule 2: LEGAL
        assertEquals(QuestionCategory.LEGAL, rules.get(2).category());
        assertEquals(List.of(
                "\\b(sue|suing|lawsuit|litigation)\\b",
                "\\b(is (it|this) legal|illegal)\\b",
                "\\b(lawyer|attorney)\\b.*\\b(need|should|hire)\\b",
                "\\b(need|should|hire)\\b.*\\b(lawyer|attorney)\\b",
                "\\bbreach of contract\\b"
        ), rules.get(2).patterns());

        // Rule 3: TAX
        assertEquals(QuestionCategory.TAX, rules.get(3).category());
        assertEquals(List.of(
                "\\b(should|how do|how should) (i|we) file\\b.*\\btax",
                "\\btax (strategy|advice|loophole)\\b",
                "\\b(write|writing) off\\b.*\\b(mortgage|interest|points)\\b",
                "\\b(deduct|deduction)\\b.*\\b(should|can) i\\b",
                "\\bclaim\\b.*\\bon (my|our) tax(es)?\\b"
        ), rules.get(3).patterns());

        // Rule 4: LIVE_RATES
        assertEquals(QuestionCategory.LIVE_RATES, rules.get(4).category());
        assertEquals(List.of(
                "\\bwhat('s| is| are)? (the |your |today)?\\w* ?rates? (can i get|today|right now|currently)\\b",
                "\\b(current|today'?s?) (interest )?rates?\\b",
                "\\bquote me\\b",
                "\\brate (quote|lock)\\b.*\\b(today|now|get)\\b",
                "\\bwhat rate\\b.*\\b(get|offer|give)\\b"
        ), rules.get(4).patterns());
    }

    @Test
    void defaultAssemblyIsByteExact() {
        RulesService rulesService = mock(RulesService.class);
        when(rulesService.effectiveHard()).thenReturn(PACK.hardRules());
        when(rulesService.effectiveGuidance()).thenReturn(PACK.guidance());

        String assembled = new PromptBuilderService(PACK, rulesService).build("Q", List.of());

        String expected = PACK.promptTemplate().formatted(
                PACK.hardRules(),
                PACK.guidance(),
                "(no source context found)",
                "Q",
                PACK.disclaimer());
        assertEquals(expected, assembled);

        // Assembly-mechanics anchors — byte-exact
        assertTrue(assembled.contains("Hard rules — follow these without exception:"));
        assertTrue(assembled.contains("Guidance — strong recommendations:"));
        assertTrue(assembled.contains("\"human_escalation_required\": false"));
    }

    @Test
    void sourceLinksMatchSeed() {
        List<DomainPack.SourceLink> links = PACK.sourceLinks();
        assertEquals(5, links.size());

        DomainPack.SourceLink fannie = links.get(0);
        assertEquals("Fannie Mae Selling Guide", fannie.name());
        assertEquals("https://selling-guide.fanniemae.com/", fannie.url());
        assertEquals("fanniemae.com", fannie.domain());
        assertEquals("PRIMARY", fannie.authority());
        assertEquals(List.of("conventional", "conforming", "underwriting", "appraisal"), fannie.topics());
        assertTrue(fannie.freshnessRequired());
        assertEquals(List.of("cite conventional underwriting and eligibility requirements"), fannie.allowedUse());
        assertEquals(List.of("FHA, VA, or USDA program rules"), fannie.doNotUseFor());
        assertEquals("BOTH", fannie.surface());

        assertEquals("Freddie Mac Single-Family Seller/Servicer Guide", links.get(1).name());
        assertEquals("freddiemac.com", links.get(1).domain());
        assertEquals("PRIMARY", links.get(1).authority());

        assertEquals("HUD Handbook 4000.1 (FHA Single Family Housing Policy Handbook)", links.get(2).name());
        assertEquals("hud.gov", links.get(2).domain());

        assertEquals("VA Lender's Handbook (M26-7)", links.get(3).name());
        assertEquals("benefits.va.gov", links.get(3).domain());

        assertEquals("USDA Single Family Housing Guaranteed Loan Program Handbook (HB-1-3555)", links.get(4).name());
        assertEquals("rd.usda.gov", links.get(4).domain());

        // every seeded link is PRIMARY / BOTH and freshness-required
        for (DomainPack.SourceLink link : links) {
            assertEquals("PRIMARY", link.authority());
            assertEquals("BOTH", link.surface());
            assertTrue(link.freshnessRequired());
        }
    }

    @Test
    void pageGuidesMatchSeed() {
        List<DomainPack.PageGuide> guides = PACK.pageGuides();
        assertEquals(3, guides.size());

        DomainPack.PageGuide fha = guides.get(0);
        assertEquals("/loans/fha", fha.route());
        assertEquals("FHA Loans", fha.title());
        assertEquals(
                "Explain FHA loan basics and direct the user toward FHA eligibility and next steps.",
                fha.purpose());
        assertEquals("BOTH", fha.surface());
        assertEquals(List.of("understand FHA loans", "FHA down payment", "FHA credit requirements"),
                fha.userIntents());
        assertEquals(List.of(
                "explain the 3.5% minimum down payment for qualifying credit scores",
                "explain that FHA loans require mortgage insurance (UFMIP and annual MIP)"),
                fha.allowedGuidance());
        assertEquals(List.of(
                new DomainPack.InternalLink("Start an FHA application", "/apply?program=fha"),
                new DomainPack.InternalLink("FHA eligibility checklist", "/loans/fha/eligibility")),
                fha.internalLinks());
        assertEquals(List.of("fha", "government", "down-payment"), fha.topics());

        assertEquals("/loans/conventional", guides.get(1).route());
        assertEquals("Conventional Loans", guides.get(1).title());
        assertEquals("BOTH", guides.get(1).surface());

        assertEquals("/loans/duplex", guides.get(2).route());
        assertEquals("2-4 Unit (Duplex) Properties", guides.get(2).title());
        assertEquals(List.of("duplex", "2-unit", "multi-unit", "investment"), guides.get(2).topics());

        // every seeded guide must have a non-blank title and a non-blank purpose
        for (var guide : guides) {
            assertFalse(guide.title() == null || guide.title().isBlank(), "every page guide has a title");
            assertFalse(guide.purpose() == null || guide.purpose().isBlank(), "every page guide has a purpose");
        }
    }

    @Test
    void retrievalRulesMatchLegacy() {
        // Full acronym map — 30 entries
        assertEquals(Map.ofEntries(
                Map.entry("pmi",              "private mortgage insurance"),
                Map.entry("mip",              "mortgage insurance premium"),
                Map.entry("dti",              "debt-to-income"),
                Map.entry("ltv",              "loan-to-value"),
                Map.entry("cltv",             "combined loan-to-value"),
                Map.entry("piti",             "principal interest taxes insurance"),
                Map.entry("arm",              "adjustable-rate mortgage"),
                Map.entry("heloc",            "home equity line of credit"),
                Map.entry("hoa",              "homeowners association"),
                Map.entry("apr",              "annual percentage rate"),
                Map.entry("aus",              "automated underwriting system"),
                Map.entry("fha",              "Federal Housing Administration"),
                Map.entry("va",               "Veterans Affairs"),
                Map.entry("usda",             "United States Department of Agriculture"),
                Map.entry("duplex",           "2-unit two-unit 2-4 units"),
                Map.entry("triplex",          "3-unit three-unit 2-4 units"),
                Map.entry("fourplex",         "4-unit four-unit 2-4 units"),
                Map.entry("quadplex",         "4-unit four-unit 2-4 units"),
                Map.entry("owner occupied",   "principal residence"),
                Map.entry("primary residence","principal residence"),
                Map.entry("primary",          "principal residence"),
                Map.entry("non-owner occupied","investment property"),
                Map.entry("investment",       "investment property"),
                Map.entry("rental",           "investment property"),
                Map.entry("vacation home",    "second home"),
                Map.entry("rate and term",    "limited cash-out refinance"),
                Map.entry("cash out",         "cash-out refinance"),
                Map.entry("condo",            "condominium project"),
                Map.entry("self employed",    "self-employment income"),
                Map.entry("jumbo",            "non-conforming")
        ), PACK.acronymExpansions());

        // Program rules — 4 in order
        List<DomainPack.ProgramRule> programs = PACK.programRules();
        assertEquals(4, programs.size());

        // FHA
        DomainPack.ProgramRule fha = programs.get(0);
        assertEquals("FHA", fha.program());
        assertEquals(List.of("fha", "hud", "4000.1"), fha.keywords());
        assertEquals(List.of(), fha.wordPatterns());

        // VA
        DomainPack.ProgramRule va = programs.get(1);
        assertEquals("VA", va.program());
        assertEquals(List.of("veteran"), va.keywords());
        assertEquals(List.of("\\bva\\b"), va.wordPatterns());

        // USDA
        DomainPack.ProgramRule usda = programs.get(2);
        assertEquals("USDA", usda.program());
        assertEquals(List.of("usda", "rural development"), usda.keywords());
        assertEquals(List.of(), usda.wordPatterns());

        // CONVENTIONAL
        DomainPack.ProgramRule conv = programs.get(3);
        assertEquals("CONVENTIONAL", conv.program());
        assertEquals(List.of("conventional", "fannie", "freddie", "conforming"), conv.keywords());
        assertEquals(List.of(), conv.wordPatterns());
    }
}
