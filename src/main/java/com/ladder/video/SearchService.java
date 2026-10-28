package com.ladder.video;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.video.dto.SearchPage;
import com.ladder.video.dto.VideoPage.Item;

/**
 * Public video search combining Postgres full-text search (tsvector) and
 * trigram fuzzy matching (pg_trgm).
 *
 * Why both:
 * - {@code search_vector @@ websearch_to_tsquery('simple', q)} matches whole
 *   words across title (weight 'A') and description (weight 'B'), and accepts
 *   natural search syntax ("quoted phrases", OR, -excluded) without ever
 *   throwing a syntax error on raw user input.
 * - {@code v.title % q} and {@code v.title %> q} use the trigram GIN index on
 *   {@code title} to catch typos ("bitrat" -> "bitrate") and partial words
 *   that whole-token matching misses.
 *
 * Results are ordered by relevance ({@code ts_rank + word_similarity}) first,
 * with UUIDv7 {@code video_id DESC} breaking ties in favour of newer uploads.
 */
@Service
public class SearchService {
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_OFFSET = 1000;

    private final JdbcTemplate jdbc;

    public SearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SearchPage search(String query, int offset, int limit) {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid offset");
        }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return new SearchPage(List.of(), null);
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query too long");
        }

        int size = CatalogService.clamp(limit);
        // Every branch of the OR uses an index operator (@@ on videos_search_idx,
        // % and %> on videos_title_trgm_idx) so Postgres can combine them in a
        // single BitmapOr scan.
        List<Item> rows = jdbc.query("""
                SELECT v.video_id, v.title, v.created_at, v.duration_secs, v.view_count,
                       v.status, v.visibility, v.thumbnail_path,
                       c.channel_name, c.channel_handle
                FROM videos v
                JOIN channels c ON c.channel_id = v.channel_id,
                     websearch_to_tsquery('simple', ?) AS tsq
                WHERE v.status = 'READY' AND v.visibility = 'PUBLIC'
                  AND (v.search_vector @@ tsq OR v.title % ? OR v.title %> ?)
                ORDER BY ts_rank(v.search_vector, tsq) + word_similarity(?, v.title) DESC,
                         v.video_id DESC
                LIMIT ? OFFSET ?
                """,
                CatalogService.ITEM, q, q, q, q, size + 1, offset);

        if (rows.size() <= size) {
            return new SearchPage(rows, null);
        }
        List<Item> items = rows.subList(0, size);
        int next = offset + size;
        return new SearchPage(items, next <= MAX_OFFSET ? next : null);
    }
}
