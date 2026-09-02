package com.familyfinance.identity;

import java.io.IOException;

public class RegistrationRequestBodyTooLargeException extends IOException {

    public RegistrationRequestBodyTooLargeException() {
        super("Registration request body exceeds the configured limit");
    }
}
