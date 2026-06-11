package com.msfg.rag.controller;

import com.msfg.rag.domain.AuditLog;
import com.msfg.rag.dto.AuditPageDto;
import com.msfg.rag.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditControllerTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AdminAuditController controller = new AdminAuditController(repository);

    private AuditLog entry(String question) {
        AuditLog log = new AuditLog();
        log.setUserQuestion(question);
        return log;
    }

    @Test
    void listMapsPageAndClampsSize() {
        when(repository.search(eq(false), eq(PageRequest.of(0, 100))))
                .thenReturn(new PageImpl<>(List.of(entry("What is PMI?")),
                        PageRequest.of(0, 20), 1));

        AuditPageDto page = controller.list(0, 500, false, null);

        assertEquals(1, page.items().size());
        assertEquals("What is PMI?", page.items().get(0).question());
        assertEquals(1, page.total());
        verify(repository).search(eq(false), eq(PageRequest.of(0, 100)));  // size clamped to 100
    }

    @Test
    void blankQueryBecomesNull() {
        when(repository.search(eq(true), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of()));
        controller.list(0, 20, true, "   ");
        verify(repository).search(eq(true), eq(PageRequest.of(0, 20)));
    }

    @Test
    void detailThrowsOnUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.detail(id));
    }
}
