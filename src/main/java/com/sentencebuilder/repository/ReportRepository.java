/*
 * The data behind the word report and the duplicates report. Lists are empty,
 * never null, when nothing matches.
 */

package com.sentencebuilder.repository;

import com.sentencebuilder.repository.ReportRows.DuplicateRow;
import com.sentencebuilder.repository.ReportRows.WordReportRow;

import java.util.List;

public interface ReportRepository
{

    /**
     * Returns every word with its counts, sorted by the chosen column. Only words
     * containing the given text are returned (blank means all words), and only
     * words seen at least minTotalCount times (0 means no minimum). Ties are
     * broken alphabetically so the order is the same on every run.
     */
    List<WordReportRow> wordReport(WordSort sortBy, boolean descending,
                                   String containing, int minTotalCount);

    /**
     * Returns the generated sentences that were produced more than once, most
     * repeated first. Sentences are compared by their exact text.
     */
    List<DuplicateRow> duplicateReport();
}