package com.retos.storage;

import com.retos.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Solo para desarrollo/pruebas (STORAGE_TYPE=local). Misma estructura de rutas que Azure. */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(@Value("${storage.local.root:./storage-local}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path resolve(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("Ruta invalida");
        }
        return p;
    }

    @Override
    public void upload(String path, InputStream content, long length) {
        Path target = resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream download(String path) {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            throw ApiException.notFound("Archivo no encontrado en el almacenamiento: " + path);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }
}
