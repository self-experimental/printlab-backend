package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import ru.self.printlab_backend.stl.BinaryStlParser;
import ru.self.printlab_backend.stl.StlFileParser;
import ru.self.printlab_backend.stl.StlLimits;
import ru.self.printlab_backend.stl.StlParseException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class StlFileParserTest {
    static final String ASCII = "solid example\nfacet normal 0 0 1 outer loop "
            + "vertex 0 0 0 vertex 2 0 0 vertex 0 2 0 endloop endfacet\nendsolid example";
    private final StlFileParser parser = new StlFileParser(new BinaryStlParser());

    static byte[] binary() {
        ByteBuffer bytes = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("solid binary header".getBytes(StandardCharsets.US_ASCII));
        bytes.position(80);
        bytes.putInt(1);
        bytes.position(108);
        bytes.putFloat(2);
        bytes.position(124);
        bytes.putFloat(2);
        return bytes.array();
    }

    @Test
    void supportsBothEncodingsIncludingSolidBinaryHeader() {
        for (byte[] bytes : new byte[][] {ASCII.getBytes(StandardCharsets.UTF_8), binary()}) {
            var geometry = parser.parse(bytes);
            assertEquals(1, geometry.getTriangleCount());
            assertEquals(2, geometry.getBoundingBoxXmm());
            assertEquals(2, geometry.getBoundingBoxYmm());
            assertEquals(2, geometry.getSurfaceAreaMm2());
        }
    }

    @Test
    void acceptsKeywordSolidNamesBomAndWindowsLineEndings() {
        String text = "\uFEFF" + ASCII.replace("example", "facet normal endsolid").replace("\n", "\r\n");
        assertEquals(1, parser.parse(text.getBytes(StandardCharsets.UTF_8)).getTriangleCount());
    }

    @Test
    void rejectsBrokenAsciiStructureAndTrailingContent() {
        for (String text : new String[] {"", "broken", "solid x endsolid x", ASCII.replace("endloop", ""),
                ASCII.replace("vertex 2 0 0", ""), ASCII.replace("endsolid", "missing"),
                ASCII + " garbage", ASCII.replace("vertex 2", "vertex NaN"),
                ASCII.replace("vertex 2", "vertex 1e309"), ASCII.replace("vertex 2", "vertex 1e13"),
                ASCII.replace("vertex 2", "vertex 0")}) {
            assertThrows(StlParseException.class, () -> parser.parse(text.getBytes(StandardCharsets.UTF_8)), text);
        }
    }

    @Test
    void rejectsBrokenBinaryAndNonFiniteCoordinatesOrNormals() {
        byte[] bytes = binary();
        assertThrows(StlParseException.class, () -> parser.parse(Arrays.copyOf(bytes, 133)));
        assertThrows(StlParseException.class, () -> parser.parse(Arrays.copyOf(bytes, 135)));
        for (int offset : new int[] {84, 96}) {
            byte[] invalid = binary();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, Float.NaN);
            assertThrows(StlParseException.class, () -> parser.parse(invalid));
        }
    }

    @Test
    void boundsFileSizeWithoutASeparateTriangleLimit() {
        int detailedCount = 225706;
        byte[] detailed = new byte[84 + detailedCount * 50];
        ByteBuffer.wrap(detailed).order(ByteOrder.LITTLE_ENDIAN).putInt(80, detailedCount);
        byte[] triangle = Arrays.copyOfRange(binary(), 84, 134);
        for (int index = 0; index < detailedCount; index++) {
            System.arraycopy(triangle, 0, detailed, 84 + index * 50, 50);
        }
        assertEquals(detailedCount, parser.parse(detailed).getTriangleCount());
        byte[] header = new byte[84];
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(80, (StlLimits.MAX_BYTES / 50) + 1);
        assertEquals(ru.self.printlab_backend.stl.StlUploadError.FILE_TOO_LARGE,
                assertThrows(StlParseException.class, () -> new BinaryStlParser().parse(new java.io.ByteArrayInputStream(header))).error());
        assertThrows(StlParseException.class, () -> parser.parse(new byte[StlLimits.MAX_BYTES + 1]));

    }
}
