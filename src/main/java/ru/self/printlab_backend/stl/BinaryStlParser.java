package ru.self.printlab_backend.stl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.self.printlab_backend.model.ModelGeometry;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Парсер бинарного STL. Считает объём, площадь поверхности и габариты модели.
 *
 * <p>Число треугольников берётся из недоверенного заголовка, поэтому ограничивается
 * по абсолютному значению и по ожидаемому размеру файла (арифметика {@code long}),
 * а треугольники читаются по одному небольшим буфером — без выделения памяти
 * пропорционально заголовку.</p>
 */
@Slf4j
@Component
public class BinaryStlParser implements StlParser {

    private static final int HEADER_SIZE = 80;
    private static final int TRIANGLE_SIZE = 50;
    private static final long MAX_TRIANGLES = 5_000_000L;
    private static final long MAX_BYTES = 100L * 1024L * 1024L;

    @Override
    public ModelGeometry parse(InputStream inputStream) {
        try (DataInputStream in = new DataInputStream(inputStream)) {
            byte[] header = new byte[HEADER_SIZE];
            in.readFully(header);

            int triangleCount = Integer.reverseBytes(in.readInt());
            if (triangleCount <= 0 || triangleCount > MAX_TRIANGLES) {
                throw new StlParseException(
                        "Некорректное число треугольников: " + triangleCount);
            }

            long expectedBytes = (long) triangleCount * TRIANGLE_SIZE;
            if (expectedBytes > MAX_BYTES) {
                throw new StlParseException(
                        "STL слишком велик: " + expectedBytes + " байт");
            }

            byte[] triangleBuffer = new byte[TRIANGLE_SIZE];

            double signedVolume = 0;
            double area = 0;

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < triangleCount; i++) {
                in.readFully(triangleBuffer);
                ByteBuffer bb = ByteBuffer
                        .wrap(triangleBuffer)
                        .order(ByteOrder.LITTLE_ENDIAN);

                bb.getFloat(); // normal x
                bb.getFloat(); // normal y
                bb.getFloat(); // normal z

                double x1 = bb.getFloat();
                double y1 = bb.getFloat();
                double z1 = bb.getFloat();
                double x2 = bb.getFloat();
                double y2 = bb.getFloat();
                double z2 = bb.getFloat();
                double x3 = bb.getFloat();
                double y3 = bb.getFloat();
                double z3 = bb.getFloat();
                bb.getShort(); // attribute byte count

                signedVolume += signedTetraVolume(x1, y1, z1, x2, y2, z2, x3, y3, z3);
                area += triangleArea(x1, y1, z1, x2, y2, z2, x3, y3, z3);

                minX = Math.min(minX, Math.min(x1, Math.min(x2, x3)));
                minY = Math.min(minY, Math.min(y1, Math.min(y2, y3)));
                minZ = Math.min(minZ, Math.min(z1, Math.min(z2, z3)));
                maxX = Math.max(maxX, Math.max(x1, Math.max(x2, x3)));
                maxY = Math.max(maxY, Math.max(y1, Math.max(y2, y3)));
                maxZ = Math.max(maxZ, Math.max(z1, Math.max(z2, z3)));
            }

            log.debug("STL разобран: треугольников={}, объём={} мм^3",
                    triangleCount, Math.abs(signedVolume));

            return ModelGeometry
                    .builder()
                    .volumeMm3(Math.abs(signedVolume))
                    .surfaceAreaMm2(area)
                    .boundingBoxXmm(maxX - minX)
                    .boundingBoxYmm(maxY - minY)
                    .boundingBoxZmm(maxZ - minZ)
                    .triangleCount(triangleCount)
                    .build();
        } catch (EOFException e) {
            throw new StlParseException(
                    "Файл STL обрывается раньше ожидаемого", e);
        } catch (IOException e) {
            throw new StlParseException(
                    "Ошибка чтения STL: " + e.getMessage(), e);
        }
    }

    private double signedTetraVolume(double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     double x3, double y3, double z3) {
        return (x1 * (y2 * z3 - y3 * z2)
                - y1 * (x2 * z3 - x3 * z2)
                + z1 * (x2 * y3 - x3 * y2)) / 6.0;
    }

    private double triangleArea(double x1, double y1, double z1,
                                double x2, double y2, double z2,
                                double x3, double y3, double z3) {
        double ax = x2 - x1;
        double ay = y2 - y1;
        double az = z2 - z1;
        double bx = x3 - x1;
        double by = y3 - y1;
        double bz = z3 - z1;
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}