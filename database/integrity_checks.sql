/*
AI Assistance: Claude Sonnet 5.5

prompt used: Create `database/integrity_checks.sql` as a read-only PostgreSQL script that checks whether the counts in 
`words`, `bigrams`, `trigrams`, and `source_files` are consistent with how `CorpusStats` processes sentences.
Make sure the script verifies that a word's total count minus its end count matches the number of times it appears in 
bigrams, and that its total count minus its start count matches the number of bigrams ending with that word. It should 
also check that the total number of sentence starts matches the total number of sentence ends, every trigram has both of 
its corresponding bigrams, trigram counts never exceed their corresponding bigram counts, and the total word counts match 
the `word_count` values in `source_files`. The script should return one row for each check, showing its severity, the number 
of violations, and up to five examples of records that failed. Even if a check passes, it should still return a row showing 0 
violations. Add a header explaining what each check does, and make sure to follow the commenting guidelines in `docs/comment-style.md`.
*/


/*
Checks that the word, bigram, and trigram counts still agree with each other, and prints one row per
check. It only reads the data, so it is safe to run on the shared database at any time, such as after
an import or after editing counts by hand. Run it in the Supabase SQL editor or psql.

A check passes when violations is 0. The examples column lists up to five offenders so you can see
what to fix. The checks hold when every count came from the importer, because it counts one sentence
at a time and never pairs words across a sentence boundary:

  - Every use of a word except the last one in its sentence starts exactly one bigram, so
    total_count - end_count equals the sum of that word's bigram occurrences. The same goes for the
    word at the end of a bigram and total_count - start_count.
  - Every sentence has one first word and one last word, so the start and end counts add up to the
    same number.
  - A trigram (a, b, c) needs the bigrams (a, b) and (b, c). It can never be seen more often than
    they were, though it can be seen less, when the pair sits at the end of a sentence.

Severity 'error' means the counts are wrong. Severity 'warning' means they differ in a way hand-made
changes can cause on purpose: editing a word's counts in the app, or adding a word through
auto-complete, changes words but not source_files. Decide whether those edits should be allowed to
break the first three checks, because the generators weight their choices by these counts.

Needs the tables from database/schema.sql.
*/

WITH
-- One row per check, so a check with no offenders still shows up as a pass
checks (check_name, severity) AS (
    VALUES ('follower counts match bigrams',           'error'),
           ('predecessor counts match bigrams',        'error'),
           ('sentence starts match sentence ends',     'error'),
           ('trigrams have both of their bigrams',     'error'),
           ('trigram counts fit within bigram counts', 'error'),
           ('word totals match imported sources',      'warning')
),

-- How many bigrams begin with each word, and how many end with it
follower_sums AS (
    SELECT word_id, sum(occurrences) AS total FROM bigrams GROUP BY word_id
),
predecessor_sums AS (
    SELECT next_word_id AS word_id, sum(occurrences) AS total FROM bigrams GROUP BY next_word_id
),

-- How often each pair appears as the first two words, and as the last two words, of a trigram
first_pair_sums AS (
    SELECT word1_id AS word_id, word2_id AS next_word_id, sum(occurrences) AS total
    FROM trigrams GROUP BY word1_id, word2_id
),
second_pair_sums AS (
    SELECT word2_id AS word_id, next_word_id, sum(occurrences) AS total
    FROM trigrams GROUP BY word2_id, next_word_id
),

-- Every row that breaks a check, with a short description of what is wrong with it
offenders AS (
    -- total_count - end_count is how many times the word had something after it
    SELECT 'follower counts match bigrams' AS check_name,
           w.word || ' (expected ' || (w.total_count - w.end_count)
                  || ', bigrams have ' || coalesce(f.total, 0) || ')' AS detail
    FROM words w
    LEFT JOIN follower_sums f ON f.word_id = w.word_id
    WHERE w.total_count - w.end_count <> coalesce(f.total, 0)

    UNION ALL

    -- total_count - start_count is how many times the word had something before it
    SELECT 'predecessor counts match bigrams',
           w.word || ' (expected ' || (w.total_count - w.start_count)
                  || ', bigrams have ' || coalesce(p.total, 0) || ')'
    FROM words w
    LEFT JOIN predecessor_sums p ON p.word_id = w.word_id
    WHERE w.total_count - w.start_count <> coalesce(p.total, 0)

    UNION ALL

    -- Each sentence adds one start and one end, so the two grand totals must match
    SELECT 'sentence starts match sentence ends',
           'starts ' || starts || ', ends ' || ends
    FROM (SELECT sum(start_count) AS starts, sum(end_count) AS ends FROM words) AS totals
    WHERE starts <> ends

    UNION ALL

    -- A trigram a-b-c is only possible if a-b and b-c were both seen as bigrams
    SELECT 'trigrams have both of their bigrams',
           w1.word || ' ' || w2.word || ' ' || w3.word
    FROM trigrams t
    JOIN words w1 ON w1.word_id = t.word1_id
    JOIN words w2 ON w2.word_id = t.word2_id
    JOIN words w3 ON w3.word_id = t.next_word_id
    WHERE NOT EXISTS (SELECT 1 FROM bigrams b
                      WHERE b.word_id = t.word1_id AND b.next_word_id = t.word2_id)
       OR NOT EXISTS (SELECT 1 FROM bigrams b
                      WHERE b.word_id = t.word2_id AND b.next_word_id = t.next_word_id)

    UNION ALL

    -- The first two words of all trigrams can't add up to more than that pair's bigram count
    SELECT 'trigram counts fit within bigram counts',
           w1.word || ' ' || w2.word || ' (trigrams ' || f.total || ', bigram ' || b.occurrences || ')'
    FROM first_pair_sums f
    JOIN bigrams b ON b.word_id = f.word_id AND b.next_word_id = f.next_word_id
    JOIN words w1 ON w1.word_id = f.word_id
    JOIN words w2 ON w2.word_id = f.next_word_id
    WHERE f.total > b.occurrences

    UNION ALL

    -- Same rule for the last two words of all trigrams
    SELECT 'trigram counts fit within bigram counts',
           w1.word || ' ' || w2.word || ' (trigrams ' || s.total || ', bigram ' || b.occurrences || ')'
    FROM second_pair_sums s
    JOIN bigrams b ON b.word_id = s.word_id AND b.next_word_id = s.next_word_id
    JOIN words w1 ON w1.word_id = s.word_id
    JOIN words w2 ON w2.word_id = s.next_word_id
    WHERE s.total > b.occurrences

    UNION ALL

    -- Every file and generated batch records how many words it added, and the words table counted them
    SELECT 'word totals match imported sources',
           'words total ' || word_total || ', sources total ' || source_total
    FROM (SELECT (SELECT sum(total_count) FROM words) AS word_total,
                 (SELECT sum(word_count) FROM source_files) AS source_total) AS totals
    WHERE word_total <> source_total
),

-- Numbered so the report can show just the first five offenders of each check
ranked AS (
    SELECT check_name, detail,
           row_number() OVER (PARTITION BY check_name ORDER BY detail) AS position
    FROM offenders
)

SELECT c.check_name,
       c.severity,
       count(r.detail) AS violations,
       coalesce(string_agg(r.detail, '; ' ORDER BY r.position) FILTER (WHERE r.position <= 5), '') AS examples
FROM checks c
LEFT JOIN ranked r ON r.check_name = c.check_name
GROUP BY c.check_name, c.severity
ORDER BY c.severity, c.check_name;