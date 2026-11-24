package com.gradle.quarkus.extension.normalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal JSON reader and canonical writer, enough for the configuration files Quarkus generates for
 * {@code native-image}.
 *
 * <p>Canonical means object keys sorted and array elements sorted by their own canonical form. Those files are sets of
 * registrations, where order carries no meaning, so ordering them makes two builds that registered the same things
 * produce the same bytes. Numbers are kept as they were written rather than re-formatted.
 *
 * <p>Deliberately not a general purpose JSON library: anything it cannot parse is reported by an exception, and the
 * caller leaves the file alone.
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    /**
     * A number kept verbatim, so that canonicalizing never re-formats one.
     */
    private static final class Num {
        private final String lexeme;

        private Num(String lexeme) {
            this.lexeme = lexeme;
        }
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.index != json.text.length()) {
            throw new IllegalArgumentException("Trailing content at index " + json.index);
        }
        return value;
    }

    static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List) {
            List<String> elements = new ArrayList<>();
            for (Object element : (List<?>) value) {
                elements.add(canonical(element));
            }
            java.util.Collections.sort(elements);
            out.append('[');
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(elements.get(i));
            }
            out.append(']');
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Num) {
            out.append(((Num) value).lexeme);
        } else {
            out.append(value);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private Object readValue() {
        char c = peek();
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> members = new TreeMap<>();
        index++; // {
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return members;
        }
        while (true) {
            skipWhitespace();
            String name = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new IllegalArgumentException("Expected ':' at index " + index);
            }
            index++;
            skipWhitespace();
            members.put(name, readValue());
            skipWhitespace();
            char c = peek();
            index++;
            if (c == '}') {
                return members;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' at index " + (index - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> elements = new ArrayList<>();
        index++; // [
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return elements;
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue());
            skipWhitespace();
            char c = peek();
            index++;
            if (c == ']') {
                return elements;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' at index " + (index - 1));
            }
        }
    }

    private String readString() {
        if (peek() != '"') {
            throw new IllegalArgumentException("Expected '\"' at index " + index);
        }
        index++;
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"': value.append('"'); break;
                case '\\': value.append('\\'); break;
                case '/': value.append('/'); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'u':
                    value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    index += 4;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown escape '\\" + escaped + "' at index " + (index - 1));
            }
        }
    }

    private Num readNumber() {
        int start = index;
        while (index < text.length() && "+-.eE0123456789".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        if (start == index) {
            throw new IllegalArgumentException("Expected a value at index " + index);
        }
        return new Num(text.substring(start, index));
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("Expected '" + literal + "' at index " + index);
        }
        index += literal.length();
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of input");
        }
        return text.charAt(index);
    }

    private char next() {
        char c = peek();
        index++;
        return c;
    }

}
