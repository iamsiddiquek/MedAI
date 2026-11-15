package ae.afia.core.platform;

import org.springframework.http.HttpStatus;

/** A refusal with a stable machine-readable code. Messages never carry clinical content. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public static ApiException forbidden(String code) {
        return new ApiException(HttpStatus.FORBIDDEN, code);
    }

    /** Also used for another tenant's records, so their existence is not revealed. */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code);
    }
}
