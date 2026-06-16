package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-CRUD service over the source-link registry, plus a short-cached
 * activeLinks() read snapshot for the (Phase 6) retrieval seam. The cache mirrors
 * VocabularyService exactly: 10s nanoTime TTL with a Long.MIN_VALUE sentinel
 * tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing
 * in Phase 3 reads activeLinks() — it is the integration point for later phases.
 */
@Service
public class SourceLinkService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainSourceLinkRepository repo;

    private volatile List<BrainSourceLink> cache = List.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public SourceLinkService(BrainSourceLinkRepository repo) {
        this.repo = repo;
    }

    public List<SourceLinkDto> list() {
        return repo.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(SourceLinkDto::from)
                .toList();
    }

    public SourceLinkDto get(UUID id) {
        return SourceLinkDto.from(find(id));
    }

    @Transactional
    public SourceLinkDto create(SourceLinkRequest req, String createdBy) {
        String name = required(req.name(), "name");
        String url = required(req.url(), "url");
        LinkAuthority authority = authority(req.authority());
        Surface surface = surface(req.surface());

        BrainSourceLink link = new BrainSourceLink(
                name, url, strip(req.domain()), authority,
                cleanList(req.topics()), req.freshnessRequired(),
                cleanList(req.allowedUse()), cleanList(req.doNotUseFor()),
                surface, createdBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public SourceLinkDto update(UUID id, SourceLinkRequest req, String updatedBy) {
        BrainSourceLink link = find(id);
        link.setName(required(req.name(), "name"));
        link.setUrl(required(req.url(), "url"));
        link.setDomain(strip(req.domain()));
        link.setAuthority(authority(req.authority()));
        link.setTopics(cleanList(req.topics()));
        link.setFreshnessRequired(req.freshnessRequired());
        link.setAllowedUse(cleanList(req.allowedUse()));
        link.setDoNotUseFor(cleanList(req.doNotUseFor()));
        link.setSurface(surface(req.surface()));
        link.setUpdatedBy(updatedBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public SourceLinkDto setActive(UUID id, boolean active, String updatedBy) {
        BrainSourceLink link = find(id);
        link.setActive(active);
        link.setUpdatedBy(updatedBy);
        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        BrainSourceLink link = find(id);
        repo.delete(link);
        invalidate();
    }

    /** Cached snapshot of active links. Phase 6 retrieval seam — unused in Phase 3. */
    public List<BrainSourceLink> activeLinks() {
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

    private BrainSourceLink find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("source link not found: " + id));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static LinkAuthority authority(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("authority is required");
        }
        return LinkAuthority.valueOf(value.strip());
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
}
