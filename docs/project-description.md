# Project for Fall 2026 – Sentence Builder

**CS4485 Senior Design** · Prof. John Cole

## Overview

It seems like everyone is using ChatGPT and other Large Language Model AI programs, but it also appears that almost nobody understands how they work. Essentially, they’re auto-complete on steroids.

Your project will be to build such a program. Obviously, it will not have the capabilities of ChatGPT or CoPilot, but it must go beyond auto-complete. This will take some imagination and curiosity.

## Project Parts

There are several parts to this project.

### 1. Database

You will need to design a database that keeps, at a minimum, the following:

- Each word found in a body of text, along with how many times it occurs, how often it occurs at the start of a sentence, and how often it occurs at the end. **Question:** What, exactly, is a word in this context?
- You will also need, for each word, every word that immediately follows it, and the number of times that occurs. Since not all words can begin a sentence, and, for example, “A preposition is something you never end a sentence with,” you could track that, too.
- Since your program will be reading and parsing text files, keep track of which files have been imported, how many words they contained, and when you imported them.
- Decide what other information might be useful to have.

### 2. File Import

It follows that you must write a module that can read a text file, isolate individual words, and put them in your database. You should keep track of which files are imported and possibly other information about them.

### 3. Generative Code

The interesting part of this is the generative code. There are at least three things your program must do:

1. Generate a sentence using a starting word you supply, using various algorithms to choose which words finish the sentence. There are several ways to decide what words to use, and you are required to implement at least two. More is better. One must use the probability that one word follows another, since even low-probability words must show up occasionally in your generated sentences. Do not worry if the generated sentences don’t make sense.
2. Generate a number of sentences using randomly chosen starting words, then use these sentences as input to the program, similar to what item 2, above, does. For example, you would have it create 10 sentences and show them on the screen. Press a button, and those now become part of your body of text.
3. Implement auto-complete, where the user is offered choices of the next word or enters something not offered but in the list of words. For this, consider what metadata you might want to keep.
   - **Note:** Don’t try to predict words from when you start typing. Choose the possibilities for the next word only when you encounter a space, period, or comma, indicating a complete word.
   - If the user types a word that is not in your database, add it. If it is in your database, increment a count of how often it is chosen to follow the previous word.

### 4. Viewing and Modifying Words

You will need a way to see and possibly modify information about the words in your database. There is no need to delete information from your database.

### 5. Reporting

Your user should be able to see a list of all words in the system and information about them. The normal sort order is alphabetic, but other sorts, such as frequency, should be possible. Keep track of what sentences have been generated so your user can look for duplicates. Are there other reports that might be interesting? Would filtering help a user make sense of a large number of words?

## Design

Consider what a good object-oriented design might look like. For example, an abstract class with method that gets called to generate a sentence. This project is a great chance to apply some of what you have learned.

This will involve design of a database, and you can infer some of the tables from information given above. It will involve UI design and implementation, and it will require close coordination to make everything work together. Good object-oriented design and programming are essential.

## Technology

- **Language / UI:** Code this Java using JavaFX for the user interface. My preferred IDE is IntelliJ Idea community edition, since that makes creation of the GUI fairly easy. Other environments such as VSCode will work but are harder to work with.
- **Database:** Use MySQL or MariaDB for the database. I used the former, and MySQL Workbench to create my tables.

## Input Data

Your input data needs to be text files, preferably fairly large ones. Depending upon where you get them, you might need to edit them a little.

- For some of my testing I went to Gutenberg.org and got Jane Austen novels, and I had to remove some of their material from the beginning and end.
- Another interesting data set would be CACM articles, converted to plain text, but these might not be long enough to make your results interesting.

Files can take a while to import, so you should have some kind of progress indicator.
