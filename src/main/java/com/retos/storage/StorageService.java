package com.retos.storage;

import java.io.InputStream;

/**
 * Almacenamiento de archivos del reto (Storage Account en Azure).
 * Rutas usadas:
 *  - plantillas/{tipo}/{nivel}.zip            carpeta de codigo que descarga el candidato
 *  - {usuario}/{tipo}/reto-{id}/entrega.zip   carpeta de codigo que carga el candidato
 */
public interface StorageService {

    void upload(String path, InputStream content, long length);

    /** @throws com.retos.web.ApiException 404 si no existe */
    InputStream download(String path);

    boolean exists(String path);
}
