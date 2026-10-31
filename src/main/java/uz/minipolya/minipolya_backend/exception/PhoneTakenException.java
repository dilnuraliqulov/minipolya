package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/** 409: the phone a user tried to save belongs to another account (users.phone is unique). */
public class PhoneTakenException extends CodedApiException {

    public PhoneTakenException() {
        super(HttpStatus.CONFLICT, "PHONE_TAKEN", "Phone is already used by another account", null);
    }
}
