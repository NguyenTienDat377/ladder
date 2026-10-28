-- V4: full-text and fuzzy title search for videos.
--
-- Two mechanisms, because they solve different halves of search:
--   1. tsvector + GIN (videos_search_idx): token search across title and
--      description. Title tokens carry weight 'A' and description tokens
--      weight 'B', so ts_rank scores a title match ahead of a description
--      match. The 'simple' dictionary lowercases and splits on punctuation
--      without English-only stemming, which would mangle Vietnamese names
--      and technical terms (ffmpeg, 1080p, hls).
--   2. pg_trgm + GIN (videos_title_trgm_idx): character 3-gram index on
--      title, catching typos ("bitrat" -> "bitrate") and partial words that
--      whole-token tsvector search misses.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE videos ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B')
    ) STORED;

CREATE INDEX videos_search_idx ON videos USING gin (search_vector);
CREATE INDEX videos_title_trgm_idx ON videos USING gin (title gin_trgm_ops);
