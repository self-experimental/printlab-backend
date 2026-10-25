package ru.self.printlab_backend.stl;

import org.springframework.stereotype.Component;
import ru.self.printlab_backend.model.ModelGeometry;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads bounded binary STL one triangle at a time, rejecting truncation and trailing data. */
@Component
public class BinaryStlParser implements StlParser {
    @Override
    public ModelGeometry parse(InputStream inputStream) {
        try {
            DataInputStream input = new DataInputStream(inputStream);
            input.readFully(new byte[80]);
            long count = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (count == 0) throw new StlParseException(StlUploadError.EMPTY_MODEL);
            if (count > StlLimits.MAX_TRIANGLES) throw new StlParseException(StlUploadError.TOO_MANY_TRIANGLES);
            StlGeometryAccumulator geometry = new StlGeometryAccumulator();
            byte[] triangle = new byte[50];
            for (int i = 0; i < count; i++) {
                input.readFully(triangle);
                ByteBuffer buffer = ByteBuffer.wrap(triangle).order(ByteOrder.LITTLE_ENDIAN);
                for (int axis = 0; axis < 3; axis++) {
                    if (!Float.isFinite(buffer.getFloat())) {
                        throw new StlParseException("STL содержит недопустимую нормаль");
                    }
                }
                double[] points = new double[9];
                for (int coordinate = 0; coordinate < points.length; coordinate++) points[coordinate] = buffer.getFloat();
                geometry.add(points);
            }
            if (input.read() != -1) throw new StlParseException("После треугольников STL есть лишние данные");
            return geometry.result();
        } catch (EOFException e) {
            throw new StlParseException("Файл STL обрывается раньше ожидаемого", e);
        } catch (IOException e) {
            throw new StlParseException("Не удалось прочитать STL", e);
        }
    }
}
