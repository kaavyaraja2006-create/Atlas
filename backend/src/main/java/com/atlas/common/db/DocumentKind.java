package com.atlas.common.db;

/** Kinds of numbered business documents and how their numbers look (SO-000001). */
public enum DocumentKind {
    ORDER("SO"), INVOICE("INV"), CREDIT_NOTE("CN"), INCIDENT("REC");

    private final String prefix;

    DocumentKind(String prefix) {
        this.prefix = prefix;
    }

    public String format(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("Document numbers start at 1");
        }
        return prefix + "-" + String.format("%06d", value);
    }
}
