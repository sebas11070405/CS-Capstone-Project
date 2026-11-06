/*
 * The rows returned by the two reports.
 */

package com.sentencebuilder.repository;

import java.time.OffsetDateTime;

public final class ReportRows
{
    private ReportRows() {}

    /** One word with how often it appears overall, at the start of a sentence, and at the end. */
    public record WordReportRow(String word, int totalCount, int startCount, int endCount) {}

    /** One generated sentence that appeared more than once, and when it first and last appeared. */
    public record DuplicateRow(String sentence, int timesGenerated,
                               OffsetDateTime firstGenerated, OffsetDateTime lastGenerated) {}
}