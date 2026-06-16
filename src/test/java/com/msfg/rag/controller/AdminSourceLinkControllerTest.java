package com.msfg.rag.controller;

import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.service.retrieval.SourceLinkService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSourceLinkControllerTest {

    private final SourceLinkService service = mock(SourceLinkService.class);
    private final AdminSourceLinkController controller = new AdminSourceLinkController(service);

    private SourceLinkDto dto(UUID id, boolean active) {
        return new SourceLinkDto(id, "Fannie Mae Selling Guide",
                "https://selling-guide.fanniemae.com", "fanniemae.com", "PRIMARY",
                List.of("conventional"), true, List.of("cite"), List.of("legal advice"),
                "BOTH", active, OffsetDateTime.now(), "admin-api", OffsetDateTime.now(), "admin-api");
    }

    private SourceLinkRequest req() {
        return new SourceLinkRequest("Fannie Mae Selling Guide",
                "https://selling-guide.fanniemae.com", "fanniemae.com", "PRIMARY",
                List.of("conventional"), true, List.of("cite"), List.of("legal advice"), "BOTH");
    }

    @Test
    void listDelegates() {
        UUID id = UUID.randomUUID();
        when(service.list()).thenReturn(List.of(dto(id, true)));

        List<SourceLinkDto> result = controller.list();

        assertEquals(1, result.size());
        verify(service).list();
    }

    @Test
    void getDelegates() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(dto(id, true));

        SourceLinkDto result = controller.get(id);

        assertEquals(id, result.id());
        verify(service).get(id);
    }

    @Test
    void createDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        SourceLinkRequest body = req();
        when(service.create(body, "admin-api")).thenReturn(dto(id, true));

        SourceLinkDto result = controller.create(body);

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
        SourceLinkRequest body = req();
        when(service.update(id, body, "admin-api")).thenReturn(dto(id, true));

        SourceLinkDto result = controller.update(id, body);

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

        SourceLinkDto result = controller.activate(id);

        assertTrue(result.active());
        verify(service).setActive(id, true, "admin-api");
    }

    @Test
    void deactivateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, false, "admin-api")).thenReturn(dto(id, false));

        SourceLinkDto result = controller.deactivate(id);

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
