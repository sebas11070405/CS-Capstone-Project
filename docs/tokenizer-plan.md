# Tokenizer Plan (Work Package 3.1)

**Owner:** Jerry Chen · **Branch:** `feature/import-tokenizer` · **Due:** Week 3 (overdue); feeds M3, text import end-to-end, Oct 18

## Purpose

The tokenizer turns raw text into sentences, where each sentence is an ordered list of normalized words. Everything the database needs comes from that shape:

- word counts, sentence-start counts and sentence-end counts (`words`)
- follow-word pairs (`word_follows`)
- word triples, if the team adopts Trigram Backoff

The tokenizer does no database work. Counting and writing belong to the import module (3.2).

## Input Text

- **Book:** *The Adventures of Sherlock Holmes* by Arthur Conan Doyle (Project Gutenberg, plain text, about 105,000 words).
- **Location:** `data/the-adventures-of-sherlock-holmes.txt`. At about 600 KB this goes against the plan's rule that large text files go through Teams, so the team should confirm it.
- **Header and footer:** the Project Gutenberg text before `*** START OF` and after `*** END OF` is stripped by the import step (3.2), not the tokenizer (risk R11).

## Word Rules

A word is a run of Latin letters, which may contain apostrophes or a single hyphen between letters. Everything else is a separator or a sentence boundary.

| Case | Rule | Example |
|---|---|---|
| Basic | Run of Latin letters, normalized (see below) | `The` → `the` |
| Contractions and possessives | Internal apostrophe kept | `don't`, `holmes's` |
| Quote marks | Leading and trailing apostrophes stripped | `'hello'` → `hello`, `'tis` → `tis` |
| Hyphenated words | One hyphen between letters kept; Unicode hyphens (U+2010, U+2011) count as `-` | `well-known` |
| Dashes | `--`, `—` and `–` separate words | `word--word` → `word`, `word` |
| Gutenberg italics | Underscores stripped | `_very_` → `very` |
| Numbers | Skipped; they don't end a sentence or break follow pairs | `in 1891 he` → `in`, `he` |
| Letters mixed with digits | Skipped as a number, including through apostrophes and hyphens | `221B`, `1st`, `’60’s`, `4-wheeled` |
| Abbreviations | Stored without the period | `Mr.` → `mr` |
| Non-Latin letters | Treated as separators | Greek, Cyrillic |
| Too long | Skipped if longer than 100 characters after normalization (`VARCHAR(100)`) | |

## Normalization (Matches the Schema)

`words.word` is unique under `utf8mb4_unicode_ci`, which already treats some spellings as the same word. The tokenizer applies the same equivalences up front, so the import module's in-memory counts always agree with the database's unique key.

**Steps, in order:**

1. Split accented letters into base letter plus accent (Unicode NFKD).
2. Remove the accents (combining marks).
3. Lowercase with `Locale.ROOT`, so the Turkish-locale `i` problem can't occur.
4. Change `ß` to `ss` and `œ` to `oe`.
5. Change curly `’` and `ʼ` to straight `'`, and Unicode hyphens to `-`.

| MySQL treats as one word | Tokenizer output |
|---|---|
| `The` / `the` | `the` |
| `café` / `cafe`, `naïve` / `naive`, `Élan` / `elan` | `cafe`, `naive`, `elan` |
| `straße` / `strasse` | `strasse` |
| `œuvre` / `oeuvre` | `oeuvre` |
| `ﬁne` (ligature) / `fine` | `fine` |

| MySQL keeps separate | Tokenizer output |
|---|---|
| `æther` / `aether` | `æther` |
| `Øre` / `ore` | `øre` |
| `łodz` / `lodz` | `łodz` |

**Verification (Oct 15, MySQL 8.4.11):**

- **Latin letters:** all 716 Latin letters were run through these steps and compared with MySQL under `utf8mb4_unicode_ci`. There were zero collisions: the steps never produce two different spellings that MySQL treats as one word.
- **Harmless differences:** 17 recent letters, such as capital `ẞ`, are merged by the tokenizer but kept separate by MySQL. The database only receives the merged form, so this causes no errors.
- **Greek and Cyrillic:** these failed. Final `ς` and `σ` came out as two words that MySQL treats as one, and Russian `й` was wrongly merged into `и`. That is why words are limited to Latin letters.

## Sentence Boundaries

- **Terminators:** `.`, `!`, `?` and `...` end a sentence, including when closing quotes or brackets follow.
- **No end after an abbreviation:** `Mr`, `Mrs`, `Ms`, `Dr`, `St`, `Messrs`, `Mme`, `Mlle`, `Prof`, `Rev`, `Capt`, `Col`, `Gen`, `Lt`, `Sgt`, `Jr`, `Sr`, `Co`, `vs` and `Mt`. `etc` and `No` are left out because they often do end a sentence.
- **No end after a single-letter initial:** `J. Watson`.
- **No end before a lowercase word:** `"Oh! my dear"`. This also catches abbreviations missing from the list (risk R3).
- **Blank lines:** a blank line always ends a sentence, so headings like `ADVENTURE I. A SCANDAL IN BOHEMIA` don't run into the next paragraph.
- **Single newlines:** these count as plain spaces, since Gutenberg hard-wraps lines mid-sentence.

