package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.stl.BinaryStlParser;
import ru.self.printlab_backend.stl.StlParseException;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BinaryStlParserTest {

    @Test
    void parsesBinaryStlAndComputesBoundingBox() {
        byte[] stl = buildSingleTriangleStl();
        ModelGeometry geometry = new BinaryStlParser().parse(new ByteArrayInputStream(stl));

        assertEquals(1, geometry.getTriangleCount());
        assertEquals(10.0, geometry.getBoundingBoxXmm(), 1e-6);
        assertEquals(20.0, geometry.getBoundingBoxYmm(), 1e-6);
        assertEquals(30.0, geometry.getBoundingBoxZmm(), 1e-6);
        assertTrue(geometry.getSurfaceAreaMm2() > 0);
    }

    @Test
    void throwsOnHugeTriangleCountWithoutAllocatingMemory() {
        ByteBuffer bb = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[80]);
        bb.putInt(40_000_000); // 40 млн треугольников = ~2 ГБ

        assertThrows(StlParseException.class, () -> 
            new BinaryStlParser().parse(new ByteArrayInputStream(bb.array()))
        );
    }

    @Test
    void throwsOnIntegerOverflowInTriangleCount() {
        ByteBuffer bb = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[80]);
        bb.putInt(42_949_673); // Переполнение int при * 50

        assertThrows(StlParseException.class, () -> 
            new BinaryStlParser().parse(new ByteArrayInputStream(bb.array()))
        );
    }

    private byte[] buildSingleTriangleStl() {
        ByteBuffer bb = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[80]);
        bb.putInt(1);
        bb.putFloat(0).putFloat(0).putFloat(0);
        bb.putFloat(0).putFloat(0).putFloat(0);
        bb.putFloat(10).putFloat(0).putFloat(0);
        bb.putFloat(0).putFloat(20).putFloat(30);
        bb.putShort((short) 0);
        return bb.array();
    }
}