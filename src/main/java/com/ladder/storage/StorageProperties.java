package com.ladder.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ladder.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}
