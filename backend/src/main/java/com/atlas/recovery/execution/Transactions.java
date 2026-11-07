package com.atlas.recovery.execution;

import java.util.function.Supplier;

/** Runs work in one database transaction: all of it commits, or none of it does. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
