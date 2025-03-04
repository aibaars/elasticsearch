/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.repositories.s3;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.internal.MD5DigestCalculatingInputStream;
import com.amazonaws.util.Base16;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.apache.http.HttpStatus;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.OperationPurpose;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.lucene.store.ByteArrayIndexInput;
import org.elasticsearch.common.lucene.store.InputStreamIndexInput;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.DeterministicTaskQueue;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.repositories.RepositoriesMetrics;
import org.elasticsearch.repositories.blobstore.AbstractBlobContainerRetriesTestCase;
import org.elasticsearch.repositories.blobstore.BlobStoreTestUtil;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.repositories.blobstore.BlobStoreTestUtil.randomNonDataPurpose;
import static org.elasticsearch.repositories.blobstore.BlobStoreTestUtil.randomPurpose;
import static org.elasticsearch.repositories.s3.S3ClientSettings.DISABLE_CHUNKED_ENCODING;
import static org.elasticsearch.repositories.s3.S3ClientSettings.ENDPOINT_SETTING;
import static org.elasticsearch.repositories.s3.S3ClientSettings.MAX_RETRIES_SETTING;
import static org.elasticsearch.repositories.s3.S3ClientSettings.READ_TIMEOUT_SETTING;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * This class tests how a {@link S3BlobContainer} and its underlying AWS S3 client are retrying requests when reading or writing blobs.
 */
@SuppressForbidden(reason = "use a http server")
public class S3BlobContainerRetriesTests extends AbstractBlobContainerRetriesTestCase {

    private S3Service service;

    @Before
    public void setUp() throws Exception {
        service = new S3Service(Mockito.mock(Environment.class), Settings.EMPTY);
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        IOUtils.close(service);
        super.tearDown();
    }

    @Override
    protected String downloadStorageEndpoint(BlobContainer container, String blob) {
        return "/bucket/" + container.path().buildAsString() + blob;
    }

    @Override
    protected String bytesContentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    protected Class<? extends Exception> unresponsiveExceptionType() {
        return SdkClientException.class;
    }

    @Override
    protected BlobContainer createBlobContainer(
        final @Nullable Integer maxRetries,
        final @Nullable TimeValue readTimeout,
        final @Nullable Boolean disableChunkedEncoding,
        final @Nullable ByteSizeValue bufferSize
    ) {
        final Settings.Builder clientSettings = Settings.builder();
        final String clientName = randomAlphaOfLength(5).toLowerCase(Locale.ROOT);

        final InetSocketAddress address = httpServer.getAddress();
        final String endpoint = "http://" + InetAddresses.toUriString(address.getAddress()) + ":" + address.getPort();
        clientSettings.put(ENDPOINT_SETTING.getConcreteSettingForNamespace(clientName).getKey(), endpoint);

        if (maxRetries != null) {
            clientSettings.put(MAX_RETRIES_SETTING.getConcreteSettingForNamespace(clientName).getKey(), maxRetries);
        }
        if (readTimeout != null) {
            clientSettings.put(READ_TIMEOUT_SETTING.getConcreteSettingForNamespace(clientName).getKey(), readTimeout);
        }
        if (disableChunkedEncoding != null) {
            clientSettings.put(DISABLE_CHUNKED_ENCODING.getConcreteSettingForNamespace(clientName).getKey(), disableChunkedEncoding);
        }

        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString(
            S3ClientSettings.ACCESS_KEY_SETTING.getConcreteSettingForNamespace(clientName).getKey(),
            "test_access_key"
        );
        secureSettings.setString(
            S3ClientSettings.SECRET_KEY_SETTING.getConcreteSettingForNamespace(clientName).getKey(),
            "test_secret_key"
        );
        clientSettings.setSecureSettings(secureSettings);
        service.refreshAndClearCache(S3ClientSettings.load(clientSettings.build()));

        final RepositoryMetadata repositoryMetadata = new RepositoryMetadata(
            "repository",
            S3Repository.TYPE,
            Settings.builder().put(S3Repository.CLIENT_NAME.getKey(), clientName).build()
        );

        return new S3BlobContainer(
            randomBoolean() ? BlobPath.EMPTY : BlobPath.EMPTY.add("foo"),
            new S3BlobStore(
                service,
                "bucket",
                S3Repository.SERVER_SIDE_ENCRYPTION_SETTING.getDefault(Settings.EMPTY),
                bufferSize == null ? S3Repository.BUFFER_SIZE_SETTING.getDefault(Settings.EMPTY) : bufferSize,
                S3Repository.CANNED_ACL_SETTING.getDefault(Settings.EMPTY),
                S3Repository.STORAGE_CLASS_SETTING.getDefault(Settings.EMPTY),
                repositoryMetadata,
                BigArrays.NON_RECYCLING_INSTANCE,
                new DeterministicTaskQueue().getThreadPool(),
                RepositoriesMetrics.NOOP
            )
        ) {
            @Override
            public InputStream readBlob(OperationPurpose purpose, String blobName) throws IOException {
                return new AssertingInputStream(super.readBlob(purpose, blobName), blobName);
            }

            @Override
            public InputStream readBlob(OperationPurpose purpose, String blobName, long position, long length) throws IOException {
                return new AssertingInputStream(super.readBlob(purpose, blobName, position, length), blobName, position, length);
            }
        };
    }

