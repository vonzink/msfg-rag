package com.msfg.rag.repository;

import com.msfg.rag.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    @Modifying
    void deleteByDocumentId(UUID documentId);

    /**
     * Vector similarity search (cosine) over chunks of active, currently
     * effective documents. The embedding is passed as a pgvector literal
     * string, e.g. "[0.12,-0.34,...]".
     */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.effective_date                                AS effectiveDate,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
            FROM mortgage_document_chunks c
            JOIN mortgage_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByVector(@Param("embedding") String embedding,
                                           @Param("limit") int limit);

    /**
     * Full-text keyword search using websearch syntax (handles quoted phrases,
     * OR, minus). ts_rank_cd is normalized by document length (flag 32) so the
     * score lands in 0..1 territory, comparable to cosine similarity.
     */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.effective_date                                AS effectiveDate,
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', :query), 32) AS score
            FROM mortgage_document_chunks c
            JOIN mortgage_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND c.content_tsv @@ websearch_to_tsquery('english', :query)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByKeyword(@Param("query") String query,
                                            @Param("limit") int limit);
}
