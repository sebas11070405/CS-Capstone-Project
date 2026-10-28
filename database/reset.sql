-- Deletes ALL data in every table and restarts IDs at 1. Run only for a clean start.
TRUNCATE generated_sentences, trigrams, bigrams, words, source_files
    RESTART IDENTITY CASCADE;
