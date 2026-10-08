/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The search results docked under the viewer: the title (click it to reopen the
 * Search dialog), the count header with Count the rest and Close, one group per
 * file with its matching lines and its last row (load more, keep searching, or
 * why the search stopped there), and the results status line.
 * It only draws; the search and its groups are held by useSearch (log-search.js).
 */

import { platform } from '@oie/web-shell';
import { count } from './log-core.js';
import {
    groupTitle, groupEndRow, excerpt, countGroups, countHeader, canCountRest, isPartial,
    resultsSummary, loadingFromText, searchNotes, STUCK_TEXT,
    SEARCH_TITLE_LEAD, searchTitleRest, pieces
} from './log-search-core.js';

const React = platform.React;

/* The keys of a row that acts as a button: Enter and Space, as on a button. */
function onActivate(action) {
    return (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            action();
        }
    };
}

/* A result line with the match picked out, cut so the match is always in view. */
function MatchText({ match }) {
    const cut = excerpt(match.lineText, match.matchStart, match.matchEnd, 40);
    return (
        <>
            {cut.text.substring(0, cut.start)}
            {cut.end > cut.start ? <mark>{cut.text.substring(cut.start, cut.end)}</mark> : null}
            {cut.text.substring(cut.end)}
        </>
    );
}

/* One file's group: its header, and when open its matching lines and the row after them. */
function LogResultGroup({ g, group, search, pickedRow, onToggle, onOpenMatch, onLoadMore }) {
    const open = group.open === true;
    const rows = group.rows || [];
    const end = open ? groupEndRow(group, g, isPartial(search, g)) : null;
    return (
        <div>
            <div className="lv-group-head" tabIndex={0} role="button" aria-expanded={open}
                title={(open ? 'Hides' : 'Shows') + ' the matching lines in ' + g.fileName + '.'}
                onClick={() => onToggle(g.fileId)}
                onKeyDown={onActivate(() => onToggle(g.fileId))}>
                <span aria-hidden="true">{open ? '-' : '+'}</span>
                {groupTitle(g, isPartial(search, g))}
            </div>
            {open && rows.map((row, i) => {
                const key = g.fileId + '#' + i;
                return (
                    <div key={key} className={'lv-hit-row' + (key === pickedRow ? ' selected' : '')}
                        tabIndex={0}
                        title={'Line ' + count(row.match.lineNumber) + ' of ' + g.fileName
                            + (row.match.truncated ? '. The line is long and shortened here.' : '')
                            + '. Click to show it in the viewer.'}
                        onClick={() => onOpenMatch(row, key)}
                        onKeyDown={(e) => { if (e.key === 'Enter') onOpenMatch(row, key); }}>
                        <span className="lv-hit-line">{count(row.match.lineNumber)}</span>
                        <span className="lv-hit-text"><MatchText match={row.match} /></span>
                    </div>
                );
            })}
            {end && !end.click && <div className="lv-more-row">{end.text}</div>}
            {end && end.click && (
                <div className="lv-more-row lv-retry" tabIndex={0} role="button" title={end.title}
                    onClick={() => onLoadMore(g.fileId)}
                    onKeyDown={onActivate(() => onLoadMore(g.fileId))}>
                    {end.text}
                </div>
            )}
        </div>
    );
}

/*
 * `search` is the search state (log-core.js), `groups` file id -> the state of that file's group,
 * `searchNote` the one-off status text a failed search leaves, `pickedRow` the match last opened.
 */
export function LogResults({
    search, groups, searchBusy, searchNote, pickedRow,
    onReopenDialog, onCountRest, onClose, onToggleGroup, onOpenMatch, onLoadMore
}) {
    const { useMemo } = React;
    const resultGroups = useMemo(() => countGroups(search), [search]);
    // The header is only the count line; what the search is doing or did, and its warnings, are the status line.
    const resultsHeader = search.last ? countHeader(search) : '';
    const loadingGroup = resultGroups.find((g) => groups[g.fileId] && groups[g.fileId].open && groups[g.fileId].loading);
    let resultsStatus;
    if (searchBusy) {
        resultsStatus = 'Counting matching lines...';
    } else if (loadingGroup) {
        resultsStatus = loadingFromText((groups[loadingGroup.fileId].rows || []).length, loadingGroup.matchingLines,
            isPartial(search, loadingGroup), loadingGroup.fileName);
    } else {
        resultsStatus = searchNote !== '' ? searchNote : resultsSummary(search);
    }
    const resultsNotes = searchNotes(search);

    return (
        <div className="lv-results">
            <div className="lv-results-head">
                {search.params && (
                    <button type="button" className="lv-search-title" onClick={onReopenDialog}
                        title={'Search results for:\n' + pieces(search.params.query, 60).join('\n') + '\n'
                            + searchTitleRest(search.params.fileName, search.params.regex,
                                search.params.caseSensitive).substring(2)
                            + '.\nClick to open the search with this text, to read it whole or change it.'}>
                        <span>{SEARCH_TITLE_LEAD}</span>
                        <span className="lv-search-query">{search.params.query}</span>
                        <span>{searchTitleRest(search.params.fileName, search.params.regex,
                            search.params.caseSensitive)}</span>
                    </button>
                )}
                <strong className="lv-count-head"
                    title="How many lines match the search, by file. Open a file's group to see its lines.">{resultsHeader}</strong>
                {!searchBusy && canCountRest(search) && (
                    <button className="btn btn-sm" onClick={onCountRest}
                        title="Counts the files the time limit left out and adds them to the results.">Count the rest</button>
                )}
                {!searchBusy && search.stuck && <span className="lv-stuck">{STUCK_TEXT}</span>}
                <button className="btn btn-ghost btn-sm" onClick={onClose}
                    title="Closes the results and removes the search highlights from the viewer."
                    aria-label="Close search results">Close</button>
            </div>
            <div className="lv-results-body">
                {resultGroups.map((g) => (
                    <LogResultGroup key={g.fileId} g={g} group={groups[g.fileId] || {}} search={search}
                        pickedRow={pickedRow} onToggle={onToggleGroup} onOpenMatch={onOpenMatch}
                        onLoadMore={onLoadMore} />
                ))}
            </div>
            <div className="lv-results-foot">
                <span className="lv-status-left" title={resultsStatus}>{resultsStatus}</span>
                <span className="lv-status-right" title={resultsNotes}>{resultsNotes}</span>
            </div>
        </div>
    );
}
