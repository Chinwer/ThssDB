package cn.edu.thssdb.exception;

public class InvalidExpressionException extends RuntimeException {
    @Override
    public String getMessage() {
        return "Exception: invalid expression.";
    }
}
