package com.atlas.recovery.execution;

/** A workflow rule refused the request. The web layer maps {@link Kind} to an HTTP status. */
public final class RecoveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 404 */ NOT_FOUND,
        /** 409: the data the plan was computed from has changed */ STALE_PLAN,
        /** 409: the plan needs approval first */ NOT_APPROVED,
        /** 403: the plan's author cannot approve it */ SELF_APPROVAL,
        /** 409: not allowed in the plan's or step's current status */ ILLEGAL_STATE,
        /** 400 */ INVALID
    }

    private final Kind kind;

    public RecoveryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
