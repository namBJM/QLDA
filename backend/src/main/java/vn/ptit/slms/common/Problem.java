package vn.ptit.slms.common;

public class Problem extends RuntimeException {

    public final String code;
    public final int status;

    public Problem(String code, String message) {
        this(409, code, message);
    }

    public Problem(int status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static void require(boolean ok, String code, String message) {
        if (!ok)
            throw new Problem(code, message);
    }
}
