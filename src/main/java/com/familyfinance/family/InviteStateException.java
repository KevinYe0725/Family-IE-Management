package com.familyfinance.family;

import com.familyfinance.shared.ResourceConflictException;

public class InviteStateException extends ResourceConflictException {
    public InviteStateException(String code, String message) { super(code, message); }
}
