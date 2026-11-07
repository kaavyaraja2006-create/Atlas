package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.Objects;

/** Value helpers: money and quantities are compared by numeric value, never by scale. */
public final class Values {

    private Values() {
    }

    public static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        return Objects.equals(a, b);
    }

    public static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            return new BigDecimal(s);
        }
        throw new IllegalArgumentException("Not a number: " + value);
    }
}