**Known limitation:** a quoted exclamation or question followed by `I` is split, because `I` is always capitalized. `“Seven!” I answered.` becomes `seven` and `i answered`. Telling this apart from a real sentence break would need grammar knowledge, so it is accepted.

## Results on the Book

Body text only (between the Gutenberg markers), checked Oct 15:

| Measure | Result |
|---|---|
| Words | 104,529 |
| Distinct words | 8,226 |
| Sentences | 6,664 |
| Time to tokenize | under 0.1 s |
| Words outside a–z, `'` and `-` | `encyclopædia`, `encyclopædias` (correct: MySQL keeps `æ` distinct) |

- **Most common words:** `the`, `and`, `i`, `to`, `of`.
- **Most common sentence-final words:** `it`, `you`, `me`, `him`, `holmes`. No abbreviation shows up as a sentence ending.
- **Stray single letters:** these all come from real text: `U.S.A.`, the initials in `K. K. K.`, and Gutenberg's `8_s_.` for shillings.
- **Table of contents:** the Roman numerals (`ii`, `iii`, ...) are counted as words. Whether to strip the contents page is a question for the import step.

## Code

| File | Contents |
|---|---|
| `importer/Tokenizer.java` | Reads a `Reader` and returns sentences one at a time, so memory stays flat on a full novel and the import can report progress (3.3). A `String` version serves the self-feeding loop (4.5). |
| `Tokenizer.normalizeWord(String)` | Public and static, so auto-complete (4.6) normalizes typed words exactly like the importer. |
| Tests (JUnit 5) | One test per word rule, normalization row and boundary rule, plus empty input, a missing final terminator, repeated terminators (`?!`) and a hand-checked excerpt from the book. |

- **Package:** `importer`, at the repo root like `generator/`. It moves under `src/main/java` when the Maven setup (6.1) lands.
- **Running tests:** until then, the tests run on JDK 17 with the JUnit console launcher jar (`junit-platform-console-standalone-1.11.4.jar` from Maven Central), from the repo root:

  ```
  javac -encoding UTF-8 -d out -cp junit-platform-console-standalone-1.11.4.jar importer/Tokenizer.java test/importer/TokenizerTest.java
  java -jar junit-platform-console-standalone-1.11.4.jar execute -cp out --select-package importer
  ```
- **AI attribution:** each AI-written Java file starts with a comment block containing the prompt used, the same convention as `schema.sql`.

## Open Items for the Team

- **Committing the book:** confirm the text file can go in the repository rather than Teams.
- **Trigram Backoff:** if adopted, the schema needs a word-triple table (Sebastian) and the import module fills it. The tokenizer already supports this.
- **Generator interface:** `generateSentence()` takes no starting word, but the spec requires generating from a supplied word.

## Prompts Used

This plan was written with AI assistance (Claude Code). These are the prompts, verbatim:

1. "lets plan out 3.1. professor says code can be done using ai, as long as the prompt used is given/attached"
2. Proposal shared with the team:

   > We should first decide on the book to tokenize, I say: The Adventures of Sherlock Holmes by Arthur Conan Doyle. 105,000 words, enough for interesting counts. The spec asks for fairly large files and we could drop it into a directory in the project for tokenization.
   >
   > In terms of development:
   > We should first start with a tokenizer so we can chop up the text. But we need to have a shared understanding of what a word is though.
   >
   > In my opinion, the word should be defined as a run of letters which may contain apostrophe or a single hyphen between letters, and everything else in the text is a separator or a sentence boundary. Then, we should normalize the letters to be lowercase in the database. We still need to think about rules on numbers and abbreviations (Mr., Mrs., Dr).
   >
   > Then we need to build the generators to predict the next word.
   >
   > I see in the SQL schema by Sebastian includes:
   > Weighted Random: Pick next word at random, weighted by follow frequency
   > Most Likely: Always pick the most frequent follower
   > Uniform Random: Pick uniformly among all known followers
   >
   > I think we should drop Uniform Random and replace it with Trigram Backoff which predicts the next word based on the previous two words, and fall back to one word when that pair hasn't been seen.
   >
   > Also I think each generator could be split up between us and could be completed parallelly instead of in series.
   >
   > Then from there, the UI is only left. We can decide on how the UI could look once we have all the logical components finished. We could probably use Figma to mock something up for a general understanding and fulfill all the features the spec requires us to have.
   >
   > I know this is sent on short notice since our advisor meeting is today, [personal note omitted]. Let me know what you guys think, and how the work should be split up.

3. "finalize the tokenizer plan, for lowercasing and accents, make them match the schema"
4. "add the plan to docs or something"
5. "okay implement the plan" (the implementation updates above came from this prompt)
