package com.crates.crates.service;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class ImageService {

    private final MinioClient minioClient;

    @Value("${storage.bucket-url}")
    private String bucketUrl;

    public String getImageUrl(String s3ObjectKey, String extension) {
        return bucketUrl + "/" + s3ObjectKey + "." + extension;
//        return bucketUrl + "/" + s3ObjectKey;
    }
}
