/*
 * The columns the word report can be sorted by. The column names live here,
 * not in the caller's input, so no user-supplied text ever reaches the SQL.
 */

package com.sentencebuilder.repository;

public enum WordSort
{
    WORD("word"),
    TOTAL("total_count"),
    START("start_count"),
    END("end_count");

    private final String column;

    WordSort(String column)
    {
        this.column = column;
    }

    /** Returns the words table column to sort by. */
    public String column() {
        return column;
    }
}