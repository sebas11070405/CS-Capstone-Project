-- Every imported text file (and each batch of generated sentences fed back in).
CREATE TABLE source_files
(
    file_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    word_count   INT          NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Each distinct word and how often it appears overall, at the start, and at the end of a sentence.
CREATE TABLE words
(
    word_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word         VARCHAR(100) NOT NULL UNIQUE,
    total_count  INT          NOT NULL DEFAULT 0,
    start_count  INT          NOT NULL DEFAULT 0,
    end_count    INT          NOT NULL DEFAULT 0
);

-- word -> next_word counts. Used by Weighted Random, Most Likely, auto-complete,
-- and as the fallback for Trigram Backoff.
CREATE TABLE bigrams
(
    word_id        INT NOT NULL REFERENCES words (word_id),
    next_word_id   INT NOT NULL REFERENCES words (word_id),
    occurrences    INT NOT NULL DEFAULT 0,
    user_selected  INT NOT NULL DEFAULT 0,  -- times picked via auto-complete
    PRIMARY KEY (word_id, next_word_id)
);

-- (word1, word2) -> next_word counts. Used by Trigram Backoff.
CREATE TABLE trigrams
(
    word1_id      INT NOT NULL REFERENCES words (word_id),
    word2_id      INT NOT NULL REFERENCES words (word_id),
    next_word_id  INT NOT NULL REFERENCES words (word_id),
    occurrences   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (word1_id, word2_id, next_word_id)
);

-- History of generated sentences, kept in the cloud for the duplicates report.
CREATE TABLE generated_sentences
(
    sentence_id    INT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sentence_text  TEXT        NOT NULL,
    start_word_id  INT         NOT NULL REFERENCES words (word_id),
    generated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Non-unique: duplicates are kept so we can report them.
CREATE INDEX ix_gen_text ON generated_sentences (sentence_text);
