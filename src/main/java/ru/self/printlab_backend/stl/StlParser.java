package ru.self.printlab_backend.stl;

import ru.self.printlab_backend.model.ModelGeometry;

import java.io.InputStream;

/**
 * Контракт парсера STL-файлов.
 */
public interface StlParser {

    ModelGeometry parse(InputStream inputStream) throws StlParseException;
}