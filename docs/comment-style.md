# Comment Style

These rules follow Prof. Cole's *Documentation Standards*, and comments are part of the grade. Every
file uses the same structure so the project reads as one codebase.

Keep comments short, but don't skip any the standard requires. A good comment explains **what** a block
does and **why**, especially when the reason depends on domain knowledge you can't see in the code. A
comment that only repeats the code counts as no comment:

```java
// Close file
reader.close();
```

Write the method signature and its comment **before** writing the body. Prof. Cole may send you away
from office hours if your code has no comments.

## 1. File Headers (required in every file)

Every file starts with a header, before `package` in Java or the first statement in SQL, followed by
one blank line. The header includes:

1. **Description (required):** what the module does, how it's used, and what it needs (files, `.env`
   keys, tables). Include enough detail that someone could use the module from the header alone.
2. **AI disclosure (optional):** the author decides whether to include it, though a reviewer may ask
   for it. If you include it, name the tool and paste every prompt word for word.

### Java

```java
/*
 * Reads a text file, tokenizes it into sentences, and adds the word, bigram,
 * and trigram counts to the database in one transaction. If the same text has
 * already been imported, it throws DuplicateImportException instead of
 * counting it twice.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: <tool, e.g. Claude Code, ChatGPT, Copilot>
 *
 * Prompt:
 * <Prompt pasted word for word.>
 *
 * Follow-up prompt:
 * <One block per follow-up that changed this file, in the order they were sent.>
 */

package com.sentencebuilder.importer;
```

- Use `/* */` with ` * ` at the start of each line. Don't use `/** */` for headers.
- Leave out the title and the name, NetID, and start date line the standard asks for. The file name
  says what the module is, and git history records who wrote what.
- Without an AI disclosure, the header is just the description.
- If several files came from one prompt, put the full prompt in the main file. The others point to it:
  `Prompt: see src/main/java/com/sentencebuilder/tokenizer/Tokenizer.java`.

### SQL

Same parts, using `/* */` without the leading ` * `. Say in the description if the script deletes data.

### FXML

The XML declaration has to stay on the first line, so put the header right after it inside `<!-- -->`
with the same parts.

## 2. Method Comments (required)

Every method gets a `/** */` comment directly above it. The comment says what the method does, what it
takes, and what it returns. Include why, if that isn't obvious. IntelliJ shows these when you hover.

```java
/**
 * Loads every word that has followed the given word, with how often each one
 * did. The generators use this to pick the next word in a sentence.
 * Returns an empty map if the word has never been followed by anything.
 */
private Map<String, Integer> loadFollowers(Connection conn, String word) throws SQLException
```

- Write inputs and outputs as prose, like the example above. Use `@throws` only when the reason for an
  exception isn't obvious.
- **Exceptions:** getters, setters, and tiny methods (a few lines) whose name already says what they do
  and return. This includes JUnit tests named for the behavior they check, like `lowercasesWords()`.
- Classes inside a file (records, nested or extra classes) get the same kind of comment.

## 3. Section Comments Inside Methods

Put a `//` comment above each major block: loops, important conditionals, database setup, transactions,
and object setup. Say what the block does and why.

```java
// Sort the keys so concurrent imports lock rows in the same order and can't
// deadlock each other
List<String> keys = new ArrayList<>(counts.keySet());
Collections.sort(keys);
```

## 4. Variable Comments

Fields, constants, and important locals whose meaning isn't clear from the name get a short comment.
Put it at the end of the line, or above if it won't fit.

```java
private final int maxWords;   // Stops generation if no end word comes up first
content_hash CHAR(64),        -- SHA-256 of the tokenized words
```

## 5. Naming

- Names must mean something and be easy to find with a text search. Don't use single-letter names like
  `i` or `j`. Use at least `ix`, or names like `iWord` and `iSentence`.
- An enhanced `for` loop variable should name the item: `for (String word : words)`.
- The one exception is `e` for the exception in a `catch` block.
- Good names don't replace the required method and section comments.

## 6. Program Structure

- In the class with `main`, put `main` first so the first executable code you see is where the program
  starts. In a JavaFX `Application`, put `main` before `start`.
- Keep methods short and focused on one job. A method that computes something returns the result. It
  doesn't display it. UI code lives in `ui/`.

## 7. Formatting

- Start comments with a capital letter. A single sentence gets no period. Two or more sentences get
  normal punctuation. Write clearly. Grammar isn't graded.
- Wrap comments at about 100 columns.
- Use `--` for single-line SQL comments and `//` in Java. Use `/* */` only for headers.

## Don't

| Avoid | Why |
| --- | --- |
| `// Returns a string` | Repeats the signature and doesn't say what the string is |
| `// Loop through the words` | Says what but not why |
| Commented-out code | Git history keeps it |
| Change logs or author tags in comments (`// Fixed 10/3`) | Git history tracks these |
| `for (int i = 0; ...)` | Can't be searched. See Naming. |

## TODOs

```java
// TODO(<name>): <what's left to do, and why it isn't done yet>
```

Remove the TODO in the same PR that fixes it.
