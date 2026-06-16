package com.msfg.rag.controller;

import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.dto.PageGuideDto;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.service.retrieval.PageGuideService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPageGuideControllerTest {

    private final PageGuideService service = mock(PageGuideService.class);
    private final AdminPageGuideController controller = new AdminPageGuideController(service);

    private PageGuideDto dto(UUID id, boolean active) {
        return new PageGuideDto(id, "/loans/fha", "FHA Loans", "Help users understand FHA.",
                "BOTH", List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new LinkRef("FHA loans", "/loans/fha")), List.of(UUID.randomUUID().toString()),
                List.of("fha"), active, OffsetDateTime.now(), "admin-api", OffsetDateTime.now(), "admin-api");
    }

    private PageGuideRequest req() {
        return new PageGuideRequest("/loans/fha", "FHA Loans", "Help users understand FHA.", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new PageGuideRequest.LinkRefRequest("FHA loans", "/loans/fha")),
                List.of(UUID.randomUUID().toString()), List.of("fha"));
    }

    @Test
    void listDelegates() {
        UUID id = UUID.randomUUID();
        when(service.list()).thenReturn(List.of(dto(id, true)));

        List<PageGuideDto> result = controller.list();

        assertEquals(1, result.size());
        verify(service).list();
    }

    @Test
    void getDelegates() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(dto(id, true));

        PageGuideDto result = controller.get(id);

        assertEquals(id, result.id());
        verify(service).get(id);
    }

    @Test
    void createDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        PageGuideRequest body = req();
        when(service.create(body, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.create(body);

        assertEquals(id, result.id());
        verify(service).create(body, "admin-api");
    }

    @Test
    void createRejectsNullBody() {
        assertThrows(IllegalArgumentException.class, () -> controller.create(null));
        verify(service, never()).create(any(), any());
    }

    @Test
    void updateDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        PageGuideRequest body = req();
        when(service.update(id, body, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.update(id, body);

        assertEquals(id, result.id());
        verify(service).update(id, body, "admin-api");
    }

    @Test
    void updateRejectsNullBody() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> controller.update(id, null));
        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void activateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, true, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.activate(id);

        assertTrue(result.active());
        verify(service).setActive(id, true, "admin-api");
    }

    @Test
    void deactivateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, false, "admin-api")).thenReturn(dto(id, false));

        PageGuideDto result = controller.deactivate(id);

        assertEquals(false, result.active());
        verify(service).setActive(id, false, "admin-api");
    }

    @Test
    void deleteDelegatesAndReturnsOk() {
        UUID id = UUID.randomUUID();

        var response = controller.delete(id);

        verify(service).delete(id);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("deleted"));
        assertEquals(id, response.getBody().get("id"));
    }
}
