package com.retos.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.retos.web.ApiException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "azure", matchIfMissing = true)
public class AzureBlobStorageService implements StorageService {

    private final BlobContainerClient container;

    public AzureBlobStorageService(
            @Value("${storage.azure.connection-string:}") String connectionString,
            @Value("${storage.azure.container:retos}") String containerName) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalStateException(
                    "Falta AZURE_STORAGE_CONNECTION_STRING (storage.azure.connection-string). "
                            + "Defina la variable o use STORAGE_TYPE=local para desarrollo.");
        }
        BlobServiceClient service = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
        this.container = service.getBlobContainerClient(containerName);
        if (!container.exists()) {
            container.create();
        }
    }

    @Override
    public void upload(String path, InputStream content, long length) {
        BlobClient blob = container.getBlobClient(path);
        blob.upload(content, length, true);
    }

    @Override
    public InputStream download(String path) {
        BlobClient blob = container.getBlobClient(path);
        if (!blob.exists()) {
            throw ApiException.notFound("Archivo no encontrado en el almacenamiento: " + path);
        }
        return blob.openInputStream();
    }

    @Override
    public boolean exists(String path) {
        return container.getBlobClient(path).exists();
    }
}
