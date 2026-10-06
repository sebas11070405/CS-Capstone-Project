/*
Prompt used:

I'm building a Java/JavaFX app for a sentence builder/sentence completer application. Please write a
MySQL database schema for it as a single .sql file.

Requirements:
- Track each word with its total count, sentence-start count, and sentence-end count
- Track every word that follows each word, and how many times
- Track imported files: name, word count, and import date/time
- Track generated sentences so I can find duplicates
- Support auto-complete, which increments a count when the user picks a next word
Please also:
- Use foreign keys, appropriate indexes, and unique constraints
- Add comments explaining each table and any design decisions
- Include the example queries I'll need (importing words, auto-complete, weighted-random next word)
- Suggest any extra tables or columns that would be useful for reports
*/

CREATE DATABASE IF NOT EXISTS sentence_builder
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE sentence_builder;

CREATE TABLE source_files
(
    file_id         INT UNSIGNED    NOT NULL AUTO_INCREMENT,
    file_name       VARCHAR(255)    NOT NULL,
    file_path       VARCHAR(1024)   NULL,
    file_hash       CHAR(64)        NULL,            -- SHA-256; blocks accidental re-imports
    source_type     ENUM('FILE','GENERATED') NOT NULL DEFAULT 'FILE',
    word_count      INT UNSIGNED    NOT NULL DEFAULT 0,   -- total word tokens
    sentence_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    imported_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uq_source_files_hash (file_hash)
) ENGINE=InnoDB;

CREATE TABLE words
(
    word_id               INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    word                  VARCHAR(100)  NOT NULL,
    total_count           INT UNSIGNED  NOT NULL DEFAULT 0,  -- all occurrences
    sentence_start_count  INT UNSIGNED  NOT NULL DEFAULT 0,  -- times it began a sentence
    sentence_end_count    INT UNSIGNED  NOT NULL DEFAULT 0,  -- times it ended a sentence
    user_selected_count   INT UNSIGNED  NOT NULL DEFAULT 0,  -- times picked/typed via auto-complete
    first_seen_file_id    INT UNSIGNED  NULL,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (word_id),
    UNIQUE KEY uq_words_word (word),
    KEY ix_words_total_count (total_count),
    CONSTRAINT fk_words_first_file
        FOREIGN KEY (first_seen_file_id) REFERENCES source_files (file_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;


CREATE TABLE word_follows
(
    word_id         INT UNSIGNED  NOT NULL,
    next_word_id    INT UNSIGNED  NOT NULL,
    occurrences     INT UNSIGNED  NOT NULL DEFAULT 0,
    user_selected   INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (word_id, next_word_id),
    KEY ix_follows_next (next_word_id),
    KEY ix_follows_rank (word_id, occurrences DESC),   -- "top N next words"
    CONSTRAINT fk_follows_word
        FOREIGN KEY (word_id)      REFERENCES words (word_id),
    CONSTRAINT fk_follows_next
        FOREIGN KEY (next_word_id) REFERENCES words (word_id)
) ENGINE=InnoDB;


CREATE TABLE file_word_counts
(
    file_id     INT UNSIGNED  NOT NULL,
    word_id     INT UNSIGNED  NOT NULL,
    occurrences INT UNSIGNED  NOT NULL DEFAULT 0,
    PRIMARY KEY (file_id, word_id),
    KEY ix_fwc_word (word_id),
    CONSTRAINT fk_fwc_file FOREIGN KEY (file_id) REFERENCES source_files (file_id),
    CONSTRAINT fk_fwc_word FOREIGN KEY (word_id) REFERENCES words (word_id)
) ENGINE=InnoDB;


CREATE TABLE algorithms
(
    algorithm_id  TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)      NOT NULL,   -- e.g. 'WEIGHTED_RANDOM', 'MOST_LIKELY'
    description   VARCHAR(255)     NULL,
    PRIMARY KEY (algorithm_id),
    UNIQUE KEY uq_algorithms_name (name)
) ENGINE=InnoDB;

CREATE TABLE generated_sentences
(
    sentence_id        INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    sentence_text      VARCHAR(2000) NOT NULL,
    sentence_hash      CHAR(64)      NOT NULL,   -- SHA-256 of normalized text; duplicate detection
    start_word_id      INT UNSIGNED  NOT NULL,
    algorithm_id       TINYINT UNSIGNED NOT NULL,
    word_count         SMALLINT UNSIGNED NOT NULL,
    generated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    imported_file_id   INT UNSIGNED  NULL,       -- set once fed back in as source text
    PRIMARY KEY (sentence_id),
    KEY ix_gen_hash (sentence_hash),             -- non-unique: duplicates are kept on purpose
    KEY ix_gen_algorithm (algorithm_id),
    KEY ix_gen_start (start_word_id),
    CONSTRAINT fk_gen_start     FOREIGN KEY (start_word_id)    REFERENCES words (word_id),
    CONSTRAINT fk_gen_algorithm FOREIGN KEY (algorithm_id)     REFERENCES algorithms (algorithm_id),
    CONSTRAINT fk_gen_file      FOREIGN KEY (imported_file_id) REFERENCES source_files (file_id)
                                ON DELETE SET NULL
) ENGINE=InnoDB;


INSERT INTO algorithms (name, description) VALUES
    ('WEIGHTED_RANDOM', 'Pick next word at random, weighted by follow frequency'),
    ('MOST_LIKELY',     'Always pick the most frequent follower'),
    ('UNIFORM_RANDOM',  'Pick uniformly among all known followers');

CREATE VIEW v_word_report AS
SELECT w.word_id,
       w.word,
       w.total_count,
       w.sentence_start_count,
       w.sentence_end_count,
       w.user_selected_count,
       (SELECT COUNT(*) FROM word_follows f WHERE f.word_id = w.word_id) AS distinct_followers
FROM words w;

CREATE VIEW v_duplicate_sentences AS
SELECT sentence_hash,
       MIN(sentence_text) AS sentence_text,
       COUNT(*)           AS times_generated
FROM generated_sentences
GROUP BY sentence_hash
HAVING COUNT(*) > 1;

