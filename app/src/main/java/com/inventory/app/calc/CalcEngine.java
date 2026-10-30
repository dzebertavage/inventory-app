package com.inventory.app.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A basic calculator modelled on the Pixel Calculator's basic keys:
 * AC, ( ), %, ÷ × − +, digits, decimal point, backspace, =.
 * Pure Java (no Android) so it can be tested off-device.
 */
public final class CalcEngine {

    public static final char PLUS = '+', MINUS = '−', TIMES = '×', DIVIDE = '÷';
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    public static final int HISTORY_MAX = 100;

    public static final class Entry {
        public final String expression, result;
        public Entry(String expression, String result) { this.expression = expression; this.result = result; }
    }

    private final StringBuilder expr = new StringBuilder();
    private String lastAnswer;          // plain number text of the last "=" result
    private boolean justEvaluated;      // display currently shows a result
    private boolean error;
    public final List<Entry> history = new ArrayList<>();   // oldest first

    // ------------------------------------------------------------ display

    /** Text for the calculator display. */
    public String display() {
        if (error) return "Error";
        if (justEvaluated && lastAnswer != null) return lastAnswer;
        return expr.toString();
    }

    public boolean showingResult() { return justEvaluated && !error; }

    public String lastAnswer() { return lastAnswer; }

    // ------------------------------------------------------------ keys

    public void digit(char d) {
        startFreshIfNeeded(false);
        char last = lastChar();
        if (last == ')' || last == '%') expr.append(TIMES);
        // avoid leading zeros like "007"
        String num = currentNumber();
        if (num.equals("0")) expr.setLength(expr.length() - 1);
        expr.append(d);
    }

    public void point() {
        startFreshIfNeeded(false);
        String num = currentNumber();
        if (num.contains(".")) return;
        char last = lastChar();
        if (last == ')' || last == '%') expr.append(TIMES);
        if (num.isEmpty()) expr.append('0');
        expr.append('.');
    }

    public void operator(char op) {
        error = false;
        if (justEvaluated) {
            justEvaluated = false;
            expr.setLength(0);
            if (lastAnswer != null) expr.append(answerAsExpr());
        }
        char last = lastChar();
        if (expr.length() == 0 || last == '(') {
            if (op == MINUS) expr.append(op);          // unary minus
            return;
        }
        if (isOperator(last)) {
            // "5×−" style unary minus after × or ÷
            if (op == MINUS && (last == TIMES || last == DIVIDE)) { expr.append(op); return; }
            // replace the trailing operator(s)
            while (expr.length() > 0 && isOperator(lastChar())) expr.setLength(expr.length() - 1);
            if (expr.length() == 0 || lastChar() == '(') { if (op == MINUS) expr.append(op); return; }
        }
        if (last == '.') expr.append('0');
        expr.append(op);
    }

    public void percent() {
        if (justEvaluated) continueFromAnswer();
        char last = lastChar();
        if (Character.isDigit(last) || last == ')' || last == '.') {
            if (last == '.') expr.append('0');
            expr.append('%');
        }
    }

    /** The single "( )" key: closes a bracket when one is open and something closable precedes it. */
    public void paren() {
        if (justEvaluated) startFreshIfNeeded(true);
        error = false;
        char last = lastChar();
        int open = 0;
        for (int i = 0; i < expr.length(); i++) {
            if (expr.charAt(i) == '(') open++;
            else if (expr.charAt(i) == ')') open--;
        }
        boolean closable = Character.isDigit(last) || last == ')' || last == '%' || last == '.';
        if (open > 0 && closable) {
            if (last == '.') expr.append('0');
            expr.append(')');
        } else {
            if (closable) expr.append(TIMES);
            expr.append('(');
        }
    }

    public void backspace() {
        if (error) { error = false; return; }
        if (justEvaluated) { clear(); return; }
        if (expr.length() > 0) expr.setLength(expr.length() - 1);
    }

    /** AC: clears the current calculation (history is kept). */
    public void clear() {
        expr.setLength(0);
        justEvaluated = false;
        error = false;
    }

