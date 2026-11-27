# Sentence Builder

A small language model you can see inside. Sentence Builder reads plain-text books, counts which
words follow which, and uses those counts to write new sentences, suggest the next word as you
type, and show you exactly why it made each choice. It's a Java 21 / JavaFX desktop app backed by
a shared PostgreSQL database on Supabase.

Built for CS 4485 Senior Design (Prof. John Cole). The full assignment is in
[`docs/project-description.md`](docs/project-description.md).

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Setup](#setup)
- [Database setup and maintenance](#database-setup-and-maintenance)
- [User guide](#user-guide)
- [How it works](#how-it-works)
- [Project layout](#project-layout)
- [Building and testing](#building-and-testing)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [References](#references)

## Features

- **Import** any `.txt` file. Words, sentence starts and ends, word pairs (bigrams), and word
  triples (trigrams) are counted and saved in one transaction, with a progress bar. Importing the
  same text twice is caught and refused.
- **Generate** a sentence from a starting word with one of three algorithms: weighted random
  (Markov chain), most frequent next word, or trigram backoff.
- **Batch generate** sentences from random starting words, then feed them back into the corpus
  with one click.
- **Auto-complete** suggests the next word only after you finish a word with a space, comma, or
  period. It learns from what you pick and adds new words you type.
- **Look up and edit** any word: its counts, the words that come before and after it, and a form
  to correct its counts.
- **Word list** of every stored word, sortable by any column, filterable, and paged.
- **History** of every generated sentence, with a report of sentences generated more than once.

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| JDK | **21** | Required. See the note below. |
| Gradle | 8.14 (bundled) | Use `./gradlew`. No separate install needed. |
| Database | PostgreSQL on Supabase | The team shares one Supabase project. |
| `psql` | any | Optional. Only needed if you run SQL from the terminal instead of the Supabase SQL editor. |

> [!IMPORTANT]
> **Use Java 21.** The build compiles against a Java 21 toolchain, and the bundled Gradle (8.14)
> can't run on much newer JDKs. If your default `java` is newer (for example 25 or 27), Gradle
> fails right away with an unhelpful message such as `What went wrong: 27`. Point `JAVA_HOME` at
> a JDK 21 before running `./gradlew`:
>
> ```bash
> # macOS
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)
>
> # Linux (adjust the path to where your JDK 21 is installed)
> export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
>
> # Windows (PowerShell)
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot"
> ```
>
> Add the line to your shell profile (`~/.zshrc`, `~/.bashrc`) so it sticks. In IntelliJ IDEA,
> also set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM** to
> 21. Any JDK 21 distribution works (Temurin, Microsoft, Oracle, and so on).

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/sebas11070405/CS-Capstone-Project.git
cd CS-Capstone-Project
```

### 2. Add your database credentials

Copy the example environment file:

```bash
cp .env.example .env
```

Fill in `.env` with the Supabase **Session pooler** connection details (Supabase dashboard →
**Connect** → **Session pooler**):

```dotenv
DB_URL="jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require"
DB_USER="postgres.<project-ref>"
DB_PASSWORD="<database password>"
```

`.env` is git-ignored, so never commit it. Real environment variables with the same names work
too and are used when a key is missing from `.env` (that's how CI passes them in).

### 3. Create the schema (new databases only)

The team's shared Supabase database **already has the schema**, so if you're using it, skip to
step 4. For a fresh database of your own, see [Creating the schema](#creating-the-schema).

### 4. Run the app

```bash
./gradlew run
```

The **Sentence Builder** window opens on the Import tab. Every tab opens even without a database
connection, and its status line tells you which `.env` key is missing or why the connection failed.

## Database setup and maintenance

All SQL lives in [`database/`](database/):

| File | What it does | Safe on the shared database? |
|------|--------------|------------------------------|
| [`schema.sql`](database/schema.sql) | Creates every table and index. | Already applied. Running it again fails because the tables exist. |
| [`integrity_checks.sql`](database/integrity_checks.sql) | Read-only report that checks the counts agree with each other. | Yes, any time. |
| [`reset.sql`](database/reset.sql) | **Deletes all data** in every table and restarts IDs at 1. Keeps the tables. | Only when the whole team agrees. |

You can run any of these in either the Supabase SQL editor or `psql`.

### Option A: Supabase SQL editor (no install)

1. Open the project in the [Supabase dashboard](https://supabase.com/dashboard).
2. Go to **SQL Editor** → **New query**.
3. Paste the full contents of the `.sql` file and click **Run**.
4. Results (for example, the integrity report) appear in the grid below the editor.

### Option B: `psql`

`psql` needs a normal Postgres connection string, not the JDBC `DB_URL` from `.env`. Use the
same Session pooler host, user, and password:

```bash
psql "postgresql://postgres.<project-ref>:<password>@aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require" \
     -f database/schema.sql
```

Swap in `database/integrity_checks.sql` or `database/reset.sql` to run the other scripts.

### Creating the schema

Run `database/schema.sql` once on an empty database with either option above. It creates:

| Table | Holds |
|-------|-------|
| `source_files` | Every imported file and every generated batch fed back in: name, type (`file` or `generated`), word count, import time, and a SHA-256 content hash used to refuse duplicate imports. |
| `words` | Each distinct lowercase word with its total count, how often it starts a sentence, and how often it ends one. |
| `bigrams` | How often each word is followed by each other word (`occurrences`), plus how often a user picked that pair in auto-complete (`user_selected`). |
| `trigrams` | How often each pair of words is followed by a third word. |
| `generated_sentences` | Every generated sentence, its starting word, the algorithm that made it, and when. Duplicates are kept on purpose for the History report. |

An ER diagram is in [`database/diagram.md`](database/diagram.md). `schema.sql` is the source of
truth if the two ever differ.

### Checking data integrity

`integrity_checks.sql` only reads, so it's safe to run on the shared database at any time, for
example after an import or after editing counts on the Words tab. It returns one row per check:

| Check | Severity | What it means |
|-------|----------|---------------|
| follower counts match bigrams | error | `total_count - end_count` for each word equals the bigrams starting with it. |
| predecessor counts match bigrams | error | `total_count - start_count` equals the bigrams ending with it. |
| sentence starts match sentence ends | error | Every sentence has exactly one first and one last word. |
| trigrams have both of their bigrams | error | Trigram `a b c` requires bigrams `a b` and `b c`. |
| trigram counts fit within bigram counts | error | A trigram can't occur more often than its bigrams. |
| word totals match imported sources | warning | Sum of word counts equals the sum of `source_files.word_count`. |

A check passes when `violations` is 0, and `examples` lists up to five offenders. A **warning**
is expected after editing counts by hand on the Words tab or adding new words through
auto-complete, because those change `words` without adding a source file.

### Resetting the database

> [!CAUTION]
> `reset.sql` empties **every** table in the shared database for the whole team. It can't be
> undone. Agree on it as a team first.

After a reset, re-import the texts in [`texts/`](texts/) (or your own) on the Import tab.

## User guide

The window has seven tabs. If the database is empty, start with **Import**: every other tab
works from what's been imported.

**A quick first run:**

1. On **Import**, import `texts/adventures-of-sherlock-holmes.txt`.
2. On **Generate**, type `the`, pick **Weighted random**, and press **Generate** a few times.
3. On **Auto-complete**, type `i am ` and click the suggestions.
4. On **Words**, look up `holmes`.
5. On **History**, see everything you just generated.

### Import

Adds a text file to the corpus.

1. Click **Import Text File…** and choose a `.txt` file (UTF-8).
2. The progress bar fills while the file is read and saved. A large novel takes a little while,
   mostly because of the network round-trip to Supabase.
3. When it finishes, the status line shows how many words and distinct words were added.

The table below the button lists everything in the corpus: each imported **File** and each
**Generated** batch added from the Batch tab, with its word count and import time. The line under
the table sums them up.

- Importing text that's already in the corpus is refused with "This text was already imported as
  … on …". The check is on the words themselves, so a renamed or reformatted copy is still caught.
- If an import fails partway, nothing is saved, so you can just try again.
- Text from [Project Gutenberg](https://www.gutenberg.org/) works well. Delete the Gutenberg
  license header and footer first so they don't end up in the counts. Two cleaned books are in
  [`texts/`](texts/).

### Generate

Makes one sentence from a starting word you choose.

1. Type a **Starting word** (one word, any case).
2. Pick an **Algorithm**:
   - **Weighted random**: each next word is picked at random, weighted by how often it followed
     the previous word. Rare words still show up sometimes, so results vary each time.
   - **Most frequent next word**: always takes the most common next word. The same start word
     always gives the same sentence.
   - **Trigram backoff**: looks at the last *two* words to pick the next one, and falls back to
     the last word when that pair was never seen. Usually reads most naturally.
3. Press **Generate** or hit Enter.

Sentences stop at a dead end (a word nothing follows) or at 20 words. If the word isn't in the
corpus, you'll see "isn't in the database yet". Every sentence is saved to History.

### Auto-complete

Suggests the next word as you type, like a phone keyboard.

1. Type in the text box. Suggestions appear **only after you finish a word** with a space, comma,
   or period, never while you're mid-word.
2. Up to five next words appear as buttons, best first. Hover over one to see how often it was
   seen after your word.
3. Click a suggestion to add it (plus a space), or keep typing your own word.
4. **Clear** empties the box.

What you type teaches the app:

- Each finished word counts as your choice after the previous word, whether you typed it or
  clicked it. That raises the pair's `user_selected` count, so it ranks higher next time.
  Suggestions are ranked by `occurrences + user_selected`.
- A word that isn't in the database yet is added.
- The first word after a period starts a new sentence, so it isn't paired with the word before it.

### Words

Looks up one word and lets you fix its counts.

1. Type a word in **Look up a word** and press **Look up** or Enter.
2. The heading shows how often the word was seen, how often it started a sentence, and how often
   it ended one.
3. Two tables show the top 20 words that **come after** it and **come before** it. **Count** is
   how often the pair appeared in imported text. **Picked** is how often a user chose it in
   auto-complete.
4. To correct the counts, edit **Total**, **Start**, and **End** under **Fix counts**, then press
   **Save**.

> [!NOTE]
> Fixing counts is a manual override. It changes only the word's own numbers, not the word-pair
> counts, so edited numbers can drift from what the importer counted. `integrity_checks.sql` will
> report those words. The generators use these counts, so edit with care.

### Word List

Every word in the corpus with its counts, 50 per page.

- **Sort:** click a column header (**Word**, **Total**, **Starts sentence**, **Ends sentence**).
  The first click sorts low to high (A to Z), the second high to low. The whole list is sorted,
  not just the current page.
- **Filter:** fill in any of these and press **Apply** (or Enter):
  - **Starts with:** words beginning with these letters.
  - **Min count:** words seen at least this many times.
  - **Starts sentences** / **Ends sentences:** only words that have started or ended a sentence.
- **Clear** removes the filters. **Prev** and **Next** move between pages. **Refresh** reloads
  after you import or edit elsewhere.

### Batch

Generates many sentences at once from random starting words, then optionally adds them to the
corpus.

1. Set **Sentences** (how many), **Algorithm**, and **Max words** (longest sentence allowed).
2. Press **Generate**. The sentences are listed and saved to History.
3. Press **Add to Corpus** to import them as a **Generated** source. Their words and pairs now
   count toward future generation, just like an imported file. The button turns off after a
   successful add so the same batch isn't added twice by accident.

Starting words are chosen at random from words that have started a sentence, so the same start
word (and sentence) can come up more than once. If nothing has been imported yet, you'll be asked
to import a text file first.

### History

Every generated sentence, from both Generate and Batch. It reloads each time you open the tab.

- **Recently generated:** the latest 100 sentences with their starting word, algorithm, and time.
- **Generated more than once:** sentences that came out more than once, most repeated first, with
  how many times. **Most frequent next word** repeats a lot by design, since it always makes the
  same sentence from the same word.

## How it works

### What counts as a word

The [`Tokenizer`](src/main/java/com/sentencebuilder/tokenizer/Tokenizer.java) is shared by the
importer, auto-complete, and every place a user types a word, so a word is always stored and
looked up the same way.

- A word is a run of letters, which may contain apostrophes and single hyphens (`don't`,
  `well-known`).
- Words are lowercased, accents are removed (`café` → `cafe`), and curly apostrophes become
  straight ones.
- Numbers and anything containing a digit are skipped, as are words over 100 characters.
- A sentence ends at `.`, `!`, `?`, an ellipsis, or a blank line. A single line break is just a
  space.
- `Mr.`, `Mrs.`, `Dr.`, and initials like `J.` don't end a sentence, and neither do `?`, `!`, or
  `…` followed by a lowercase word (`"What?" he asked.`).
- Known limitation: a capitalized word after a quoted exclamation still splits, as in
  `"Seven!" I answered.`

### Importing

[`Importer`](src/main/java/com/sentencebuilder/importer/Importer.java) streams the file one sentence
at a time through
[`CorpusStats`](src/main/java/com/sentencebuilder/importer/CorpusStats.java), which counts words,
starts, ends, bigrams, and trigrams. Pairs and triples never cross a sentence boundary. All counts
and the `source_files` row are written in one transaction, so an import fully succeeds or leaves
the database unchanged. A SHA-256 hash of the tokenized words catches repeat imports.

### Generators

All three generators extend
[`AbstractGenerator`](src/main/java/com/sentencebuilder/generator/AbstractGenerator.java), so the UI
can run any of them the same way.

| Algorithm | Class | How it picks the next word |
|-----------|-------|----------------------------|
| Weighted random | [`MarkovGenerator`](src/main/java/com/sentencebuilder/generator/MarkovGenerator.java) | At random, with probability proportional to how often each word followed the current one. |
| Most frequent next word | [`FrequentWordGenerator`](src/main/java/com/sentencebuilder/generator/FrequentWordGenerator.java) | The single most common follower of the current word. |
| Trigram backoff | [`TrigramBackoffGenerator`](src/main/java/com/sentencebuilder/generator/TrigramBackoffGenerator.java) | Weighted random over trigrams for the last two words, falling back to bigrams for the last word. |

Generators return raw lowercase text.
[`SentenceFormatter`](src/main/java/com/sentencebuilder/generator/SentenceFormatter.java) capitalizes
it and adds a period only for display, so History compares and Batch imports the exact raw words.

### Architecture

```
ui/            JavaFX controllers, one per tab (FXML views in src/main/resources)
  │
  ├── generator/     sentence algorithms, batch generation, sentence history
  ├── autocomplete/  next-word suggestions and recording user choices
  ├── importer/      file import and counting
  └── tokenizer/     text → normalized sentences of words
        │
repository/    interfaces the generators and auto-complete depend on (tests use fakes)
database/      JDBC implementations against Supabase Postgres
```

Database work always runs on a background thread so the window never freezes.

## Project layout

```
.
├── build.gradle.kts            Gradle build (Java 21 toolchain, JavaFX 21, Postgres driver, JUnit 5)
├── .env.example                Template for database credentials
├── database/
│   ├── schema.sql              Creates all tables and indexes
│   ├── integrity_checks.sql    Read-only consistency report
│   ├── reset.sql               Empties every table (destructive)
│   └── diagram.md / .png       ER diagram
├── docs/
│   ├── project-description.md  The assignment
│   └── comment-style.md        Required comment and header style
├── texts/                      Sample books, ready to import
└── src/
    ├── main/java/com/sentencebuilder/
    │   ├── app/                Main: launches the JavaFX window
    │   ├── ui/                 Tab controllers
    │   ├── generator/          Sentence generators, batch service, history, formatter
    │   ├── autocomplete/       Auto-complete logic
    │   ├── importer/           Importer and CorpusStats
    │   ├── tokenizer/          Tokenizer
    │   ├── model/              WordTransition
    │   ├── repository/         Repository interfaces
    │   └── database/           Database connection and Postgres repositories
    ├── main/resources/com/sentencebuilder/ui/   FXML layouts, one per tab
    └── test/java/com/sentencebuilder/           JUnit 5 tests
```

## Building and testing

```bash
./gradlew run      # build and launch the app
./gradlew build    # compile and run all tests
./gradlew test     # run the tests only
./gradlew clean    # delete build output
```

Remember to [use Java 21](#requirements).

Tests for the tokenizer, generators, formatter, and auto-complete use in-memory fakes and need no
database. The `database/` tests, plus a few others that save to the database, connect to the
Supabase database in `.env`. Each one works in a transaction it rolls back or in temporary
tables, so it doesn't leave data behind. Without valid credentials those tests fail with
`IllegalStateException: DB_URL is not set in .env or the environment`.

GitHub Actions ([`.github/workflows/test.yml`](.github/workflows/test.yml)) runs `./gradlew test`
on Java 21 for every pull request and push to `main`. It reads `DB_URL`, `DB_USER`, and
`DB_PASSWORD` from the repository's Actions secrets.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Gradle fails immediately with `What went wrong: 27` (or another Java version number) | Your default JDK is too new for Gradle 8.14. Set `JAVA_HOME` to JDK 21. See [Requirements](#requirements). |
| `DB_URL is not set in .env or the environment` | Create `.env` from `.env.example` in the project root and fill in all three keys. |
| `Connection refused`, timeout, or `password authentication failed` | Use the **Session pooler** values from Supabase → Connect, keep `?sslmode=require` on the URL, and check the user is `postgres.<project-ref>`, not just `postgres`. |
| `relation "words" does not exist` | The schema isn't on this database. Run `database/schema.sql`. |
| `relation "source_files" already exists` when running `schema.sql` | The schema is already applied. Use `reset.sql` to clear data instead. |
| "This text was already imported as …" | That text is already in the corpus. Nothing was changed. |
| "… isn't in the database yet" on Generate or Words | Import a text that uses the word, or check the spelling. |
| Batch says no word has started a sentence | The corpus is empty. Import a text file first. |
| No auto-complete suggestions | Finish the word with a space, comma, or period. If it still shows none, nothing in the corpus follows that word. |
| `integrity_checks.sql` shows warnings | Expected after editing counts on the Words tab or adding words in auto-complete. Errors mean the counts are inconsistent. |
| Build fails for other reasons | Run `./gradlew clean build` and check you're on JDK 21. |

## Contributing

- Branch from `main`, one branch per issue, and open a pull request. CI must pass before merging.
- Every source file needs a header comment and every method needs a doc comment, following
  [`docs/comment-style.md`](docs/comment-style.md) (Prof. Cole's documentation standard). If AI
  helped write a file, its header may include the tool and the exact prompts.
- The shared Supabase database is used by everyone. Don't run `reset.sql` or change the schema
  without telling the team.

## References

- **Assignment:** [`docs/project-description.md`](docs/project-description.md)
- **Comment style:** [`docs/comment-style.md`](docs/comment-style.md)
- **Schema:** [`database/schema.sql`](database/schema.sql) and [`database/diagram.md`](database/diagram.md)
- **Text sources:** [Project Gutenberg](https://www.gutenberg.org/) (use the Plain Text UTF-8 downloads)
- **JavaFX:** [openjfx.io](https://openjfx.io/)
- **Supabase:** [supabase.com/docs](https://supabase.com/docs)
