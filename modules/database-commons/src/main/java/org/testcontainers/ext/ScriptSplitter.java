package org.testcontainers.ext;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.testcontainers.ext.ScriptScanner.Lexem;

import java.util.List;

/**
 * Performs splitting of an SQL script into statements including
 * basic clean-up.
 */
@RequiredArgsConstructor
class ScriptSplitter {

    private final ScriptScanner scanner;

    private final List<String> statements;

    private final StringBuilder sb = new StringBuilder();

    /**
     * Tracks the last significant (non-whitespace, non-comment) lexem that was appended,
     * so that whitespace-shrinking can tell whether it is sitting between two adjacent
     * quoted string literals.
     */
    private Lexem lastSignificantLexem;

    /**
     * True if the whitespace run currently being shrunk contains a newline. Some databases
     * (e.g. PostgreSQL) only treat two adjacent string literals as implicitly concatenated
     * when they are separated by a newline, so that distinction has to survive shrinking.
     */
    private boolean pendingWhitespaceHasNewline;

    /**
     * Standard parsing:
     * 1. Remove comments
     * 2. Shrink whitespace and eols
     * 3. Split on separator
     */
    void split() {
        Lexem l;
        while ((l = scanner.next()) != Lexem.EOF) {
            switch (l) {
                case SEPARATOR:
                    flushStringBuilder();
                    resetAdjacencyTracking();
                    break;
                case COMMENT:
                    //skip
                    break;
                case WHITESPACE:
                    if (scanner.getCurrentMatch().indexOf('\n') >= 0) {
                        pendingWhitespaceHasNewline = true;
                    }
                    if (sb.length() == 0 || sb.charAt(sb.length() - 1) != ' ') {
                        sb.append(' ');
                    }
                    break;
                case IDENTIFIER:
                    appendMatch();
                    if ("begin".equalsIgnoreCase(scanner.getCurrentMatch())) {
                        compoundStatement(false);
                        flushStringBuilder();
                        resetAdjacencyTracking();
                        break;
                    }
                    lastSignificantLexem = l;
                    pendingWhitespaceHasNewline = false;
                    break;
                case QUOTED_STRING:
                    if (
                        lastSignificantLexem == Lexem.QUOTED_STRING &&
                        pendingWhitespaceHasNewline &&
                        sb.length() > 0 &&
                        sb.charAt(sb.length() - 1) == ' '
                    ) {
                        // Two adjacent string literals with only shrunk whitespace between them would
                        // no longer be valid syntax for some databases; restore the newline that makes
                        // them concatenate rather than collide (see #11206).
                        sb.setCharAt(sb.length() - 1, '\n');
                    }
                    appendMatch();
                    lastSignificantLexem = l;
                    pendingWhitespaceHasNewline = false;
                    break;
                default:
                    appendMatch();
                    lastSignificantLexem = l;
                    pendingWhitespaceHasNewline = false;
            }
        }
        flushStringBuilder();
    }

    private void resetAdjacencyTracking() {
        lastSignificantLexem = null;
        pendingWhitespaceHasNewline = false;
    }

    /**
     * Compound statement ('create procedure') mode:
     * 1. Do not remove comments
     * 2. Do not shrink whitespace
     * 3. Do not split on separators
     * 3. This mode can be recursive
     */
    private void compoundStatement(boolean recursive) {
        Lexem l;
        while ((l = scanner.next()) != Lexem.EOF) {
            appendMatch();
            if (Lexem.IDENTIFIER.equals(l)) {
                if ("begin".equalsIgnoreCase(scanner.getCurrentMatch())) {
                    compoundStatement(true);
                } else if ("end".equalsIgnoreCase(scanner.getCurrentMatch())) {
                    if (endOfBlock(recursive)) {
                        return;
                    }
                }
            }
        }
        flushStringBuilder();
    }

    private boolean endOfBlock(boolean recursive) {
        Lexem l;
        StringBuilder temporary = new StringBuilder();
        while ((l = scanner.next()) != Lexem.EOF) {
            switch (l) {
                case COMMENT:
                case WHITESPACE:
                    temporary.append(scanner.getCurrentMatch());
                    break;
                case SEPARATOR:
                    //Only whitespace and comments preceded the separator: true end of block
                    //If it's an internal block, append everything
                    if (recursive) {
                        sb.append(temporary);
                        appendMatch();
                    }
                    return true;
                default:
                    // Semicolon is not recognized as separator: this means that a custom
                    // separator is used. Still, 'END;' should be a valid end of block
                    if (";".equals(scanner.getCurrentMatch())) {
                        if (recursive) {
                            sb.append(temporary);
                        }
                        appendMatch();
                        return true;
                    }
                    sb.append(temporary);
                    appendMatch();
                    return false;
            }
        }
        return true;
    }

    private void appendMatch() {
        sb.append(scanner.getCurrentMatch());
    }

    private void flushStringBuilder() {
        final String s = sb.toString().trim();
        if (StringUtils.isNotEmpty(s)) {
            statements.add(s);
        }
        sb.setLength(0);
    }
}
