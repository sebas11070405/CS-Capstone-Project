/*
Creates every table and index the app uses: source_files for imported files
and generated batches, words for per-word counts, bigrams and trigrams for
which words follow which, and generated_sentences for the duplicates report.
It has already been run on the shared Supabase database. Running it again
fails because the tables exist. Use reset.sql to clear the data instead.

AI-assisted: Claude Sonnet 5.5

Prompt:
I'm the database architect on a Java/JavaFX sentence-generator project using
PostgreSQL on Supabase. Review the attached assignment and my schema.sql below.
Fix problems, add needed indexes and integrity constraints, keep existing names,
and return the full updated schema plus a grouped summary of changes and any
open design decisions.
*/

-- Every imported text file (and each batch of generated sentences fed back in).
CREATE TABLE source_files
(
    file_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    source_type  VARCHAR(20)  NOT NULL DEFAULT 'file'
                              CHECK (source_type IN ('file', 'generated')),
    word_count   INT          NOT NULL DEFAULT 0 CHECK (word_count >= 0),
    imported_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    content_hash CHAR(64),                          -- SHA-256 of the tokenized words
    CHECK (source_type <> 'file' OR content_hash IS NOT NULL)
);

-- Generated batches may repeat; the same file may not.
CREATE UNIQUE INDEX ux_source_files_hash
    ON source_files (content_hash)
    WHERE source_type = 'file';

-- Each distinct word and how often it appears overall, at the start, and at the end of a sentence.
CREATE TABLE words
(
    word_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word         VARCHAR(100) NOT NULL UNIQUE CHECK (word = lower(word)),
    total_count  INT          NOT NULL DEFAULT 0 CHECK (total_count >= 0),
    start_count  INT          NOT NULL DEFAULT 0 CHECK (start_count >= 0),
    end_count    INT          NOT NULL DEFAULT 0 CHECK (end_count >= 0),
    CHECK (start_count <= total_count AND end_count <= total_count)
);

-- word -> next_word counts. Used by Weighted Random, Most Likely, auto-complete,
-- and as the fallback for Trigram Backoff.
CREATE TABLE bigrams
(
    word_id        INT NOT NULL REFERENCES words (word_id),
    next_word_id   INT NOT NULL REFERENCES words (word_id),
    occurrences    INT NOT NULL DEFAULT 0 CHECK (occurrences >= 0),
    user_selected  INT NOT NULL DEFAULT 0 CHECK (user_selected >= 0),  -- times picked via auto-complete
    PRIMARY KEY (word_id, next_word_id)
);

-- Postgres does not index foreign keys automatically.
CREATE INDEX ix_bigrams_next   ON bigrams (next_word_id);                   -- "what precedes X"
CREATE INDEX ix_bigrams_follow ON bigrams (word_id, occurrences DESC);      -- "most likely next word"

-- (word1, word2) -> next_word counts. Used by Trigram Backoff.
CREATE TABLE trigrams
(
    word1_id      INT NOT NULL REFERENCES words (word_id),
    word2_id      INT NOT NULL REFERENCES words (word_id),
    next_word_id  INT NOT NULL REFERENCES words (word_id),
    occurrences   INT NOT NULL DEFAULT 0 CHECK (occurrences >= 0),
    PRIMARY KEY (word1_id, word2_id, next_word_id)
);

CREATE INDEX ix_trigrams_word2  ON trigrams (word2_id);
CREATE INDEX ix_trigrams_next   ON trigrams (next_word_id);

-- History of generated sentences, kept in the cloud for the duplicates report.
CREATE TABLE generated_sentences
(
    sentence_id    INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sentence_text  TEXT         NOT NULL,
    start_word_id  INT          NOT NULL REFERENCES words (word_id),
    algorithm      VARCHAR(50),                    -- which generator produced it
    generated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Non-unique: duplicates are kept so we can report them.
-- Indexed on a hash so very long sentences can't exceed the btree entry size limit.
CREATE INDEX ix_gen_text  ON generated_sentences (md5(sentence_text));
CREATE INDEX ix_gen_start ON generated_sentences (start_word_id);
