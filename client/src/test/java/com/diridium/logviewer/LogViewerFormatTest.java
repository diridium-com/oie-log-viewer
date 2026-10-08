// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class LogViewerFormatTest {

    // The status bar separates its items with a middle dot.
    private static final String DOT = " \u00B7 ";

    @Test
    void statusPositionInTheMiddleOfAFileHasNoSuffix() {
        assertEquals("Lines 2,001-3,000 of 11,905",
                LogViewerFormat.statusPosition(2001L, 1000, 11_905L, 102_400, 358_912, 1_048_576L, false));
    }

    @Test
    void statusPositionSaysStartEndOrWholeFile() {
        assertEquals("Lines 1-1,000 of 11,905" + DOT + "start of file",
                LogViewerFormat.statusPosition(1L, 1000, 11_905L, 0, 90_000, 1_000_000L, false));
        assertEquals("Lines 10,906-11,905 of 11,905" + DOT + "end of file",
                LogViewerFormat.statusPosition(10_906L, 1000, 11_905L, 900_000, 1_000_000, 1_000_000L, true));
        assertEquals("Lines 1-500 of 500" + DOT + "whole file",
                LogViewerFormat.statusPosition(1L, 500, 500L, 0, 40_000, 40_000L, true));
    }

    @Test
    void statusPositionDropsTheTotalWhenTheServerDidNotCountLines() {
        assertEquals("Lines 2,001-3,000", LogViewerFormat.statusPosition(2001L, 1000, null, 5, 10, null, false));
        assertEquals("Lines 1-1,000" + DOT + "start of file",
                LogViewerFormat.statusPosition(1L, 1000, null, 0, 10, null, false));
    }

    @Test
    void statusPositionFallsBackToBytesWithTheSameSuffixes() {
        assertEquals("Bytes 0-4,096 of 10,000" + DOT + "start of file",
                LogViewerFormat.statusPosition(null, 40, null, 0, 4096, 10_000L, false));
        assertEquals("Bytes 5,000-10,000 of 10,000" + DOT + "end of file",
                LogViewerFormat.statusPosition(null, 40, null, 5000, 10_000, 10_000L, true));
        assertEquals("Bytes 0-500 (total size not known yet)" + DOT + "start of file",
                LogViewerFormat.statusPosition(null, 10, null, 0, 500, null, false));
    }

    @Test
    void anEmptyPageHasNoLineRange() {
        assertEquals("Bytes 0-0 of 0" + DOT + "whole file", LogViewerFormat.statusPosition(1L, 0, 0L, 0, 0, 0L, true));
    }

    @Test
    void statusItemsJoinWithAMiddleDotAndSkipTheEmptyOnes() {
        assertEquals("", LogViewerFormat.statusItems());
        assertEquals("a" + DOT + "c", LogViewerFormat.statusItems("a", "", null, "c"));
        assertEquals("Loading..." + DOT + "UTF-8" + DOT + "2.0 MB" + DOT + "Times are engine time: MDT (UTC-06:00)",
                LogViewerFormat.statusItems("Loading...", "UTF-8", "2.0 MB", "Times are engine time: MDT (UTC-06:00)"));
    }

    @Test
    void midLineNotesNameTheEndThatIsSplit() {
        assertEquals("", LogViewerFormat.midLineNotes(false, false));
        assertTrue(LogViewerFormat.midLineNotes(true, false).contains("first line continues from the previous"));
        assertTrue(LogViewerFormat.midLineNotes(false, true).contains("last line continues on the next"));
        String both = LogViewerFormat.midLineNotes(true, true);
        assertTrue(both.contains("first line") && both.contains("last line"));
    }

    @Test
    void byteSizesUseBinaryUnits() {
        assertEquals("0 B", LogViewerFormat.bytes(0));
        assertEquals("1023 B", LogViewerFormat.bytes(1023));
        assertEquals("1.0 KB", LogViewerFormat.bytes(1024));
        assertEquals("1.5 MB", LogViewerFormat.bytes(1_572_864));
        assertEquals("2.0 GB", LogViewerFormat.bytes(2L * 1024 * 1024 * 1024));
    }

    @Test
    void countHeaderSaysLinesAndFilesWithMatches() {
        assertEquals("1,641 matching lines in 6 files", LogViewerFormat.countHeader(1641, 6, 6));
        assertEquals("1 matching line in 1 file", LogViewerFormat.countHeader(1, 1, 5));
        assertEquals("12 matching lines in 1 file", LogViewerFormat.countHeader(12, 1, 6));
    }

    @Test
    void anEmptyCountNamesTheFilesCounted() {
        assertEquals("No matching lines in 6 files", LogViewerFormat.countHeader(0, 0, 6));
        assertEquals("No matching lines in 1 file", LogViewerFormat.countHeader(0, 0, 1));
    }

    @Test
    void aCountTheTimeLimitStoppedSaysSoWithoutTheFileCounts() {
        // The files counted of the files in scope are on the results status line, not here.
        assertEquals("245 matching lines so far in 2 files.", LogViewerFormat.countPartialHeader(245, 2));
        assertEquals("1 matching line so far in 1 file.", LogViewerFormat.countPartialHeader(1, 1));
    }

    @Test
    void theResultsSummaryNamesFilesSizeAndTime() {
        assertEquals("Counted 7 of 7 files, 12.0 MB searched in 0.9 s",
                LogViewerFormat.countSummary(7, 7, 12L * 1024 * 1024, 900, false));
        assertEquals("Counted 3 of 6 files before the time limit, 4.1 MB searched in 15.0 s",
                LogViewerFormat.countSummary(3, 6, 4_300_000, 15_000, true));
        assertEquals("Counted 1 of 1 files, 0 B searched in 0.0 s", LogViewerFormat.countSummary(1, 1, 0, 0, false));
        assertEquals("Counted 2,000 of 2,000 files, 1.0 KB searched in 12.3 s",
                LogViewerFormat.countSummary(2000, 2000, 1024, 12_345, false));
    }

    @Test
    void theResultsFailureLinesAreTheWordsOfTheDesign() {
        assertEquals("The search failed.", LogViewerFormat.SEARCH_FAILED);
        assertEquals("The count could not be finished.", LogViewerFormat.COUNT_NOT_FINISHED);
    }

    @Test
    void theLoadMoreRowSaysHowManyAreShownAndHowManyComeNext() {
        assertEquals("1,000 of 2,991 shown. Click to show the next 1,000.",
                LogViewerFormat.loadMoreRow(1000, 2991, true));
        assertEquals("2,000 of 2,991 shown. Click to show the next 991.",
                LogViewerFormat.loadMoreRow(2000, 2991, true));
        assertEquals("2,990 of 2,991 shown. Click to show the next 1.",
                LogViewerFormat.loadMoreRow(2990, 2991, true));
    }

    @Test
    void theLoadMoreRowSaysMoreWhenTheCountCannotSayHowMany() {
        // A partial count, or a file that grew after it was counted.
        assertEquals("Click to show more.", LogViewerFormat.loadMoreRow(1000, 245, false));
        assertEquals("Click to show more.", LogViewerFormat.loadMoreRow(1000, 1000, true));
        assertEquals("Click to show more.", LogViewerFormat.loadMoreRow(1200, 1000, true));
    }

    @Test
    void loadingTextsUseTheSameNumberAsTheClickText() {
        assertEquals("Loading the next 1,000...", LogViewerFormat.loadingMoreRow(1000, 2991, true));
        assertEquals("Loading the next 991...", LogViewerFormat.loadingMoreRow(2000, 2991, true));
        assertEquals("Loading more...", LogViewerFormat.loadingMoreRow(1000, 1000, true));
        assertEquals("Loading more...", LogViewerFormat.loadingMoreRow(1000, 5000, false));
        assertEquals("Loading the next 1,000 from mirth.log.3.zip...",
                LogViewerFormat.loadingMoreFrom("mirth.log.3.zip", 0, 2991, true));
        assertEquals("Loading more from mirth.log...", LogViewerFormat.loadingMoreFrom("mirth.log", 1000, 245, false));
        assertEquals("Could not load more. Click to try again.", LogViewerFormat.LOAD_MORE_FAILED);
    }

    @Test
    void countingAndRotatedHeadersAreTheWordsOfTheDesign() {
        assertEquals("Counting matching lines...", LogViewerFormat.COUNTING);
        assertEquals("The log files rotated while counting. Refresh the list and search again.",
                LogViewerFormat.COUNT_ROTATED);
    }

    @Test
    void everyStopReasonIsExplainedAndNoneMentionsContinueSearch() {
        for (LogSearchStopReason reason : LogSearchStopReason.values()) {
            String text = LogViewerFormat.stopReason(reason);
            assertTrue(!text.isEmpty(), reason.toString());
            assertTrue(!text.contains("Continue search"), reason + ": " + text);
        }
        assertTrue(LogViewerFormat.stopReason(LogSearchStopReason.DEADLINE).contains("time limit"));
        assertTrue(LogViewerFormat.stopReason(LogSearchStopReason.PATTERN_TOO_COMPLEX).contains("Simplify"));
        assertTrue(LogViewerFormat.stopReason(LogSearchStopReason.FILES_ROTATED).contains("search again"));
    }

    @Test
    void warningsAreListedAfterAHeader() {
        assertEquals("", LogViewerFormat.warningsNote(Collections.emptyList()));
        assertEquals("", LogViewerFormat.warningsNote(null));
        assertEquals("Warnings: one; two", LogViewerFormat.warningsNote(Arrays.asList("one", "two")));
    }

    @Test
    void fileNameIsTakenFromAServerId() {
        assertEquals("mirth.log.3.zip", LogViewerFormat.fileNameFromId("fout/mirth.log.3.zip@a03c19e4d2f8"));
        assertEquals("odd-id", LogViewerFormat.fileNameFromId("odd-id"));
        assertEquals("", LogViewerFormat.fileNameFromId(null));
    }

    @Test
    void downloadProgressNamesBothFigures() {
        assertEquals("1.0 KB of 2.0 KB", LogViewerFormat.progress(1024, 2048));
        assertEquals("1.0 KB", LogViewerFormat.progress(1024, 0));
    }

    @Test
    void anEngineTimeIsWrittenInTheEnginesZoneNotTheViewersOwn() {
        // 2026-10-04 21:30:05 UTC
        long millis = java.time.Instant.parse("2026-10-04T21:30:05Z").toEpochMilli();
        assertEquals("2026-10-04 15:30:05", LogViewerFormat.engineTime(millis, "America/Denver", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("2026-10-05 06:30:05", LogViewerFormat.engineTime(millis, "Asia/Tokyo", "yyyy-MM-dd HH:mm:ss"));
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"));
            assertEquals("15:30:05", LogViewerFormat.engineTime(millis, "America/Denver", "HH:mm:ss"));
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }

    @Test
    void aMissingOrUnknownZoneFallsBackToUtc() {
        long millis = java.time.Instant.parse("2026-10-04T21:30:05Z").toEpochMilli();
        assertEquals("21:30:05", LogViewerFormat.engineTime(millis, null, "HH:mm:ss"));
        assertEquals("21:30:05", LogViewerFormat.engineTime(millis, "", "HH:mm:ss"));
        assertEquals("21:30:05", LogViewerFormat.engineTime(millis, "Not/AZone", "HH:mm:ss"));
    }

    @Test
    void theZoneAndSnapshotSentencesAreWhatTheDesignSays() {
        assertEquals("Times are engine time: MDT (UTC-06:00)", LogViewerFormat.timeZoneNotice("MDT (UTC-06:00)"));
        assertEquals("Times are engine time", LogViewerFormat.timeZoneNotice(null));
        assertEquals("Snapshot of mirth.log taken at 15:30:05 (engine time). It does not update by itself.",
                LogViewerFormat.snapshotNotice("mirth.log", "15:30:05"));
    }

    @Test
    void aGroupHeaderCarriesItsMatchingLineCount() {
        assertEquals("mirth.log.3.zip (578 matching lines)", LogViewerFormat.groupLabel("mirth.log.3.zip", 578, true));
        assertEquals("mirth.log (1 matching line)", LogViewerFormat.groupLabel("mirth.log", 1, true));
        assertEquals("mirth.log (1,641 matching lines)", LogViewerFormat.groupLabel("mirth.log", 1641, true));
    }

    @Test
    void aGroupWhoseCountIsNotFinishedShowsAPlus() {
        assertEquals("mirth.log.2.zip (245+ matching lines)", LogViewerFormat.groupLabel("mirth.log.2.zip", 245, false));
    }

    @Test
    void theHighlightNoteCountsPairs() {
        assertEquals("", LogViewerFormat.highlightNote(null, null));
        assertEquals("0 matches highlighted on this page.", LogViewerFormat.highlightNote(new int[0], null));
        assertEquals("1 match highlighted on this page.", LogViewerFormat.highlightNote(new int[] {0, 2}, null));
        assertEquals("3 matches highlighted on this page.", LogViewerFormat.highlightNote(new int[6], null));
    }

    @Test
    void theHighlightNoteSaysWhichLimitStoppedTheMarks() {
        assertEquals("Only the first 5,000 matches on this page are highlighted.",
                LogViewerFormat.highlightNote(new int[10000], LogHighlightStop.MATCH_LIMIT));
        assertEquals("Highlighting reached its 2-second limit, so only part of this page is highlighted.",
                LogViewerFormat.highlightNote(new int[6], LogHighlightStop.TIME_LIMIT));
        assertEquals("The search pattern is too complex for a line on this page, so only part of it is highlighted.",
                LogViewerFormat.highlightNote(new int[0], LogHighlightStop.TOO_COMPLEX));
    }

    @Test
    void searchTitleNamesTheScopeAndOnlyTheOptionsThatAreOn() {
        assertEquals("\" in all files", LogViewerFormat.searchTitleRest(null, false, false));
        assertEquals("\" in mirth.log.3.zip (Java regular expression)",
                LogViewerFormat.searchTitleRest("mirth.log.3.zip", true, false));
        assertEquals("\" in all files (match case)", LogViewerFormat.searchTitleRest(null, false, true));
        assertEquals("\" in all files (Java regular expression, match case)",
                LogViewerFormat.searchTitleRest(null, true, true));
        assertEquals("Search results for \"ERROR\" in all files",
                LogViewerFormat.SEARCH_TITLE_LEAD + "ERROR" + LogViewerFormat.searchTitleRest(null, false, false));
    }

    @Test
    void cutsALongSearchIntoPiecesWithoutSplittingACharacter() {
        String json = "{\"patientId\":\"12345\",\"encounter\":\"E-998\"}";
        assertEquals(List.of("{\"patientId\":\"", "12345\",\"encoun", "ter\":\"E-998\"}"),
                LogViewerFormat.pieces(json, 14));
        assertEquals(String.join("", LogViewerFormat.pieces(json, 14)), json);
        assertEquals(List.of("ab"), LogViewerFormat.pieces("ab", 50));
        assertEquals(List.of(), LogViewerFormat.pieces("", 50));
        // An emoji is two chars; a piece ends before it rather than between its halves.
        assertEquals(List.of("ab", "\uD83D\uDE00c"), LogViewerFormat.pieces("ab\uD83D\uDE00c", 3));
    }

    @Test
    void anArchiveInADateFolderIsSavedUnderOneNameWithTheFolderJoinedOn() {
        assertEquals("2026-10-06-1630_mirth.log.5.zip", LogViewerFormat.saveName("2026-10-06-1630/mirth.log.5.zip"));
        assertEquals("2026-10_06_mirth.log.1.gz", LogViewerFormat.saveName("2026-10/06/mirth.log.1.gz"));
        assertEquals("mirth.log", LogViewerFormat.saveName("mirth.log"));
    }

    @Test
    void aSearchThatCannotMoveOnSaysSo() {
        assertEquals("The search reached its time limit before finding a line in this file. Click to keep searching.",
                LogViewerFormat.KEEP_SEARCHING);
        assertEquals("The search pattern is too complex for a line in this file. Try a simpler pattern.",
                LogViewerFormat.PATTERN_TOO_COMPLEX_IN_FILE);
        assertEquals("The search can't get past this point within its time limit (a very long line or a "
                + "complex pattern). Try a simpler pattern.", LogViewerFormat.NO_PROGRESS);
    }

    @Test
    void theEndOfASnapshotPointsToLoadLatestLines() {
        assertEquals("This is the end of the snapshot. Load latest lines shows anything written since.",
                LogViewerFormat.SNAPSHOT_END);
    }

    @Test
    void aGroupWhoseFileIsGoneSaysToSearchAgain() {
        assertEquals("mirth.log.3.zip no longer exists under that name. Search again for current results.",
                LogViewerFormat.fileGoneRow("mirth.log.3.zip"));
    }

    @Test
    void aNameWithAFolderIsCutFromTheFoldersStartKeepingTheFilePartWhole() {
        String name = "2026-10-06-1630/mirth.log.5.zip";
        assertEquals(name, LogViewerFormat.fitName(name, 100, String::length));
        // One unit per char: the file part is 16, the ellipsis 1, so three folder chars fit in 20.
        assertEquals("\u2026630/mirth.log.5.zip", LogViewerFormat.fitName(name, 20, String::length));
        assertEquals("\u2026/mirth.log.5.zip", LogViewerFormat.fitName(name, 5, String::length));
        assertEquals("mirth.log.4.zip", LogViewerFormat.fitName("mirth.log.4.zip", 5, String::length));
    }
}
