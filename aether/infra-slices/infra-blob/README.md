# Aether Infrastructure: Blob Storage

S3-like object storage service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-blob</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides an S3-compatible object storage API with in-memory implementation.

### Key Features

- Bucket management (create, delete, list)
- Object operations (put, get, delete, copy)
- Metadata support (content type, custom metadata)
- Prefix-based listing

### Quick Start

```java
var storage = BlobStorageService.blobStorageService();

// Create bucket
storage.createBucket("images").await();

// Upload blob
var metadata = storage.put(
    "images",
    "photos/vacation.jpg",
    imageBytes,
    "image/jpeg"
).await();

// Upload with custom metadata
storage.put(
    "images",
    "photos/profile.jpg",
    imageBytes,
    "image/jpeg",
    Map.of("author", "alice", "year", "2024")
).await();

// Download blob
var content = storage.get("images", "photos/vacation.jpg").await();
content.onPresent(blob -> {
    byte[] data = blob.data();
    String contentType = blob.metadata().contentType();
});

// List with prefix
var photos = storage.listWithPrefix("images", "photos/").await();

// Copy blob
storage.copy("images", "photos/vacation.jpg", "backup", "vacation.jpg").await();
```

### API Summary

| Category | Methods |
|----------|---------|
| Buckets | `createBucket`, `deleteBucket`, `bucketExists`, `listBuckets` |
| Objects | `put`, `get`, `getMetadata`, `delete`, `exists` |
| Listing | `list`, `listWithPrefix` |
| Copy | `copy` |

### Data Types

```java
record BlobMetadata(String key, String contentType, long size,
                    Instant createdAt, Map<String, String> customMetadata) {}

record BlobContent(BlobMetadata metadata, byte[] data) {}
```

## License

Apache License 2.0