    public void testWriteBlobWithRetries() throws Exception {
        final int maxRetries = randomInt(5);
        final CountDown countDown = new CountDown(maxRetries + 1);

        final BlobContainer blobContainer = createBlobContainer(maxRetries, null, true, null);

        final byte[] bytes = randomBlobContent();
        httpServer.createContext(downloadStorageEndpoint(blobContainer, "write_blob_max_retries"), exchange -> {
            if ("PUT".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getQuery() == null) {
                if (countDown.countDown()) {
                    final BytesReference body = Streams.readFully(exchange.getRequestBody());
                    if (Objects.deepEquals(bytes, BytesReference.toBytes(body))) {
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, -1);
                    } else {
                        exchange.sendResponseHeaders(HttpStatus.SC_BAD_REQUEST, -1);
                    }
                    exchange.close();
                    return;
                }

                if (randomBoolean()) {
                    if (randomBoolean()) {
                        org.elasticsearch.core.Streams.readFully(
                            exchange.getRequestBody(),
                            new byte[randomIntBetween(1, Math.max(1, bytes.length - 1))]
                        );
                    } else {
                        Streams.readFully(exchange.getRequestBody());
                        exchange.sendResponseHeaders(
                            randomFrom(
                                HttpStatus.SC_INTERNAL_SERVER_ERROR,
                                HttpStatus.SC_BAD_GATEWAY,
                                HttpStatus.SC_SERVICE_UNAVAILABLE,
                                HttpStatus.SC_GATEWAY_TIMEOUT
                            ),
                            -1
                        );
                    }
                }
                exchange.close();
            }
        });
        try (InputStream stream = new InputStreamIndexInput(new ByteArrayIndexInput("desc", bytes), bytes.length)) {
            blobContainer.writeBlob(randomPurpose(), "write_blob_max_retries", stream, bytes.length, false);
        }
        assertThat(countDown.isCountedDown(), is(true));
    }

    public void testWriteBlobWithReadTimeouts() {
        final byte[] bytes = randomByteArrayOfLength(randomIntBetween(10, 128));
        final TimeValue readTimeout = TimeValue.timeValueMillis(randomIntBetween(100, 500));
        final BlobContainer blobContainer = createBlobContainer(1, readTimeout, true, null);

        // HTTP server does not send a response
        httpServer.createContext(downloadStorageEndpoint(blobContainer, "write_blob_timeout"), exchange -> {
            if (randomBoolean()) {
                if (randomBoolean()) {
                    org.elasticsearch.core.Streams.readFully(exchange.getRequestBody(), new byte[randomIntBetween(1, bytes.length - 1)]);
                } else {
                    Streams.readFully(exchange.getRequestBody());
                }
            }
        });

        Exception exception = expectThrows(IOException.class, () -> {
            try (InputStream stream = new InputStreamIndexInput(new ByteArrayIndexInput("desc", bytes), bytes.length)) {
                blobContainer.writeBlob(randomPurpose(), "write_blob_timeout", stream, bytes.length, false);
            }
        });
        assertThat(
            exception.getMessage().toLowerCase(Locale.ROOT),
            containsString("unable to upload object [" + blobContainer.path().buildAsString() + "write_blob_timeout] using a single upload")
        );

        assertThat(exception.getCause(), instanceOf(SdkClientException.class));
        assertThat(exception.getCause().getMessage().toLowerCase(Locale.ROOT), containsString("read timed out"));

        assertThat(exception.getCause().getCause(), instanceOf(SocketTimeoutException.class));
        assertThat(exception.getCause().getCause().getMessage().toLowerCase(Locale.ROOT), containsString("read timed out"));
    }

    public void testWriteLargeBlob() throws Exception {
        final boolean useTimeout = rarely();
        final TimeValue readTimeout = useTimeout ? TimeValue.timeValueMillis(randomIntBetween(100, 500)) : null;
        final ByteSizeValue bufferSize = new ByteSizeValue(5, ByteSizeUnit.MB);
        final BlobContainer blobContainer = createBlobContainer(null, readTimeout, true, bufferSize);

        final int parts = randomIntBetween(1, 5);
        final long lastPartSize = randomLongBetween(10, 512);
        final long blobSize = (parts * bufferSize.getBytes()) + lastPartSize;

        final int nbErrors = 2; // we want all requests to fail at least once
        final CountDown countDownInitiate = new CountDown(nbErrors);
        final AtomicInteger countDownUploads = new AtomicInteger(nbErrors * (parts + 1));
        final CountDown countDownComplete = new CountDown(nbErrors);

        httpServer.createContext(downloadStorageEndpoint(blobContainer, "write_large_blob"), exchange -> {
            final long contentLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));

            if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getQuery().equals("uploads")) {
                // initiate multipart upload request
                if (countDownInitiate.countDown()) {
                    byte[] response = ("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <InitiateMultipartUploadResult>
                          <Bucket>bucket</Bucket>
                          <Key>write_large_blob</Key>
                          <UploadId>TEST</UploadId>
                        </InitiateMultipartUploadResult>""").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
            } else if ("PUT".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getQuery().contains("uploadId=TEST")
                && exchange.getRequestURI().getQuery().contains("partNumber=")) {
                    // upload part request
                    MD5DigestCalculatingInputStream md5 = new MD5DigestCalculatingInputStream(exchange.getRequestBody());
                    BytesReference bytes = Streams.readFully(md5);
                    assertThat((long) bytes.length(), anyOf(equalTo(lastPartSize), equalTo(bufferSize.getBytes())));
                    assertThat(contentLength, anyOf(equalTo(lastPartSize), equalTo(bufferSize.getBytes())));

                    if (countDownUploads.decrementAndGet() % 2 == 0) {
                        exchange.getResponseHeaders().add("ETag", Base16.encodeAsString(md5.getMd5Digest()));
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, -1);
                        exchange.close();
                        return;
                    }

                } else if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getQuery().equals("uploadId=TEST")) {
                    // complete multipart upload request
                    if (countDownComplete.countDown()) {
                        Streams.readFully(exchange.getRequestBody());
                        byte[] response = ("""
                            <?xml version="1.0" encoding="UTF-8"?>
                            <CompleteMultipartUploadResult>
                              <Bucket>bucket</Bucket>
                              <Key>write_large_blob</Key>
                            </CompleteMultipartUploadResult>""").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/xml");
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                        return;
                    }
                }

            // sends an error back or let the request time out
            if (useTimeout == false) {
                if (randomBoolean() && contentLength > 0) {
                    org.elasticsearch.core.Streams.readFully(
                        exchange.getRequestBody(),
                        new byte[randomIntBetween(1, Math.toIntExact(contentLength - 1))]
                    );
                } else {
                    Streams.readFully(exchange.getRequestBody());
                    exchange.sendResponseHeaders(
                        randomFrom(
                            HttpStatus.SC_INTERNAL_SERVER_ERROR,
                            HttpStatus.SC_BAD_GATEWAY,
                            HttpStatus.SC_SERVICE_UNAVAILABLE,
                            HttpStatus.SC_GATEWAY_TIMEOUT
                        ),
                        -1
                    );
                }
                exchange.close();
            }
        });

        blobContainer.writeBlob(randomPurpose(), "write_large_blob", new ZeroInputStream(blobSize), blobSize, false);

        assertThat(countDownInitiate.isCountedDown(), is(true));
        assertThat(countDownUploads.get(), equalTo(0));
        assertThat(countDownComplete.isCountedDown(), is(true));
    }

    public void testWriteLargeBlobStreaming() throws Exception {
        final boolean useTimeout = rarely();
        final TimeValue readTimeout = useTimeout ? TimeValue.timeValueMillis(randomIntBetween(100, 500)) : null;
        final ByteSizeValue bufferSize = new ByteSizeValue(5, ByteSizeUnit.MB);
        final BlobContainer blobContainer = createBlobContainer(null, readTimeout, true, bufferSize);

        final int parts = randomIntBetween(1, 5);
        final long lastPartSize = randomLongBetween(10, 512);
        final long blobSize = (parts * bufferSize.getBytes()) + lastPartSize;

        final int nbErrors = 2; // we want all requests to fail at least once
        final CountDown countDownInitiate = new CountDown(nbErrors);
        final AtomicInteger counterUploads = new AtomicInteger(0);
        final AtomicLong bytesReceived = new AtomicLong(0L);
        final CountDown countDownComplete = new CountDown(nbErrors);

        httpServer.createContext(downloadStorageEndpoint(blobContainer, "write_large_blob_streaming"), exchange -> {
            final long contentLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));

            if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getQuery().equals("uploads")) {
                // initiate multipart upload request
                if (countDownInitiate.countDown()) {
                    byte[] response = ("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <InitiateMultipartUploadResult>
                          <Bucket>bucket</Bucket>
                          <Key>write_large_blob_streaming</Key>
                          <UploadId>TEST</UploadId>
                        </InitiateMultipartUploadResult>""").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
            } else if ("PUT".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getQuery().contains("uploadId=TEST")
                && exchange.getRequestURI().getQuery().contains("partNumber=")) {
                    // upload part request
                    MD5DigestCalculatingInputStream md5 = new MD5DigestCalculatingInputStream(exchange.getRequestBody());
                    BytesReference bytes = Streams.readFully(md5);

                    if (counterUploads.incrementAndGet() % 2 == 0) {
                        bytesReceived.addAndGet(bytes.length());
                        exchange.getResponseHeaders().add("ETag", Base16.encodeAsString(md5.getMd5Digest()));
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, -1);
                        exchange.close();
                        return;
                    }

                } else if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getQuery().equals("uploadId=TEST")) {
                    // complete multipart upload request
                    if (countDownComplete.countDown()) {
                        Streams.readFully(exchange.getRequestBody());
                        byte[] response = ("""
                            <?xml version="1.0" encoding="UTF-8"?>
                            <CompleteMultipartUploadResult>
                              <Bucket>bucket</Bucket>
                              <Key>write_large_blob_streaming</Key>
                            </CompleteMultipartUploadResult>""").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/xml");
                        exchange.sendResponseHeaders(HttpStatus.SC_OK, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                        return;
                    }
                }

            // sends an error back or let the request time out
            if (useTimeout == false) {
                if (randomBoolean() && contentLength > 0) {
                    org.elasticsearch.core.Streams.readFully(
                        exchange.getRequestBody(),
                        new byte[randomIntBetween(1, Math.toIntExact(contentLength - 1))]
                    );
                } else {
                    Streams.readFully(exchange.getRequestBody());
                    exchange.sendResponseHeaders(
                        randomFrom(
                            HttpStatus.SC_INTERNAL_SERVER_ERROR,
                            HttpStatus.SC_BAD_GATEWAY,
                            HttpStatus.SC_SERVICE_UNAVAILABLE,
                            HttpStatus.SC_GATEWAY_TIMEOUT
                        ),
                        -1
                    );
                }
                exchange.close();
            }
        });

        blobContainer.writeMetadataBlob(randomNonDataPurpose(), "write_large_blob_streaming", false, randomBoolean(), out -> {
            final byte[] buffer = new byte[16 * 1024];
            long outstanding = blobSize;
            while (outstanding > 0) {
                if (randomBoolean()) {
                    int toWrite = Math.toIntExact(Math.min(randomIntBetween(64, buffer.length), outstanding));
                    out.write(buffer, 0, toWrite);
                    outstanding -= toWrite;
                } else {
                    out.write(0);
                    outstanding--;
                }
            }
        });

        assertEquals(blobSize, bytesReceived.get());
    }

    public void testReadRetriesAfterMeaningfulProgress() throws Exception {
        final int maxRetries = between(0, 5);
        final int bufferSizeBytes = scaledRandomIntBetween(
            0,
            randomFrom(1000, Math.toIntExact(S3Repository.BUFFER_SIZE_SETTING.get(Settings.EMPTY).getBytes()))
        );
        final BlobContainer blobContainer = createBlobContainer(maxRetries, null, true, ByteSizeValue.ofBytes(bufferSizeBytes));
        final int meaningfulProgressBytes = Math.max(1, bufferSizeBytes / 100);

        final byte[] bytes = randomBlobContent();

        @SuppressForbidden(reason = "use a http server")
        class FlakyReadHandler implements HttpHandler {
            private int failuresWithoutProgress;

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Streams.readFully(exchange.getRequestBody());
                if (failuresWithoutProgress >= maxRetries) {
                    final int rangeStart = getRangeStart(exchange);
                    assertThat(rangeStart, lessThan(bytes.length));
                    exchange.getResponseHeaders().add("Content-Type", bytesContentType());
                    final var remainderLength = bytes.length - rangeStart;
                    exchange.sendResponseHeaders(HttpStatus.SC_OK, remainderLength);
                    exchange.getResponseBody()
                        .write(
                            bytes,
                            rangeStart,
                            remainderLength < meaningfulProgressBytes ? remainderLength : between(meaningfulProgressBytes, remainderLength)
                        );
                } else if (randomBoolean()) {
                    failuresWithoutProgress += 1;
                    exchange.sendResponseHeaders(
                        randomFrom(
                            HttpStatus.SC_INTERNAL_SERVER_ERROR,
                            HttpStatus.SC_BAD_GATEWAY,
                            HttpStatus.SC_SERVICE_UNAVAILABLE,
                            HttpStatus.SC_GATEWAY_TIMEOUT
                        ),
                        -1
                    );
                } else if (randomBoolean()) {
                    final var bytesSent = sendIncompleteContent(exchange, bytes);
                    if (bytesSent < meaningfulProgressBytes) {
                        failuresWithoutProgress += 1;
                    } else {
                        exchange.getResponseBody().flush();
                    }
                } else {
                    failuresWithoutProgress += 1;
                }
                exchange.close();
            }
        }

        httpServer.createContext(downloadStorageEndpoint(blobContainer, "read_blob_max_retries"), new FlakyReadHandler());

        try (InputStream inputStream = blobContainer.readBlob(randomRetryingPurpose(), "read_blob_max_retries")) {
            final int readLimit;
            final InputStream wrappedStream;
            if (randomBoolean()) {
                // read stream only partly
                readLimit = randomIntBetween(0, bytes.length);
                wrappedStream = Streams.limitStream(inputStream, readLimit);
            } else {
                readLimit = bytes.length;
                wrappedStream = inputStream;
            }
            final byte[] bytesRead = BytesReference.toBytes(Streams.readFully(wrappedStream));
            assertArrayEquals(Arrays.copyOfRange(bytes, 0, readLimit), bytesRead);
        }
    }

    public void testReadDoesNotRetryForRepositoryAnalysis() {
        final int maxRetries = between(0, 5);
        final int bufferSizeBytes = scaledRandomIntBetween(
            0,
            randomFrom(1000, Math.toIntExact(S3Repository.BUFFER_SIZE_SETTING.get(Settings.EMPTY).getBytes()))
        );
        final BlobContainer blobContainer = createBlobContainer(maxRetries, null, true, ByteSizeValue.ofBytes(bufferSizeBytes));

        final byte[] bytes = randomBlobContent();

        @SuppressForbidden(reason = "use a http server")
        class FlakyReadHandler implements HttpHandler {
            private int failureCount;

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (failureCount != 0) {
                    ExceptionsHelper.maybeDieOnAnotherThread(new AssertionError("failureCount=" + failureCount));
                }
                failureCount += 1;
                Streams.readFully(exchange.getRequestBody());
                sendIncompleteContent(exchange, bytes);
                exchange.close();
            }
        }

        httpServer.createContext(downloadStorageEndpoint(blobContainer, "read_blob_repo_analysis"), new FlakyReadHandler());

        expectThrows(Exception.class, () -> {
            try (InputStream inputStream = blobContainer.readBlob(OperationPurpose.REPOSITORY_ANALYSIS, "read_blob_repo_analysis")) {
                final byte[] bytesRead = BytesReference.toBytes(Streams.readFully(inputStream));
                assertArrayEquals(Arrays.copyOfRange(bytes, 0, bytes.length), bytesRead);
            }
        });
    }

    @Override
    protected Matcher<Integer> getMaxRetriesMatcher(int maxRetries) {
        // some attempts make meaningful progress and do not count towards the max retry limit
        return allOf(greaterThanOrEqualTo(maxRetries), lessThanOrEqualTo(S3RetryingInputStream.MAX_SUPPRESSED_EXCEPTIONS));
    }

    @Override
    protected OperationPurpose randomRetryingPurpose() {
        return randomValueOtherThan(OperationPurpose.REPOSITORY_ANALYSIS, BlobStoreTestUtil::randomPurpose);
    }

    /**
     * Asserts that an InputStream is fully consumed, or aborted, when it is closed
     */
    private static class AssertingInputStream extends FilterInputStream {

        private final String blobName;
        private final boolean range;
        private final long position;
        private final long length;

        AssertingInputStream(InputStream in, String blobName) {
            super(in);
            this.blobName = blobName;
            this.position = 0L;
            this.length = Long.MAX_VALUE;
            this.range = false;
        }

        AssertingInputStream(InputStream in, String blobName, long position, long length) {
            super(in);
            this.blobName = blobName;
            this.position = position;
            this.length = length;
            this.range = true;
        }

        @Override
        public String toString() {
            String description = "[blobName='" + blobName + "', range=" + range;
            if (range) {
                description += ", position=" + position;
                description += ", length=" + length;
            }
            description += ']';
            return description;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (in instanceof final S3RetryingInputStream s3Stream) {
                assertTrue(
                    "Stream "
                        + toString()
                        + " should have reached EOF or should have been aborted but got [eof="
                        + s3Stream.isEof()
                        + ", aborted="
                        + s3Stream.isAborted()
                        + ']',
                    s3Stream.isEof() || s3Stream.isAborted()
                );
            } else {
                assertThat(in, instanceOf(ByteArrayInputStream.class));
                assertThat(((ByteArrayInputStream) in).available(), equalTo(0));
            }
        }
    }
}