    /** "=": evaluates; returns true on success. */
    public boolean equals() {
        if (justEvaluated || expr.length() == 0) return justEvaluated;
        // a trailing operator ("2+") is ignored, like the Pixel calculator
        while (expr.length() > 0 && isOperator(lastChar())) expr.setLength(expr.length() - 1);
        if (expr.length() == 0) return false;
        String text = expr.toString();
        BigDecimal v = evaluate(text);
        if (v == null) { error = true; return false; }
        // close any open brackets in the stored expression for history
        int open = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '(') open++; else if (text.charAt(i) == ')') open--;
        }
        StringBuilder shown = new StringBuilder(text);
        for (int i = 0; i < open; i++) shown.append(')');
        lastAnswer = format(v);
        history.add(new Entry(shown.toString(), lastAnswer));
        while (history.size() > HISTORY_MAX) history.remove(0);
        justEvaluated = true;
        error = false;
        return true;
    }

    /** Inserts a number (e.g. tapped from history) into the current calculation. */
    public void insertNumber(String number) {
        if (justEvaluated) { justEvaluated = false; expr.setLength(0); }
        error = false;
        char last = lastChar();
        if (Character.isDigit(last) || last == '.') {
            // replace the number being typed
            while (expr.length() > 0 && (Character.isDigit(lastChar()) || lastChar() == '.')) expr.setLength(expr.length() - 1);
            last = lastChar();
        }
        if (last == ')' || last == '%') expr.append(TIMES);
        if (number.startsWith("-")) {
            if (expr.length() == 0 || lastChar() == '(' || isOperator(lastChar())) {
                expr.append('(').append(MINUS).append(number.substring(1)).append(')');
                return;
            }
        }
        expr.append(number);
    }

    /**
     * The answer to hand to the inventory field: evaluates first if needed, then rounds to 2 decimals.
     * Returns null if there is nothing valid to use.
     */
    public String answerForUse() {
        if (!justEvaluated && expr.length() > 0) {
            if (!equals()) return null;
        }
        if (lastAnswer == null || error) return null;
        BigDecimal d = new BigDecimal(lastAnswer).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.scale() < 0) d = d.setScale(0);
        return d.toPlainString();
    }

    // ------------------------------------------------------------ state save/restore

    public String saveExpression() { return expr.toString(); }
    public boolean saveJustEvaluated() { return justEvaluated; }

    public void restore(String expression, String answer, boolean evaluated) {
        expr.setLength(0);
        if (expression != null) expr.append(expression);
        lastAnswer = answer;
        justEvaluated = evaluated && answer != null;
        error = false;
    }

    // ------------------------------------------------------------ evaluation

    /** Evaluates an expression string; returns null if it is incomplete or invalid (e.g. ÷ 0). */
    public static BigDecimal evaluate(String s) {
        try {
            Parser p = new Parser(s);
            BigDecimal v = p.expression();
            p.skipClosers();
            if (p.pos != p.s.length()) return null;
            return v;
        } catch (ArithmeticException | IllegalStateException | NumberFormatException e) {
            return null;
        }
    }

    public static String format(BigDecimal v) {
        BigDecimal r = v.round(MC).stripTrailingZeros();
        if (r.scale() < 0) r = r.setScale(0);
        String out = r.toPlainString();
        return out.equals("-0") ? "0" : out;
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        char peek() { return pos < s.length() ? s.charAt(pos) : 0; }

        BigDecimal expression() {
            BigDecimal v = term();
            while (true) {
                char c = peek();
                if (c == PLUS) { pos++; v = v.add(term(), MC); }
                else if (c == MINUS) { pos++; v = v.subtract(term(), MC); }
                else return v;
            }
        }

        BigDecimal term() {
            BigDecimal v = unary();
            while (true) {
                char c = peek();
                if (c == TIMES) { pos++; v = v.multiply(unary(), MC); }
                else if (c == DIVIDE) {
                    pos++;
                    BigDecimal d = unary();
                    if (d.signum() == 0) throw new ArithmeticException("divide by zero");
                    v = v.divide(d, MC);
                }
                else if (c == '(' || Character.isDigit(c) || c == '.') { v = v.multiply(unary(), MC); }   // implicit ×
                else return v;
            }
        }

        BigDecimal unary() {
            char c = peek();
            if (c == MINUS) { pos++; return unary().negate(); }
            if (c == PLUS) { pos++; return unary(); }
            return postfix();
        }

        BigDecimal postfix() {
            BigDecimal v = primary();
            while (peek() == '%') { pos++; v = v.divide(BigDecimal.valueOf(100), MC); }
            return v;
        }

        BigDecimal primary() {
            char c = peek();
            if (c == '(') {
                pos++;
                BigDecimal v = expression();
                if (peek() == ')') pos++;
                else if (pos != s.length()) throw new IllegalStateException("bad bracket");
                return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalStateException("number expected");
            String num = s.substring(start, pos);
            if (num.equals(".")) throw new IllegalStateException("bad number");
            return new BigDecimal(num.endsWith(".") ? num + "0" : num);
        }

        void skipClosers() { /* open brackets are auto-closed; nothing to do */ }
    }

    // ------------------------------------------------------------ helpers

    private void startFreshIfNeeded(boolean force) {
        error = false;
        if (justEvaluated || force) { justEvaluated = false; expr.setLength(0); }
    }

    private void continueFromAnswer() {
        justEvaluated = false;
        expr.setLength(0);
        if (lastAnswer != null) expr.append(answerAsExpr());
    }

    private String answerAsExpr() { return lastAnswer.startsWith("-") ? MINUS + lastAnswer.substring(1) : lastAnswer; }

    private char lastChar() { return expr.length() == 0 ? 0 : expr.charAt(expr.length() - 1); }

    private static boolean isOperator(char c) { return c == PLUS || c == MINUS || c == TIMES || c == DIVIDE; }

    /** Digits/point at the end of the expression. */
    private String currentNumber() {
        int i = expr.length();
        while (i > 0 && (Character.isDigit(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) i--;
        return expr.substring(i);
    }
}
