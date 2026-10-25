package ru.self.printlab_backend.stl;

import org.springframework.stereotype.Component;
import ru.self.printlab_backend.model.ModelGeometry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects binary STL by its exact length, including binary headers starting with "solid". */
@Component
public class StlFileParser implements StlParser {
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final BinaryStlParser binaryParser;

    public StlFileParser(BinaryStlParser binaryParser) {
        this.binaryParser = binaryParser;
    }

    @Override
    public ModelGeometry parse(InputStream input) {
        try {
            return parse(input.readNBytes(StlLimits.MAX_BYTES + 1));
        } catch (IOException e) {
            throw new StlParseException("Не удалось прочитать STL", e);
        }
    }

    public ModelGeometry parse(byte[] bytes) {
        if (bytes.length > StlLimits.MAX_BYTES) throw new StlParseException(StlUploadError.FILE_TOO_LARGE);
        if (bytes.length >= 84) {
            long count = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(80));
            if (84 + count * 50 == bytes.length) {
                return binaryParser.parse(new ByteArrayInputStream(bytes));
            }
        }
        String source = new String(bytes, StandardCharsets.UTF_8).strip();
        if (source.startsWith("\uFEFF")) source = source.substring(1).strip();
        Tokens tokens;
        String name;
        String next;
        int headerEnd = source.indexOf('\n');
        if (headerEnd < 0) headerEnd = source.indexOf('\r');
        if (headerEnd >= 0) {
            // A solid name may itself contain STL keywords such as "facet".
            Tokens header = new Tokens(source.substring(0, headerEnd));
            header.expect("solid");
            name = header.remainder();
            tokens = new Tokens(source.substring(headerEnd + 1));
            next = tokens.next();
        } else {
            // Also accept compact, single-line STL produced by simple generators.
            tokens = new Tokens(source);
            tokens.expect("solid");
            StringBuilder compactName = new StringBuilder();
            next = tokens.next();
            while (!next.equalsIgnoreCase("facet") && !next.equalsIgnoreCase("endsolid")) {
                if (!compactName.isEmpty()) compactName.append(' ');
                compactName.append(next);
                next = tokens.next();
            }
            name = compactName.toString();
        }
        StlGeometryAccumulator geometry = new StlGeometryAccumulator();
        while (next.equalsIgnoreCase("facet")) {
            tokens.expect("normal");
            for (int i = 0; i < 3; i++) tokens.number();
            tokens.expect("outer");
            tokens.expect("loop");
            double[] points = new double[9];
            for (int vertex = 0; vertex < 3; vertex++) {
                tokens.expect("vertex");
                for (int axis = 0; axis < 3; axis++) points[vertex * 3 + axis] = tokens.number();
            }
            tokens.expect("endloop");
            tokens.expect("endfacet");
            geometry.add(points);
            next = tokens.next();
        }
        if (!next.equalsIgnoreCase("endsolid")) throw invalid();
        String closingName = tokens.remainder();
        if (!closingName.isEmpty() && !closingName.equals(name)) throw invalid();
        return geometry.result();
    }

    private static StlParseException invalid() {
        return new StlParseException("Не удалось прочитать STL. Выберите корректный ASCII или бинарный STL");
    }

    private static final class Tokens {
        private final Matcher matcher;
        Tokens(String source) { matcher = TOKEN.matcher(source); }
        String next() {
            if (!matcher.find()) throw invalid();
            return matcher.group();
        }
        void expect(String expected) {
            if (!next().equalsIgnoreCase(expected)) throw invalid();
        }
        String remainder() {
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(matcher.group());
            }
            return result.toString();
        }
        double number() {
            String value = next();
            if (value.length() > 128 || !NUMBER.matcher(value).matches()) throw invalid();
            double result = Double.parseDouble(value);
            if (!Double.isFinite(result) || Math.abs(result) > StlLimits.MAX_COORDINATE) {
                throw new StlParseException(StlUploadError.INVALID_COORDINATES);
            }
            return result;
        }
    }
}
