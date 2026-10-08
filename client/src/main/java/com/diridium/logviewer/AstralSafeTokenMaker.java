// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.modes.PlainTextTokenMaker;

/**
 * Plain text tokens that keep a surrogate pair together.
 *
 * <p>RSyntaxTextArea 2.5.6's plain text tokenizer works on single chars, so a
 * character outside the Basic Multilingual Plane (an emoji, a musical symbol)
 * becomes two one-char tokens, each holding half a pair. The token painter then
 * draws each half on its own, and a lone surrogate has no glyph: two empty
 * boxes. This joins the two tokens back into one before anything paints them;
 * the document text is not touched.</p>
 */
final class AstralSafeTokenMaker extends PlainTextTokenMaker {

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        Token first = super.getTokenList(text, initialTokenType, startOffset);
        for (Token token = first; token instanceof TokenImpl; token = token.getNextToken()) {
            Token next = token.getNextToken();
            if (next != null && token.length() == 1 && next.length() == 1
                    && Character.isHighSurrogate(token.charAt(0)) && Character.isLowSurrogate(next.charAt(0))
                    && next.getTextArray() == token.getTextArray()
                    && next.getTextOffset() == token.getTextOffset() + 1) {
                TokenImpl joined = (TokenImpl) token;
                joined.textCount = 2;
                joined.setNextToken(next.getNextToken());
            }
        }
        return first;
    }
}
