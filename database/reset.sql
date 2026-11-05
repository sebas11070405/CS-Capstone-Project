/*
DESTRUCTIVE: deletes ALL data in every table of the shared database and
restarts IDs at 1. The tables themselves are kept. Run it only when the whole
team wants a clean start, then re-import the texts.
*/

TRUNCATE generated_sentences, trigrams, bigrams, words, source_files
    RESTART IDENTITY CASCADE;
