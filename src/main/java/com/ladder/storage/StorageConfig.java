package com.ladder.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketCorsArgs;
import io.minio.messages.CORSConfiguration;

import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    MinioClient minioClient(StorageProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    @Bean
    ApplicationRunner createBucket(MinioClient client, StorageProperties props) {
        return args -> {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
            }
            client.setBucketCors(SetBucketCorsArgs.builder()
                .bucket(props.bucket())
                .config(new CORSConfiguration(List.of(new CORSConfiguration.CORSRule(
                        List.of("*"),                    // allowedHeaders
                        // GET/HEAD: the player reads segments. PUT: the upload page
                        // sends the file straight to storage with the presigned URL,
                        // so the bytes never pass through the API. Only a signed
                        // URL can PUT; CORS just lets the browser make the call.
                        List.of("GET", "HEAD", "PUT"),
                        List.of(props.corsOrigin()),     // allowedOrigins
                        List.of(),                       // exposeHeaders
                        null,                            // id
                        3600))))                         // maxAgeSeconds
                .build());
        };
    }
}
