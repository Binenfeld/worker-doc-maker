package utils;

/**
 * A problem the person running the program can fix, such as a missing input file or a wrong setting.
 * The message says what went wrong and what to do about it; {@code Main} prints it without a stack trace.
 */
public class UserFacingException extends Exception {
    public UserFacingException(String message) {
        super(message);
    }

    public UserFacingException(String message, Throwable cause) {
        super(message, cause);
    }
}
