package com.example.chapter07.thirdlevel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextDeduplicatorTest {

    @Test
    public void normalizeRemovesWhitespaceAndPunctuation() {
        assertTrue(TextDeduplicator.normalize("请 先，转账！").equals("请先转账"));
    }

    @Test
    public void emptyOrPunctuationOnlyTextIsIgnored() {
        TextDeduplicator deduplicator = new TextDeduplicator();
        assertFalse(deduplicator.shouldSend(""));
        assertFalse(deduplicator.shouldSend("  ，。！ "));
    }

    @Test
    public void equivalentTextIsOnlySentOnce() {
        TextDeduplicator deduplicator = new TextDeduplicator();
        assertTrue(deduplicator.shouldSend("请先转账"));
        assertFalse(deduplicator.shouldSend("请先 转账。"));
    }

    @Test
    public void changedTextIsSent() {
        TextDeduplicator deduplicator = new TextDeduplicator();
        assertTrue(deduplicator.shouldSend("请先转账"));
        assertTrue(deduplicator.shouldSend("请先转账到安全账户"));
    }
}
