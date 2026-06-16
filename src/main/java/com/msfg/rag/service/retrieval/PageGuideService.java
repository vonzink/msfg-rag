package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.PageGuideDto;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-CRUD service over the page-guide registry, plus a short-cached
 * activePageGuides() read snapshot for the later retrieval/routing seam. The
 * cache mirrors SourceLinkService exactly: 10s nanoTime TTL with a Long.MIN_VALUE
 * sentinel tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing in
 * Phase 4 reads activePageGuides() — it is the integration point for later phases.
 */
@Service
public class PageGuideService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainPageGuideRepository repo;

    private volatile List<BrainPageGuide> cache = List.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public PageGuideService(BrainPageGuideRepository repo) {
        this.repo = repo;
    }

    public List<PageGuideDto> list() {
        return repo.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(PageGuideDto::from)
                .toList();
    }

    public PageGuideDto get(UUID id) {
        return PageGuideDto.from(find(id));
    }

    @Transactional
    public PageGuideDto create(PageGuideRequest req, String createdBy) {
        String title = required(req.title(), "title");
        String purpose = required(req.purpose(), "purpose");
        Surface surface = surface(req.surface());

        BrainPageGuide guide = new BrainPageGuide(
                route(req.route()), title, purpose, surface,
                cleanList(req.userIntents()), cleanList(req.allowedGuidance()),
                links(req.internalLinks()), ids(req.sourceLinkIds()),
                cleanList(req.topics()), createdBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public PageGuideDto update(UUID id, PageGuideRequest req, String updatedBy) {
        BrainPageGuide guide = find(id);
        guide.setRoute(route(req.route()));
        guide.setTitle(required(req.title(), "title"));
        guide.setPurpose(required(req.purpose(), "purpose"));
        guide.setSurface(surface(req.surface()));
        guide.setUserIntents(cleanList(req.userIntents()));
        guide.setAllowedGuidance(cleanList(req.allowedGuidance()));
        guide.setInternalLinks(links(req.internalLinks()));
        guide.setSourceLinkIds(ids(req.sourceLinkIds()));
        guide.setTopics(cleanList(req.topics()));
        guide.setUpdatedBy(updatedBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public PageGuideDto setActive(UUID id, boolean active, String updatedBy) {
        BrainPageGuide guide = find(id);
        guide.setActive(active);
        guide.setUpdatedBy(updatedBy);
        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        BrainPageGuide guide = find(id);
        repo.delete(guide);
        invalidate();
    }

    /** Cached snapshot of active page guides. Later-phase retrieval seam — unused in Phase 4. */
    public List<BrainPageGuide> activePageGuides() {
        long now = System.nanoTime();
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            cache = List.copyOf(repo.findByActiveTrueOrderByCreatedAtDescIdDesc());
            cachedAtNanos = now;
        }
        return cache;
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    // --- helpers ---

    private BrainPageGuide find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("page guide not found: " + id));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    /** route is optional: null stays null; otherwise stripped (blank → null). */
    private static String route(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Surface surface(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("surface is required");
        }
        return Surface.valueOf(value.strip());
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }

    /** Map request {label,url} rows to LinkRef; drop rows where both fields are blank. */
    private static List<LinkRef> links(List<PageGuideRequest.LinkRefRequest> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<LinkRef> out = new ArrayList<>(values.size());
        for (PageGuideRequest.LinkRefRequest v : values) {
            if (v == null) {
                continue;
            }
            String label = v.label() == null ? "" : v.label().strip();
            String url = v.url() == null ? "" : v.url().strip();
            if (label.isEmpty() && url.isEmpty()) {
                continue;
            }
            out.add(new LinkRef(label, url));
        }
        return out;
    }

    /** Convert String ids to UUID (a malformed value throws IllegalArgumentException → 400). */
    private static List<UUID> ids(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<UUID> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(UUID.fromString(v.strip()));
            }
        }
        return out;
    }
}
