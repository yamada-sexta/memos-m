package org.example.memosm.api

import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.manager.AttachmentManager
import org.junit.Assert.*
import org.junit.Test

class ResourceUrlsTest {
    @Test fun `media authentication checks each request and redirect destination`() {
        val own =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val external =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        val externalUrl = "http://127.0.0.1:${external.address.port}/segment"
        external.createContext("/") { exchange ->
            seen += "external" to exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("segment".toByteArray()) }
        }
        own.createContext("/") { exchange ->
            seen += "own" to exchange.requestHeaders.getFirst("Authorization")
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", externalUrl)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("playlist".toByteArray()) }
            }
        }
        own.start()
        external.start()
        val origin = "http://127.0.0.1:${own.address.port}"
        val client =
            okhttp3.OkHttpClient
                .Builder()
                .addNetworkInterceptor(resourceAuthentication(origin, "secret"))
                .build()
        try {
            for (url in listOf("$origin/playlist", externalUrl, "$origin/redirect")) {
                client
                    .newCall(
                        okhttp3.Request
                            .Builder()
                            .url(url)
                            .build(),
                    ).execute()
                    .use { assertTrue(it.isSuccessful) }
            }
            assertEquals(listOf("own" to "Bearer secret", "external" to null, "own" to "Bearer secret", "external" to null), seen)
            assertNull(resourceAuthorization(origin, java.io.File("local-image"), "secret"))
            assertNull(resourceAuthorization(origin, externalUrl, "secret"))
            assertEquals("Bearer secret", resourceAuthorization(origin, "$origin/original", "secret"))
        } finally {
            own.stop(0)
            external.stop(0)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `authentication requires matching scheme host and effective port`() {
        assertTrue(sameOrigin("https://server.example/subpath/", "https://SERVER.example:443/file/photo"))
        for (resource in listOf("https://storage.example/photo", "http://server.example/photo", "https://server.example:8443/photo")) {
            assertFalse(sameOrigin("https://server.example", resource))
        }
    }

    @Test fun `resource resolution preserves server subpath and escaped thumbnail parameters`() {
        assertEquals("https://server.example/subpath/file/photo", resolveResourceUrl("https://server.example/subpath/", "/file/photo"))
        val attachment =
            Attachment(filename = "photo", type = "image/jpeg", externalLink = "/file/photo?share%5Ftoken=a%26b&thumbnail=false#fragment")
        assertEquals(
            "https://server.example/subpath/file/photo?share%5Ftoken=a%26b&thumbnail=true#fragment",
            AttachmentManager.getAttachmentThumbnailUrl("https://server.example/subpath/", attachment),
        )
    }
}
